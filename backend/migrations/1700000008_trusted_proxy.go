package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// No longer changes anything. This migration used to trust X-Forwarded-For
// unconditionally, which let a client that reaches the port directly (no proxy
// in front) choose a fresh per-IP rate-limit bucket on every request. Whether
// the header is trusted is now decided at runtime by the TRUSTED_PROXY
// environment variable (internal/proxytrust), which overrides whatever this
// migration stored on databases that already applied it. Production and dev
// on Coolify must set TRUSTED_PROXY=1 (see backend/README.md).
//
// The down step still removes the header this migration used to store.
func init() {
	const header = "X-Forwarded-For"

	m.Register(func(core.App) error {
		return nil
	}, func(app core.App) error {
		settings := app.Settings()
		if len(settings.TrustedProxy.Headers) == 1 && settings.TrustedProxy.Headers[0] == header {
			settings.TrustedProxy.Headers = nil
			return app.Save(settings)
		}
		return nil
	})
}
