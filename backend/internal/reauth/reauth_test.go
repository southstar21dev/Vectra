package reauth

import (
	"net/http"
	"strings"
	"testing"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	_ "vectra/pb/migrations"
)

// TestPasswordChangeGuard drives the real records API: wrong oldPassword
// guesses spend the shared budget, then even the right one is refused (429),
// while updates that do not touch password fields are never throttled.
func TestPasswordChangeGuard(t *testing.T) {
	t.Parallel()
	g := newGuard(2, 100)

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

	RegisterPasswordChangeGuard(app, g)
	factory := func(testing.TB) *tests.TestApp { return app }
	auth := map[string]string{"Authorization": token}
	url := "/api/collections/users/records/" + u.Id

	wrong := `{"oldPassword":"nope","password":"newpassword1","passwordConfirm":"newpassword1"}`
	scenarios := []tests.ApiScenario{
		{Name: "wrong guess 1", Method: http.MethodPatch, URL: url, Headers: auth, Body: strings.NewReader(wrong),
			ExpectedStatus: http.StatusBadRequest, ExpectedContent: []string{`"status":400`}, TestAppFactory: factory, DisableTestAppCleanup: true},
		{Name: "wrong guess 2", Method: http.MethodPatch, URL: url, Headers: auth, Body: strings.NewReader(wrong),
			ExpectedStatus: http.StatusBadRequest, ExpectedContent: []string{`"status":400`}, TestAppFactory: factory, DisableTestAppCleanup: true},
		{Name: "budget spent", Method: http.MethodPatch, URL: url, Headers: auth,
			Body:           strings.NewReader(`{"oldPassword":"password123","password":"newpassword1","passwordConfirm":"newpassword1"}`),
			ExpectedStatus: http.StatusTooManyRequests, ExpectedContent: []string{`"status":429`}, TestAppFactory: factory, DisableTestAppCleanup: true},
		{Name: "non-password update is not throttled", Method: http.MethodPatch, URL: url, Headers: auth,
			Body:           strings.NewReader(`{"name":"x"}`),
			ExpectedStatus: http.StatusOK, ExpectedContent: []string{`"id":"` + u.Id + `"`}, TestAppFactory: factory, DisableTestAppCleanup: true},
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}
