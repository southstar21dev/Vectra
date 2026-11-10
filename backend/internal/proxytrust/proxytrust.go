// Package proxytrust decides, from the TRUSTED_PROXY environment variable,
// whether PocketBase trusts the X-Forwarded-For header for the client IP.
//
// e.RealIP() (and with it every per-IP rate limit: PocketBase's auth limiter,
// the reset-email limiter, passkey login/start, the Futár proxy, the re-auth
// checks) reads the client address from the trusted-proxy headers. Behind
// Coolify/Traefik that header is needed, or every user shares the proxy's
// address and one bucket. Reached directly (no proxy in front), the header is
// client-controlled, so trusting it lets a caller pick a fresh bucket per
// request. The deployment knows which case it is in, so it says so in the env:
//
//	TRUSTED_PROXY=1   behind a reverse proxy that appends the client IP
//	                  (production and dev on Coolify)
//	unset / other     the socket address is used and X-Forwarded-For ignored
//
// The environment always wins over the settings stored in the database (a
// value saved from the dashboard is replaced on every settings reload).
// UseLeftmostIP stays false: PocketBase then takes the right-most valid entry,
// which is the one the proxy appended itself; the left-most entries are
// client-supplied.
package proxytrust

import (
	"os"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

const (
	// EnvVar turns trusted-proxy handling on when set to 1, true or yes.
	EnvVar = "TRUSTED_PROXY"
	// Header is the forwarded-for header Traefik appends to.
	Header = "X-Forwarded-For"
)

// Enabled reports whether TRUSTED_PROXY asks for the proxy header to be
// trusted.
func Enabled() bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(EnvVar))) {
	case "1", "true", "yes":
		return true
	default:
		return false
	}
}

// Apply sets the in-memory trusted-proxy settings: the forwarded-for header
// when enabled, no header otherwise.
func Apply(s *core.Settings, enabled bool) {
	s.TrustedProxy.UseLeftmostIP = false
	if enabled {
		s.TrustedProxy.Headers = []string{Header}
		return
	}
	s.TrustedProxy.Headers = nil
}

// Register applies the TRUSTED_PROXY decision after every settings reload
// (the first one runs during bootstrap, later ones after a dashboard save), so
// the stored settings can never turn it on or off behind the environment's
// back. It also applies it once right away, for an app that is already
// bootstrapped.
func Register(app core.App) {
	app.OnSettingsReload().BindFunc(func(e *core.SettingsReloadEvent) error {
		if err := e.Next(); err != nil {
			return err
		}
		Apply(e.App.Settings(), Enabled())
		return nil
	})
	if app.IsBootstrapped() {
		Apply(app.Settings(), Enabled())
	}
}
