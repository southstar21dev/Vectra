package routes_test

import (
	"net/http"
	"strconv"
	"strings"
	"testing"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"

	"vectra/pb/internal/routes"
	_ "vectra/pb/migrations"
)

// TestRouteBindings boots PocketBase's real router (via tests.ApiScenario),
// registers the production route table on the serve event, and asserts the
// auth gating behaviorally: gated routes reject anonymous callers while the
// intentionally-public routes are reachable without a token.
func TestRouteBindings(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", "test-key")
	t.Setenv("WEBAUTHN_RP_ID", "example.org")
	t.Setenv("WEBAUTHN_RP_ORIGINS", "https://example.org")
	t.Setenv("WEBAUTHN_RP_DISPLAY_NAME", "Vectra Test")

	register := func(_ testing.TB, _ *tests.TestApp, e *core.ServeEvent) {
		routes.Register(e)
	}

	scenarios := []tests.ApiScenario{
		{
			Name:            "crypto requires auth",
			Method:          http.MethodPost,
			URL:             "/api/vectra/crypto",
			Body:            strings.NewReader(`{"op":"digest","parts":[["x"]]}`),
			ExpectedStatus:  http.StatusUnauthorized,
			ExpectedContent: []string{`"status":401`},
			BeforeTestFunc:  register,
		},
		{
			Name:            "account delete requires auth",
			Method:          http.MethodPost,
			URL:             "/api/vectra/account/delete",
			ExpectedStatus:  http.StatusUnauthorized,
			ExpectedContent: []string{`"status":401`},
			BeforeTestFunc:  register,
		},
		{
			Name:            "account email requires auth",
			Method:          http.MethodPost,
			URL:             "/api/vectra/account/email",
			Body:            strings.NewReader(`{"currentPassword":"x","newEmail":"n@test.local"}`),
			ExpectedStatus:  http.StatusUnauthorized,
			ExpectedContent: []string{`"status":401`},
			BeforeTestFunc:  register,
		},
		{
			Name:            "push send requires auth",
			Method:          http.MethodPost,
			URL:             "/api/vectra/push/send",
			Body:            strings.NewReader(`{"user_id":"x","title":"t","body":"b"}`),
			ExpectedStatus:  http.StatusUnauthorized,
			ExpectedContent: []string{`"status":401`},
			BeforeTestFunc:  register,
		},
		{
			Name:            "neptun replace requires auth",
			Method:          http.MethodPost,
			URL:             "/api/vectra/neptun/replace",
			ExpectedStatus:  http.StatusUnauthorized,
			ExpectedContent: []string{`"status":401`},
			BeforeTestFunc:  register,
		},
		{
			Name:            "passkeys list requires auth",
			Method:          http.MethodGet,
			URL:             "/api/vectra/passkeys",
			ExpectedStatus:  http.StatusUnauthorized,
			ExpectedContent: []string{`"status":401`},
			BeforeTestFunc:  register,
		},
		{
			Name:            "futar is public (reaches validation, not 401)",
			Method:          http.MethodPost,
			URL:             "/api/vectra/futar",
			Body:            strings.NewReader(`{}`),
			ExpectedStatus:  http.StatusBadRequest,
			ExpectedContent: []string{`"status":400`},
			BeforeTestFunc:  register,
		},
		{
			Name:               "reset page is public and locked down",
			Method:             http.MethodGet,
			URL:                "/reset-password",
			ExpectedStatus:     http.StatusOK,
			ExpectedContent:    []string{"Choose a new password", "/api/collections/users/confirm-password-reset"},
			NotExpectedContent: []string{"{{NONCE}}"},
			AfterTestFunc: func(t testing.TB, _ *tests.TestApp, res *http.Response) {
				for k, v := range map[string]string{"Referrer-Policy": "no-referrer", "Cache-Control": "no-store"} {
					if got := res.Header.Get(k); got != v {
						t.Errorf("%s = %q, want %q", k, got, v)
					}
				}
				if csp := res.Header.Get("Content-Security-Policy"); !strings.Contains(csp, "script-src 'nonce-") {
					t.Errorf("missing nonce CSP: %q", csp)
				}
			},
			BeforeTestFunc: register,
		},
		{
			Name:               "reset page icon",
			Method:             http.MethodGet,
			URL:                "/reset-password/icon.png",
			ExpectedStatus:     http.StatusOK,
			NotExpectedContent: []string{"<html"},
			AfterTestFunc: func(t testing.TB, _ *tests.TestApp, res *http.Response) {
				if ct := res.Header.Get("Content-Type"); ct != "image/png" {
					t.Errorf("Content-Type = %q", ct)
				}
			},
			BeforeTestFunc: register,
		},
		{
			Name:            "passkey login start is public",
			Method:          http.MethodPost,
			URL:             "/api/vectra/passkeys/login/start",
			ExpectedStatus:  http.StatusOK,
			ExpectedContent: []string{`"challenge_id"`},
			BeforeTestFunc:  register,
		},
	}

	for i := range scenarios {
		scenarios[i].Test(t)
	}
}

// TestSuperuserBindings enforces the auth convention: routes for signed-in
// users refuse a superuser token (403), and push send, the one route
// superusers may call, lets it through to the handler.
func TestSuperuserBindings(t *testing.T) {
	register := func(_ testing.TB, _ *tests.TestApp, e *core.ServeEvent) { routes.Register(e) }
	super := func(t testing.TB, app *tests.TestApp) {
		_, err := app.FindAuthRecordByEmail(core.CollectionNameSuperusers, "root@test.local")
		if err == nil {
			return
		}
		col, err := app.FindCollectionByNameOrId(core.CollectionNameSuperusers)
		if err != nil {
			t.Fatal(err)
		}
		r := core.NewRecord(col)
		r.SetEmail("root@test.local")
		r.SetPassword("password123")
		if err := app.Save(r); err != nil {
			t.Fatal(err)
		}
	}
	token := func(t testing.TB, app *tests.TestApp) string {
		super(t, app)
		r, err := app.FindAuthRecordByEmail(core.CollectionNameSuperusers, "root@test.local")
		if err != nil {
			t.Fatal(err)
		}
		tok, err := r.NewAuthToken()
		if err != nil {
			t.Fatal(err)
		}
		return tok
	}

	for _, tc := range []struct {
		name, method, url string
		status            int
	}{
		{"crypto", http.MethodPost, "/api/vectra/crypto", http.StatusForbidden},
		{"account delete", http.MethodPost, "/api/vectra/account/delete", http.StatusForbidden},
		{"account email", http.MethodPost, "/api/vectra/account/email", http.StatusForbidden},
		{"neptun replace", http.MethodPost, "/api/vectra/neptun/replace", http.StatusForbidden},
		{"passkeys list", http.MethodGet, "/api/vectra/passkeys", http.StatusForbidden},
		{"push send is superuser-capable", http.MethodPost, "/api/vectra/push/send", http.StatusBadRequest},
	} {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			app, err := tests.NewTestApp()
			if err != nil {
				t.Fatal(err)
			}
			defer app.Cleanup()
			s := tests.ApiScenario{
				Name:            tc.name,
				Method:          tc.method,
				URL:             tc.url,
				Body:            strings.NewReader(`{}`),
				Headers:         map[string]string{"Authorization": token(t, app)},
				ExpectedStatus:  tc.status,
				ExpectedContent: []string{`"status":` + strconv.Itoa(tc.status)},
				TestAppFactory:  func(testing.TB) *tests.TestApp { return app },
				BeforeTestFunc:  register,
			}
			s.DisableTestAppCleanup = true
			s.Test(t)
		})
	}
}
