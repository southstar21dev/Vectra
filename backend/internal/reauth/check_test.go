package reauth

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"
	"ganigeorgiev.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/ratelimit"
)

// swapLimiters is reauthtest.Use, inlined because reauthtest imports this
// package and these tests are internal to it (an import cycle otherwise).
func swapLimiters(t testing.TB, perUser, perIP int) {
	t.Helper()
	prevUser, prevIP := UserLimiter, IPLimiter
	UserLimiter = ratelimit.New(perUser, time.Minute)
	IPLimiter = ratelimit.New(perIP, time.Minute)
	t.Cleanup(func() { UserLimiter, IPLimiter = prevUser, prevIP })
}

func newUser(t *testing.T, app *tests.TestApp, email string) *core.Record {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.SetEmail(email)
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))
	return u
}

// checkAs runs Check for auth as a caller at the given socket address.
func checkAs(app core.App, auth *core.Record, remoteAddr, password string) error {
	req := httptest.NewRequest(http.MethodPost, "/x", nil)
	req.RemoteAddr = remoteAddr
	e := &core.RequestEvent{App: app, Auth: auth}
	e.Request = req
	e.Response = httptest.NewRecorder()
	return Check(e, password)
}

func statusOf(t *testing.T, err error) int {
	t.Helper()
	var apiErr *router.ApiError
	require.ErrorAs(t, err, &apiErr)
	return apiErr.Status
}

func TestCheckNoSessionIs401(t *testing.T) {
	swapLimiters(t, 5, 20)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	require.Equal(t, http.StatusUnauthorized, statusOf(t, checkAs(app, nil, "192.0.2.1:1", "x")))
	blank := core.NewRecord(core.NewBaseCollection("users"))
	require.Equal(t, http.StatusUnauthorized, statusOf(t, checkAs(app, blank, "192.0.2.1:1", "x")),
		"a record without an id is not a session")
}

func TestCheckEmptyPasswordIsRejectedAndCounted(t *testing.T) {
	swapLimiters(t, 2, 100)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	u := newUser(t, app, "empty@test.local")

	require.Equal(t, http.StatusBadRequest, statusOf(t, checkAs(app, u, "192.0.2.1:1", "")))
	require.Equal(t, http.StatusBadRequest, statusOf(t, checkAs(app, u, "192.0.2.1:1", "")))
	require.Equal(t, http.StatusTooManyRequests, statusOf(t, checkAs(app, u, "192.0.2.1:1", "password123")),
		"empty passwords spend the user budget like any wrong guess")
}

func TestCheckSuccessDoesNotSpendBudget(t *testing.T) {
	swapLimiters(t, 1, 1)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	u := newUser(t, app, "ok@test.local")

	for i := 0; i < 5; i++ {
		require.NoError(t, checkAs(app, u, "192.0.2.1:1", "password123"))
	}
}

// TestCheckIPBucket: several users guessing from one address spend the IP
// bucket although no single user budget is spent; another address is unaffected.
func TestCheckIPBucket(t *testing.T) {
	swapLimiters(t, 5, 3)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	for i := 0; i < 3; i++ {
		u := newUser(t, app, "ip"+string(rune('a'+i))+"@test.local")
		require.Equal(t, http.StatusBadRequest, statusOf(t, checkAs(app, u, "192.0.2.7:1", "wrong")))
	}
	fresh := newUser(t, app, "ipfresh@test.local")
	require.Equal(t, http.StatusTooManyRequests, statusOf(t, checkAs(app, fresh, "192.0.2.7:1", "password123")),
		"the IP bucket is spent, so even a correct password is refused")
	require.NoError(t, checkAs(app, fresh, "192.0.2.8:1", "password123"), "another IP is unaffected")
}

// TestCheckUsesStoredPassword: the record is reloaded, so a password changed
// after the token was issued is the one checked, not the stale session copy.
func TestCheckUsesStoredPassword(t *testing.T) {
	swapLimiters(t, 5, 20)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	u := newUser(t, app, "changed@test.local")
	stale, err := app.FindRecordById("users", u.Id)
	require.NoError(t, err)

	u.SetPassword("brandnewpass1")
	require.NoError(t, app.Save(u))

	require.Equal(t, http.StatusBadRequest, statusOf(t, checkAs(app, stale, "192.0.2.1:1", "password123")),
		"the old password no longer works")
	require.NoError(t, checkAs(app, stale, "192.0.2.1:1", "brandnewpass1"))
}

func TestCheckDeletedUserIs401(t *testing.T) {
	swapLimiters(t, 5, 20)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	u := newUser(t, app, "gone@test.local")
	require.NoError(t, app.Delete(u))

	require.Equal(t, http.StatusUnauthorized, statusOf(t, checkAs(app, u, "192.0.2.1:1", "password123")))
}
