package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Trusts the reverse proxy's X-Forwarded-For so e.RealIP() is the actual
// client. Production sits behind Coolify/Traefik (the only ingress, see the
// deploy runbook), so without this every request's RemoteIP is the proxy and
// all per-IP limiters (passkey login, Futár proxy, email change) share one
// bucket across all users.
//
// UseLeftmostIP stays false: PocketBase then takes the right-most valid IP,
// which is the one Traefik appended itself; the left-most entries are
// client-supplied and spoofable.
//
// An operator-configured value is left alone.
func init() {
	const header = "X-Forwarded-For"

	m.Register(func(app core.App) error {
		settings := app.Settings()
		if len(settings.TrustedProxy.Headers) > 0 {
			return nil
		}
		settings.TrustedProxy.Headers = []string{header}
		settings.TrustedProxy.UseLeftmostIP = false
		return app.Save(settings)
	}, func(app core.App) error {
		settings := app.Settings()
		if len(settings.TrustedProxy.Headers) == 1 && settings.TrustedProxy.Headers[0] == header {
			settings.TrustedProxy.Headers = nil
			return app.Save(settings)
		}
		return nil
	})
}
