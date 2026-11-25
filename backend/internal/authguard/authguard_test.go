package authguard

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/ratelimit"
	_ "vectra/pb/migrations"
)

func freshLimiters(t *testing.T) {
	t.Helper()
	pi, pf, ps, pr := IdentityFailures, IPFailures, Signups, Resets
	IdentityFailures = ratelimit.New(2, time.Minute)
	IPFailures = ratelimit.New(100, time.Minute)
	Signups = ratelimit.New(100, time.Minute)
	Resets = ratelimit.New(100, time.Minute)
	t.Cleanup(func() { IdentityFailures, IPFailures, Signups, Resets = pi, pf, ps, pr })
}

func TestLoginFailureLimitAndDisabledFlows(t *testing.T) {
	freshLimiters(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.SetEmail("guard@test.local")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))
	token, err := u.NewAuthToken()
	require.NoError(t, err)

	Register(app)
	factory := func(testing.TB) *tests.TestApp { return app }
	login := func(name, pw string, status int, content string) tests.ApiScenario {
		return tests.ApiScenario{
			Name: name, Method: http.MethodPost, URL: "/api/collections/users/auth-with-password",
			Body:           strings.NewReader(`{"identity":"Guard@Test.local ","password":"` + pw + `"}`),
			ExpectedStatus: status, ExpectedContent: []string{content},
			TestAppFactory: factory, DisableTestAppCleanup: true,
		}
	}
	scenarios := []tests.ApiScenario{
		login("wrong 1", "bad", http.StatusBadRequest, `"status":400`),
		login("wrong 2", "bad", http.StatusBadRequest, `"status":400`),
		login("blocked even with the right password", "password123", http.StatusTooManyRequests, `"status":429`),
		{
			Name: "email change flow is off", Method: http.MethodPost, URL: "/api/collections/users/request-email-change",
			Headers: map[string]string{"Authorization": token}, Body: strings.NewReader(`{"newEmail":"x@test.local"}`),
			ExpectedStatus: http.StatusNotFound, ExpectedContent: []string{`"status":404`},
			TestAppFactory: factory, DisableTestAppCleanup: true,
		},
		{
			Name: "verification flow is off", Method: http.MethodPost, URL: "/api/collections/users/request-verification",
			Body:           strings.NewReader(`{"email":"guard@test.local"}`),
			ExpectedStatus: http.StatusNotFound, ExpectedContent: []string{`"status":404`},
			TestAppFactory: factory, DisableTestAppCleanup: true,
		},
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}

func TestSignupAndResetLimits(t *testing.T) {
	freshLimiters(t)
	Signups = ratelimit.New(1, time.Minute)
	Resets = ratelimit.New(1, time.Minute)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	Register(app)
	factory := func(testing.TB) *tests.TestApp { return app }
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	c := core.NewRecord(users)
	c.SetEmail("c@test.local")
	c.SetPassword("password123")
	require.NoError(t, app.Save(c))

	scenarios := []tests.ApiScenario{
		{Name: "signup 1", Method: http.MethodPost, URL: "/api/collections/users/records",
			Body:           strings.NewReader(`{"email":"a@test.local","password":"password123","passwordConfirm":"password123"}`),
			ExpectedStatus: http.StatusOK, ExpectedContent: []string{`"collectionName":"users"`},
			TestAppFactory: factory, DisableTestAppCleanup: true},
		{Name: "signup 2 limited", Method: http.MethodPost, URL: "/api/collections/users/records",
			Body:           strings.NewReader(`{"email":"b@test.local","password":"password123","passwordConfirm":"password123"}`),
			ExpectedStatus: http.StatusTooManyRequests, ExpectedContent: []string{`"status":429`},
			TestAppFactory: factory, DisableTestAppCleanup: true},
		{Name: "reset 1", Method: http.MethodPost, URL: "/api/collections/users/request-password-reset",
			Body:           strings.NewReader(`{"email":"a@test.local"}`),
			ExpectedStatus: http.StatusNoContent, ExpectedContent: nil,
			TestAppFactory: factory, DisableTestAppCleanup: true},
		{Name: "reset 2 limited", Method: http.MethodPost, URL: "/api/collections/users/request-password-reset",
			Body:           strings.NewReader(`{"email":"c@test.local"}`),
			ExpectedStatus: http.StatusTooManyRequests, ExpectedContent: []string{`"status":429`},
			TestAppFactory: factory, DisableTestAppCleanup: true},
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}
