package proxytrust_test

import (
	"net/http/httptest"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/proxytrust"
	"vectra/pb/internal/ratelimit"
	_ "vectra/pb/migrations"
)

// newApp boots a test app with the hook registered and the settings reloaded,
// the way `serve` does at bootstrap.
func newApp(t *testing.T) *tests.TestApp {
	t.Helper()
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	t.Cleanup(app.Cleanup)
	proxytrust.Register(app)
	require.NoError(t, app.ReloadSettings())
	return app
}

// clientKey is the per-IP limiter key a request from remoteAddr carrying the
// given X-Forwarded-For value gets.
func clientKey(app core.App, remoteAddr, xff string) string {
	req := httptest.NewRequest("POST", "/api/vectra/passkeys/login/start", nil)
	req.RemoteAddr = remoteAddr
	if xff != "" {
		req.Header.Set("X-Forwarded-For", xff)
	}
	e := &core.RequestEvent{App: app, Event: router.Event{Request: req}}
	return ratelimit.ClientIP(e)
}

// Without TRUSTED_PROXY, a client reaching the port directly cannot pick a new
// rate-limit bucket per request by sending its own X-Forwarded-For.
func TestForwardedForIgnoredByDefault(t *testing.T) {
	t.Setenv(proxytrust.EnvVar, "")
	app := newApp(t)

	require.Empty(t, app.Settings().TrustedProxy.Headers)
	for _, xff := range []string{"", "1.2.3.4", "5.6.7.8", "9.9.9.9, 10.10.10.10"} {
		require.Equal(t, "198.51.100.7", clientKey(app, "198.51.100.7:4711", xff), "xff %q", xff)
	}
}

// Behind the proxy (TRUSTED_PROXY=1) the right-most entry, the one the proxy
// appended, is the client; client-supplied entries to its left are ignored.
func TestForwardedForTrustedWhenEnabled(t *testing.T) {
	t.Setenv(proxytrust.EnvVar, "1")
	app := newApp(t)

	require.Equal(t, []string{"X-Forwarded-For"}, app.Settings().TrustedProxy.Headers)
	require.False(t, app.Settings().TrustedProxy.UseLeftmostIP)
	require.Equal(t, "203.0.113.9", clientKey(app, "10.0.1.2:80", "203.0.113.9"))
	require.Equal(t, "203.0.113.9", clientKey(app, "10.0.1.2:80", "6.6.6.6, 203.0.113.9"),
		"a spoofed left-most entry must not change the bucket")
	require.Equal(t, "10.0.1.2", clientKey(app, "10.0.1.2:80", ""), "no header: the socket address")
}

// A trusted-proxy value saved from the dashboard does not outlive the reload
// that follows the save: the environment decides.
func TestStoredSettingCannotOverrideEnv(t *testing.T) {
	t.Setenv(proxytrust.EnvVar, "")
	app := newApp(t)

	s := app.Settings()
	s.TrustedProxy.Headers = []string{"X-Forwarded-For"}
	require.NoError(t, app.Save(s))
	require.Empty(t, app.Settings().TrustedProxy.Headers)
	require.Equal(t, "198.51.100.7", clientKey(app, "198.51.100.7:4711", "1.2.3.4"))

	t.Setenv(proxytrust.EnvVar, "true")
	s = app.Settings()
	s.TrustedProxy.Headers = nil
	require.NoError(t, app.Save(s))
	require.Equal(t, []string{"X-Forwarded-For"}, app.Settings().TrustedProxy.Headers)
}

func TestEnabledValues(t *testing.T) {
	for v, want := range map[string]bool{"1": true, "true": true, "YES": true, " 1 ": true, "": false, "0": false, "false": false, "no": false} {
		t.Setenv(proxytrust.EnvVar, v)
		require.Equal(t, want, proxytrust.Enabled(), "TRUSTED_PROXY=%q", v)
	}
}
