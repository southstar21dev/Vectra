package main

import (
	"log"
	"os"
	"strings"

	"ganigeorgiev.com/pocketbase/pocketbase"
	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/plugins/migratecmd"

	"vectra/pb/internal/authguard"
	"vectra/pb/internal/catalog"
	"vectra/pb/internal/config"
	"vectra/pb/internal/passkeys"
	"vectra/pb/internal/proxytrust"
	"vectra/pb/internal/push"
	"vectra/pb/internal/reauth"
	"vectra/pb/internal/routes"
	"vectra/pb/internal/schema"
	"vectra/pb/internal/versiongate"
	_ "vectra/pb/migrations"
)

func main() {
	app := pocketbase.New()

	// Trust a proxy's client-IP header only when TRUSTED_PROXY says one sits in
	// front (cloudflare or 1); otherwise per-IP rate limits key on the socket address.
	proxytrust.Register(app)

	schema.RegisterTimestamps(app)
	// Per-user row caps so a free account cannot fill the disk.
	schema.RegisterRowQuotas(app)
	// Reject push subscriptions whose endpoint is not a known push service, so
	// the send path can never be aimed at an arbitrary host.
	push.RegisterSubscriptionValidation(app)

	// Reclaim expired passkey challenges (the public login/start writes one per
	// call and authentication challenges have no user, so cascade delete never
	// touches them).
	passkeys.RegisterChallengeCleanup(app)
	// Revoke passkeys when the password or email changes (tokenKey rotation),
	// so a passkey enrolled before a password reset cannot log in after it.
	passkeys.RegisterRevokeOnCredentialChange(app)
	// Count wrong oldPassword guesses on PATCH users/{id} against the re-auth budget.
	reauth.RegisterPasswordChangeGuard(app)
	// Per-identity and /64-keyed limits, and the unused email flows switched off.
	authguard.Register(app)

	migratecmd.MustRegister(app, app.RootCmd, migratecmd.Config{Automigrate: false})

	app.OnServe().BindFunc(func(se *core.ServeEvent) error {
		// Log missing or misspelled settings at boot instead of on the first
		// request; VECTRA_STRICT_CONFIG=1 turns a missing required one into a
		// failed start (see backend/README.md).
		strict := strings.TrimSpace(os.Getenv("VECTRA_STRICT_CONFIG")) == "1"
		if err := config.Validate(os.Getenv, os.Environ(), strict); err != nil {
			return err
		}
		// Refuses app builds older than the minimum in app_config (426), before any route runs.
		versiongate.Register(se)
		routes.Register(se)
		// The public transit catalog (/catalog/*.json): built from Futár, refreshed daily,
		// kept under the data dir. The builder stops with the server.
		stopCatalog := catalog.Register(se, app.DataDir())
		app.OnTerminate().BindFunc(func(e *core.TerminateEvent) error {
			stopCatalog()
			return e.Next()
		})
		return se.Next()
	})

	if err := app.Start(); err != nil {
		log.Fatal(err)
	}
}
