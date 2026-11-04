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
	_ "vectra/pb/migrations"
)

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

	prevUser, prevIP := emailUserLimiter, emailIPLimiter
	emailUserLimiter = ratelimit.New(2, time.Minute)
	emailIPLimiter = ratelimit.New(100, time.Minute)
	t.Cleanup(func() { emailUserLimiter, emailIPLimiter = prevUser, prevIP })

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
