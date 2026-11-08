package routes

import (
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/account"
	"vectra/pb/internal/crypto"
	"vectra/pb/internal/futar"
	"vectra/pb/internal/neptun"
	"vectra/pb/internal/passkeys"
	"vectra/pb/internal/push"
	"vectra/pb/internal/resetpage"
)

func Register(se *core.ServeEvent) {
	g := se.Router.Group("/api/vectra")
	g.POST("/crypto", crypto.Handler).Bind(apis.RequireAuth())
	// Public: transit arrivals are public data and the proxy only hides the
	// Futár API key server-side; the endpoint whitelist + param coercion are
	// the abuse control, so this route is intentionally not auth-bound.
	g.POST("/futar", futar.Handler)
	// JWT-gated; the handler itself enforces that the caller is the target user
	// or a superuser (internal service-role caller).
	g.POST("/push/send", push.Handler).Bind(apis.RequireAuth())
	// JWT-gated; deletes only the caller's own data.
	g.POST("/account/delete", account.Handler).Bind(apis.RequireAuth())
	// JWT-gated; verifies the current password and switches the caller's own
	// email instantly (no SMTP confirmation), matching the old behavior.
	g.POST("/account/email", account.EmailHandler).Bind(apis.RequireAuth("users"))
	// JWT-gated; replaces only the caller's own calendar (owner is always the
	// authenticated user id, never a client-supplied one).
	g.POST("/neptun/replace", neptun.Handler).Bind(apis.RequireAuth())
	// Passkeys: registration and management require a logged-in user; the login
	// ceremony runs before a session exists, so those two endpoints are public.
	g.GET("/passkeys", passkeys.ListHandler).Bind(apis.RequireAuth("users"))
	g.DELETE("/passkeys/{id}", passkeys.DeleteHandler).Bind(apis.RequireAuth("users"))
	g.POST("/passkeys/register/start", passkeys.RegistrationStartHandler).Bind(apis.RequireAuth("users"))
	g.POST("/passkeys/register/verify", passkeys.RegistrationVerifyHandler).Bind(apis.RequireAuth("users"))
	se.Router.POST("/api/vectra/passkeys/login/start", passkeys.LoginStartHandler)
	se.Router.POST("/api/vectra/passkeys/login/verify", passkeys.LoginVerifyHandler)
	// Public: the page reset emails link to (the token travels in the URL fragment, never to the
	// server); it posts to PocketBase's own confirm-password-reset endpoint.
	se.Router.GET(resetpage.Path, resetpage.Handler)
	se.Router.GET(resetpage.Path+"/icon.png", resetpage.IconHandler)
}
