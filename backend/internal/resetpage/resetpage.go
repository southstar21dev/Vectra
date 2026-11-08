// Package resetpage serves the Vectra-styled page that password-reset emails link to, in place of
// PocketBase's admin-UI page. The page posts the new password to PocketBase's own
// confirm-password-reset endpoint on the same origin, so no new API is involved.
package resetpage

import (
	"crypto/rand"
	"encoding/base64"
	_ "embed"
	"net/http"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// Path is where reset emails point: {APP_URL}/reset-password#{TOKEN}.
const Path = "/reset-password"

//go:embed page.html
var page string

//go:embed icon.png
var icon []byte

// Handler serves the page with a fresh CSP nonce, so only its own inline script and style run.
func Handler(e *core.RequestEvent) error {
	nonce, err := newNonce()
	if err != nil {
		return err
	}
	h := e.Response.Header()
	h.Set("Content-Security-Policy", strings.Join([]string{
		"default-src 'none'",
		"script-src 'nonce-" + nonce + "'",
		"style-src 'nonce-" + nonce + "'",
		"img-src 'self'",
		"connect-src 'self'",
		"form-action 'none'",
		"base-uri 'none'",
		"frame-ancestors 'none'",
	}, "; "))
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Cache-Control", "no-store")
	return e.HTML(http.StatusOK, strings.ReplaceAll(page, "{{NONCE}}", nonce))
}

// IconHandler serves the app icon shown on the page and in the emails.
func IconHandler(e *core.RequestEvent) error {
	e.Response.Header().Set("Cache-Control", "public, max-age=86400")
	return e.Blob(http.StatusOK, "image/png", icon)
}

func newNonce() (string, error) {
	b := make([]byte, 18)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(b), nil
}
