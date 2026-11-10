package resetpage

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/stretchr/testify/require"
)

func serve(t *testing.T) *httptest.ResponseRecorder {
	t.Helper()
	e := &core.RequestEvent{}
	e.Request = httptest.NewRequest(http.MethodGet, "/reset", nil)
	rec := httptest.NewRecorder()
	e.Response = rec
	require.NoError(t, Handler(e))
	return rec
}

func TestHandlerSecurityHeaders(t *testing.T) {
	rec := serve(t)
	require.Equal(t, http.StatusOK, rec.Code)
	csp := rec.Header().Get("Content-Security-Policy")
	require.Contains(t, csp, "default-src 'none'")
	require.Contains(t, csp, "frame-ancestors 'none'")
	require.Contains(t, csp, "form-action 'none'")
	require.Equal(t, "no-referrer", rec.Header().Get("Referrer-Policy"))
	require.Equal(t, "no-store", rec.Header().Get("Cache-Control"))
	require.Equal(t, "nosniff", rec.Header().Get("X-Content-Type-Options"))
}

func TestHandlerNonceIsFreshAndApplied(t *testing.T) {
	a, b := serve(t), serve(t)
	nonceOf := func(rec *httptest.ResponseRecorder) string {
		csp := rec.Header().Get("Content-Security-Policy")
		i := strings.Index(csp, "'nonce-")
		require.GreaterOrEqual(t, i, 0)
		rest := csp[i+len("'nonce-"):]
		return rest[:strings.Index(rest, "'")]
	}
	na, nb := nonceOf(a), nonceOf(b)
	require.NotEqual(t, na, nb)
	body := a.Body.String()
	require.NotContains(t, body, "{{NONCE}}")
	require.Contains(t, body, `nonce="`+na+`"`)
}
