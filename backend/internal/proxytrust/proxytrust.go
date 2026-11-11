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
//	TRUSTED_PROXY=cloudflare  behind Cloudflare (production and dev): trust
//	                          CF-Connecting-IP, which Cloudflare always sets
//	                          to the connecting client and overwrites if the
//	                          client sent one
//	TRUSTED_PROXY=1           behind a reverse proxy that appends the client
//	                          IP and nothing in front of it
//	unset / other             the socket address is used and both headers
//	                          ignored
//
// Behind Cloudflare, X-Forwarded-For ends with Cloudflare's edge address
// (Traefik appends the peer it saw), which changes from request to request, so
// keying on it gives every request a fresh bucket. CF-Connecting-IP can only be
// forged by a client that bypasses Cloudflare and reaches the origin directly,
// so the origin should accept Cloudflare's addresses only.
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
	// EnvVar selects the trusted-proxy mode (see the package comment).
	EnvVar = "TRUSTED_PROXY"
	// Header is the forwarded-for header Traefik appends to.
	Header = "X-Forwarded-For"
	// CloudflareHeader carries the client IP Cloudflare saw.
	CloudflareHeader = "CF-Connecting-IP"
)

// Mode is how the client IP is found.
type Mode int

const (
	// Direct: the socket address; no header is trusted.
	Direct Mode = iota
	// ForwardedFor: the right-most X-Forwarded-For entry, appended by the proxy.
	ForwardedFor
	// Cloudflare: CF-Connecting-IP.
	Cloudflare
)

// CurrentMode reads TRUSTED_PROXY.
func CurrentMode() Mode {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(EnvVar))) {
	case "1", "true", "yes":
		return ForwardedFor
	case "cloudflare", "cf":
		return Cloudflare
	default:
		return Direct
	}
}

// Enabled reports whether TRUSTED_PROXY asks for a proxy header to be trusted.
func Enabled() bool { return CurrentMode() != Direct }

// Apply sets the in-memory trusted-proxy settings for mode.
func Apply(s *core.Settings, mode Mode) {
	s.TrustedProxy.UseLeftmostIP = false
	switch mode {
	case ForwardedFor:
		s.TrustedProxy.Headers = []string{Header}
	case Cloudflare:
		s.TrustedProxy.Headers = []string{CloudflareHeader}
	default:
		s.TrustedProxy.Headers = nil
	}
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
		Apply(e.App.Settings(), CurrentMode())
		return nil
	})
	if app.IsBootstrapped() {
		Apply(app.Settings(), CurrentMode())
	}
}
