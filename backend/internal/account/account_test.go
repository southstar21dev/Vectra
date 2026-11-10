package account

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/ratelimit"
	"vectra/pb/internal/reauth"
	_ "vectra/pb/migrations"
)

// useLimiters swaps in fresh re-auth limiters allowing perUser checks per user.
func useLimiters(t *testing.T, perUser int) {
	t.Helper()
	prevUser, prevIP := reauth.UserLimiter, reauth.IPLimiter
	reauth.UserLimiter = ratelimit.New(perUser, time.Minute)
	reauth.IPLimiter = ratelimit.New(100, time.Minute)
	t.Cleanup(func() { reauth.UserLimiter, reauth.IPLimiter = prevUser, prevIP })
}

func postAs(app *tests.TestApp, auth *core.Record, target, body string, handler func(*core.RequestEvent) error) (*httptest.ResponseRecorder, error) {
	req := httptest.NewRequest(http.MethodPost, target, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	e := &core.RequestEvent{App: app, Auth: auth}
	e.Request = req
	e.Response = w
	return w, handler(e)
}

func TestDeleteAccountCascades(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, _ := app.FindCollectionByNameOrId("users")
	u := core.NewRecord(users)
	u.Set("email", "del@test.local")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))

	plans, _ := app.FindCollectionByNameOrId("trip_plans")
	p := core.NewRecord(plans)
	p.Set("user", u.Id)
	p.Set("name", "t")
	p.Set("legs", []any{})
	require.NoError(t, app.Save(p))

	require.NoError(t, deleteAccount(app, u.Id))
	_, err = app.FindRecordById("trip_plans", p.Id)
	require.Error(t, err)
	_, err = app.FindRecordById("users", u.Id)
	require.Error(t, err)
}

func TestChangeEmail(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, _ := app.FindCollectionByNameOrId("users")
	u := core.NewRecord(users)
	u.Set("email", "old@test.local")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))
	oldTokenKey := u.TokenKey()

	// Wrong password is rejected and the email is left untouched.
	err = ChangeEmail(app, u.Id, "wrong-password", "new@test.local")
	require.ErrorIs(t, err, errInvalidPassword)
	unchanged, err := app.FindRecordById("users", u.Id)
	require.NoError(t, err)
	require.Equal(t, "old@test.local", unchanged.Email())

	// Correct password switches instantly; the new address is unproven, so it
	// is stored unverified even though the old one was verified.
	unchanged.SetVerified(true)
	require.NoError(t, app.Save(unchanged))
	oldTokenKey = unchanged.TokenKey()
	require.NoError(t, ChangeEmail(app, u.Id, "password123", "new@test.local"))
	updated, err := app.FindRecordById("users", u.Id)
	require.NoError(t, err)
	require.Equal(t, "new@test.local", updated.Email())
	require.False(t, updated.Verified(), "a switched address must not be marked verified")
	// PocketBase regenerates the auth token key on an email change, which
	// invalidates the caller's current token — the client re-authenticates.
	require.NotEqual(t, oldTokenKey, updated.TokenKey())
}

func TestChangeEmailSameAddressKeepsVerified(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, _ := app.FindCollectionByNameOrId("users")
	u := core.NewRecord(users)
	u.Set("email", "same@test.local")
	u.SetPassword("password123")
	u.SetVerified(true)
	require.NoError(t, app.Save(u))

	require.NoError(t, ChangeEmail(app, u.Id, "password123", "Same@test.local"))
	got, err := app.FindRecordById("users", u.Id)
	require.NoError(t, err)
	require.True(t, got.Verified(), "a no-op change keeps the verified state")
	require.Equal(t, "same@test.local", got.Email())
}

func TestEmailHandlerRateLimited(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	useLimiters(t, 2)

	users, _ := app.FindCollectionByNameOrId("users")
	u := core.NewRecord(users)
	u.Set("email", "limited@test.local")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))

	call := func() error {
		req := httptest.NewRequest(http.MethodPost, "/api/vectra/account/email",
			strings.NewReader(`{"currentPassword":"wrong","newEmail":"x@test.local"}`))
		req.Header.Set("Content-Type", "application/json")
		e := &core.RequestEvent{App: app, Auth: u}
		e.Request = req
		e.Response = httptest.NewRecorder()
		return EmailHandler(e)
	}
	for i := 0; i < 2; i++ {
		apiErr, ok := call().(*router.ApiError)
		require.True(t, ok)
		require.Equal(t, http.StatusBadRequest, apiErr.Status, "wrong password, not yet limited")
	}
	apiErr, ok := call().(*router.ApiError)
	require.True(t, ok)
	require.Equal(t, http.StatusTooManyRequests, apiErr.Status)
}

// TestDeleteHandlerRequiresCurrentPassword: a bearer token alone cannot delete
// the account; a missing or wrong password is a 400 and deletes nothing, the
// right one deletes the account.
func TestDeleteHandlerRequiresCurrentPassword(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	useLimiters(t, 10)

	users, _ := app.FindCollectionByNameOrId("users")
	u := core.NewRecord(users)
	u.Set("email", "keep@test.local")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))

	for _, body := range []string{"", `{}`, `{"currentPassword":"wrong"}`} {
		_, err := postAs(app, u, "/api/vectra/account/delete", body, Handler)
		apiErr, ok := err.(*router.ApiError)
		require.True(t, ok, "body %q", body)
		require.Equal(t, http.StatusBadRequest, apiErr.Status, "body %q", body)
		_, err = app.FindRecordById("users", u.Id)
		require.NoError(t, err, "a rejected delete must leave the account in place")
	}

	w, err := postAs(app, u, "/api/vectra/account/delete", `{"currentPassword":"password123"}`, Handler)
	require.NoError(t, err)
	require.Equal(t, http.StatusOK, w.Code)
	_, err = app.FindRecordById("users", u.Id)
	require.Error(t, err)
}

// TestReauthBucketIsShared: wrong guesses on one re-auth route spend the same
// per-user budget as another, so spreading guesses buys nothing.
func TestReauthBucketIsShared(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	useLimiters(t, 2)

	users, _ := app.FindCollectionByNameOrId("users")
	u := core.NewRecord(users)
	u.Set("email", "shared@test.local")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))

	_, _ = postAs(app, u, "/api/vectra/account/email", `{"currentPassword":"wrong","newEmail":"x@test.local"}`, EmailHandler)
	_, _ = postAs(app, u, "/api/vectra/account/delete", `{"currentPassword":"wrong"}`, Handler)
	_, err = postAs(app, u, "/api/vectra/account/delete", `{"currentPassword":"password123"}`, Handler)
	apiErr, ok := err.(*router.ApiError)
	require.True(t, ok)
	require.Equal(t, http.StatusTooManyRequests, apiErr.Status)
	_, err = app.FindRecordById("users", u.Id)
	require.NoError(t, err)
}
