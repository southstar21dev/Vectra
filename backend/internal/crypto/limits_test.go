package crypto

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"
	"ganigeorgiev.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/limits"
	"vectra/pb/internal/ratelimit"
)

// callStatus runs the handler and returns the HTTP status it produced, either
// written (200) or as a returned API error.
func callStatus(t *testing.T, l *ratelimit.Limiter, app *tests.TestApp, auth *core.Record, body any) int {
	t.Helper()
	raw, err := json.Marshal(body)
	require.NoError(t, err)
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/crypto", strings.NewReader(string(raw)))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{App: app, Auth: auth}
	e.Request = req
	e.Response = rec
	if err := Handler(l)(e); err != nil {
		var apiErr *router.ApiError
		require.True(t, errors.As(err, &apiErr), "unexpected error %v", err)
		return apiErr.Status
	}
	return rec.Code
}

func limitsApp(t *testing.T) (*tests.TestApp, *core.Record, *core.Record) {
	t.Helper()
	resetKey(t, "limits-key")
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	t.Cleanup(app.Cleanup)
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	newUser := func(email string) *core.Record {
		u := core.NewRecord(users)
		u.SetEmail(email)
		u.SetPassword("password123")
		require.NoError(t, app.Save(u))
		return u
	}
	return app, newUser("limits-a@example.com"), newUser("limits-b@example.com")
}

func repeat(v string, n int) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = v
	}
	return out
}

// TestBatchCaps: a request over the per-request caps is refused with 413
// before any work is done; a request at the caps still succeeds.
func TestBatchCaps(t *testing.T) {
	app, alice, _ := limitsApp(t)
	l := ratelimit.New(limits.CryptoValues.Max, limits.CryptoValues.Window)

	require.Equal(t, http.StatusOK, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": repeat("x", MaxValues)}))
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": repeat("x", MaxValues+1)}))
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "encrypt", "values": repeat("x", MaxValues+1)}))

	parts := make([][]string, MaxPartLists+1)
	for i := range parts {
		parts[i] = []string{"r", "s", ""}
	}
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "digest", "parts": parts}))
	require.Equal(t, http.StatusOK, callStatus(t, l, app, alice, map[string]any{"op": "digest", "parts": parts[:MaxPartLists]}))
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "digest", "parts": [][]string{repeat("p", MaxPartsPerList+1)}}))

	big := strings.Repeat("a", MaxBatchBytes/2+1)
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": []string{big, big}}))
}

// TestUserBudget: each user has a budget of values per window; spending it
// gives 429 for that user only, and it refills after the window.
func TestUserBudget(t *testing.T) {
	app, alice, bob := limitsApp(t)
	l := ratelimit.New(5, time.Minute)

	three := map[string]any{"op": "decrypt", "values": []string{"a", "b", "c"}}
	require.Equal(t, http.StatusOK, callStatus(t, l, app, alice, three))
	require.Equal(t, http.StatusTooManyRequests, callStatus(t, l, app, alice, three), "6 values exceed a budget of 5")
	require.Equal(t, http.StatusOK, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": []string{"a", "b"}}))
	require.Equal(t, http.StatusTooManyRequests, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": []string{}}),
		"an empty request still costs one unit")
	require.Equal(t, http.StatusOK, callStatus(t, l, app, bob, three), "the budget is per user")

	// The budget refills once the window has passed.
	require.True(t, l.AllowN(alice.Id, 5, time.Now().Add(2*time.Minute)))
}

// TestValueByteCap: one value (or digest part) over MaxValueBytes is refused
// with 413 even though the request is far below the total byte cap; one
// exactly at it is accepted.
func TestValueByteCap(t *testing.T) {
	app, alice, _ := limitsApp(t)
	l := ratelimit.New(limits.CryptoValues.Max, limits.CryptoValues.Window)

	require.Equal(t, http.StatusOK, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": []string{strings.Repeat("a", MaxValueBytes)}}))
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": []string{strings.Repeat("a", MaxValueBytes+1)}}))
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "encrypt", "values": []string{strings.Repeat("a", MaxValueBytes+1)}}))
	require.Equal(t, http.StatusRequestEntityTooLarge, callStatus(t, l, app, alice, map[string]any{"op": "digest", "parts": [][]string{{"r", strings.Repeat("a", MaxValueBytes+1)}}}))
}

// TestBatchCostCountsBytes: values up to CostUnitBytes cost one unit each as
// before; larger ones cost one unit per CostUnitBytes started, so bytes use up
// the budget and not only the number of values.
func TestBatchCostCountsBytes(t *testing.T) {
	cost := func(body request) int {
		c, reason := batchCost(body)
		require.Empty(t, reason)
		return c
	}
	require.Equal(t, 1, cost(request{}), "an empty request costs one unit")
	require.Equal(t, 3, cost(request{Values: []string{"a", "", strings.Repeat("b", CostUnitBytes)}}))
	require.Equal(t, 2, cost(request{Values: []string{strings.Repeat("b", CostUnitBytes+1)}}))
	require.Equal(t, 256, cost(request{Values: []string{strings.Repeat("b", MaxValueBytes)}}))
	require.Equal(t, 3, cost(request{Parts: [][]string{{"r", "s"}, {strings.Repeat("p", CostUnitBytes+1)}}}))

	// Through the handler: a budget of 100 units refuses a 1 MiB value
	// (256 units) that the old per-value count would have let through.
	app, alice, _ := limitsApp(t)
	l := ratelimit.New(100, time.Minute)
	require.Equal(t, http.StatusTooManyRequests, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": []string{strings.Repeat("a", MaxValueBytes)}}))
	require.Equal(t, http.StatusOK, callStatus(t, l, app, alice, map[string]any{"op": "decrypt", "values": []string{strings.Repeat("a", 50*CostUnitBytes)}}))
}
