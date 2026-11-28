package routes

import (
	"ganigeorgiev.com/pocketbase/pocketbase/apis"
	"ganigeorgiev.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/account"
	"vectra/pb/internal/crypto"
	"vectra/pb/internal/futar"
	"vectra/pb/internal/limits"
	"vectra/pb/internal/neptun"
	"vectra/pb/internal/passkeys"
	"vectra/pb/internal/ratelimit"
	"vectra/pb/internal/reauth"
	"vectra/pb/internal/resetpage"
)

// Deps is what the route handlers spend: one limiter or guard per budget, built
// once and shared by every request (and, for Reauth, by the password-change
// hook that must draw on the same buckets). Tests build their own.
type Deps struct {
	// Reauth bounds current-password checks (account delete and email change,
	// passkey enrolment).
	Reauth *reauth.Guard
	// Crypto is each user's budget of values on /crypto.
	Crypto *ratelimit.Limiter
	// Neptun caps /neptun/replace calls per user.
	Neptun *ratelimit.Limiter
	// PasskeyLoginStart caps public passkey login ceremonies per client IP.
	PasskeyLoginStart *ratelimit.Limiter
	// Futar is the Futár proxy, which also owns the shared upstream budget the
	// catalog builder draws on.
	Futar *futar.Proxy
}

// NewDeps builds Deps from the values in internal/limits. guard is the re-auth
// guard, passed in because main registers it elsewhere too.
func NewDeps(guard *reauth.Guard) Deps {
	return Deps{
		Reauth:            guard,
		Crypto:            limits.CryptoValues.New(),
		Neptun:            limits.NeptunReplace.New(),
		PasskeyLoginStart: limits.PasskeyLoginStart.New(),
		Futar:             futar.DefaultProxy(),
	}
}

// Register binds the /api/vectra routes. Auth convention: a route for signed-in
// users binds apis.RequireAuth("users"), so a superuser token is refused with a
// 403 before the handler runs; a route that superusers may call would say so
// explicitly in its binding. Public routes carry no binding and say why. Every route lives on the /api/vectra group. The handlers keep a
// nil-Auth guard so they stay safe when called directly.
func Register(se *core.ServeEvent, d Deps) {
	g := se.Router.Group("/api/vectra")
	g.POST("/crypto", crypto.Handler(d.Crypto)).Bind(apis.RequireAuth("users"))
	// Public: transit arrivals are public data and the proxy only hides the
	// Futár API key server-side; the endpoint whitelist + param coercion are
	// the abuse control, so this route is intentionally not auth-bound.
	g.POST("/futar", d.Futar.Handler)
	// JWT-gated; verifies the current password, then deletes only the caller's own data.
	g.POST("/account/delete", account.Handler(d.Reauth)).Bind(apis.RequireAuth("users"))
	// JWT-gated; verifies the current password and switches the caller's own
	// email instantly (no SMTP confirmation), matching the old behavior.
	g.POST("/account/email", account.EmailHandler(d.Reauth)).Bind(apis.RequireAuth("users"))
	// JWT-gated; replaces only the caller's own calendar (owner is always the
	// authenticated user id, never a client-supplied one).
	g.POST("/neptun/replace", neptun.Handler(d.Neptun)).Bind(apis.RequireAuth("users"))
	// Passkeys: registration and management require a logged-in user; the login
	// ceremony runs before a session exists, so those two endpoints are public.
	g.GET("/passkeys", passkeys.ListHandler).Bind(apis.RequireAuth("users"))
	g.DELETE("/passkeys/{id}", passkeys.DeleteHandler).Bind(apis.RequireAuth("users"))
	g.POST("/passkeys/register/start", passkeys.RegistrationStartHandler(d.Reauth)).Bind(apis.RequireAuth("users"))
	g.POST("/passkeys/register/verify", passkeys.RegistrationVerifyHandler).Bind(apis.RequireAuth("users"))
	g.POST("/passkeys/login/start", passkeys.LoginStartHandler(d.PasskeyLoginStart))
	g.POST("/passkeys/login/verify", passkeys.LoginVerifyHandler)
	// Public: the page reset emails link to (the token travels in the URL fragment, never to the
	// server); it posts to PocketBase's own confirm-password-reset endpoint.
	se.Router.GET(resetpage.Path, resetpage.Handler)
	se.Router.GET(resetpage.Path+"/icon.png", resetpage.IconHandler)
}
