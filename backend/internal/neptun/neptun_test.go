package neptun

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/ratelimit"
	_ "vectra/pb/migrations"
)

func newUser(t *testing.T, app core.App, email string) *core.Record {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.Set("email", email)
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))
	return u
}

func rowsFor(t *testing.T, app core.App, userID string) []*core.Record {
	t.Helper()
	rows, err := app.FindRecordsByFilter(
		"neptun_schedule_events", "user = {:u}", "", 0, 0, dbx.Params{"u": userID},
	)
	require.NoError(t, err)
	return rows
}

func TestReplaceNeptunSchedule(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	u := newUser(t, app, "n@test.local")

	events := []map[string]any{
		{"course_name": "Math", "day_of_week": "0", "start_time": "08:00", "end_time": "09:00"},
		{"course_name": "Physics", "day_of_week": "1", "start_time": "10:00", "end_time": "11:00"},
	}
	require.NoError(t, Replace(app, u.Id, events))
	require.Len(t, rowsFor(t, app, u.Id), 2)

	// Replacing with one event removes the previous set.
	require.NoError(t, Replace(app, u.Id, events[:1]))
	require.Len(t, rowsFor(t, app, u.Id), 1)
}

// TestReplaceDefaultsAndVerbatimFields pins the semantics ported from the
// replace_neptun_schedule RPC: content fields are stored verbatim (ciphertext),
// including the encrypted semester bounds, and color/event_type fall back to
// their COALESCE defaults.
func TestReplaceDefaultsAndVerbatimFields(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	u := newUser(t, app, "n2@test.local")

	events := []map[string]any{
		{
			"course_name":    "vc2:cipher",
			"day_of_week":    "vc2:day",
			"start_time":     "vc2:start",
			"end_time":       "vc2:end",
			"semester":       "vc2:semester",
			"semester_start": "vc2:sem-start",
			"semester_end":   "vc2:sem-end",
			"event_type":     "vc2:type",
		},
	}
	require.NoError(t, Replace(app, u.Id, events))

	rows := rowsFor(t, app, u.Id)
	require.Len(t, rows, 1)

	r := rows[0]
	require.Equal(t, "vc2:cipher", r.GetString("course_name"))
	require.Equal(t, "vc2:day", r.GetString("day_of_week"))
	require.Equal(t, "vc2:start", r.GetString("start_time"))
	require.Equal(t, "vc2:end", r.GetString("end_time"))
	require.Equal(t, "vc2:semester", r.GetString("semester"))
	// The Critical regression: encrypted semester bounds must round-trip
	// verbatim instead of being silently discarded by a DateField.
	require.Equal(t, "vc2:sem-start", r.GetString("semester_start"))
	require.Equal(t, "vc2:sem-end", r.GetString("semester_end"))
	require.Equal(t, "vc2:type", r.GetString("event_type"))
	require.Equal(t, "#3B82F6", r.GetString("color"))
	require.False(t, r.GetDateTime("created_at").IsZero())
}

// TestReplaceOmitsAbsentSemesterBounds confirms that events without encrypted
// semester bounds leave those columns empty rather than storing a stray value.
func TestReplaceOmitsAbsentSemesterBounds(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	u := newUser(t, app, "n3@test.local")

	require.NoError(t, Replace(app, u.Id, []map[string]any{
		{"course_name": "Math", "day_of_week": "0", "start_time": "08:00", "end_time": "09:00"},
	}))

	rows := rowsFor(t, app, u.Id)
	require.Len(t, rows, 1)
	require.Equal(t, "", rows[0].GetString("semester_start"))
	require.Equal(t, "", rows[0].GetString("semester_end"))
	require.Equal(t, "#3B82F6", rows[0].GetString("color"))
	require.Equal(t, "class", rows[0].GetString("event_type"))
}

// TestHandlerIgnoresClientSuppliedOwner proves the route derives the owner
// solely from e.Auth.Id: a body carrying user_id/p_user_id for another user is
// ignored and the rows land on the authenticated caller.
func TestHandlerIgnoresClientSuppliedOwner(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	caller := newUser(t, app, "caller@test.local")
	victim := newUser(t, app, "victim@test.local")

	body := `{"user_id":"` + victim.Id + `","p_user_id":"` + victim.Id + `","events":[{"course_name":"Mine","day_of_week":"0","start_time":"08:00","end_time":"09:00"}]}`
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/neptun/replace", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	e := &core.RequestEvent{App: app, Auth: caller}
	e.Request = req
	e.Response = httptest.NewRecorder()

	require.NoError(t, Handler(e))

	require.Len(t, rowsFor(t, app, caller.Id), 1)
	require.Empty(t, rowsFor(t, app, victim.Id))
	require.Equal(t, "Mine", rowsFor(t, app, caller.Id)[0].GetString("course_name"))
}

// TestReplaceRollsBackOnInsertFailure forces insertEvents to fail after
// deleteForUser has already run, and asserts the transaction rolls back so the
// prior rows survive. No production seam is needed: a course_name longer than
// the text field's 5000-char limit fails PocketBase validation during Save.
func TestReplaceRollsBackOnInsertFailure(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	u := newUser(t, app, "rollback@test.local")

	// Seed the user's calendar.
	require.NoError(t, Replace(app, u.Id, []map[string]any{
		{"course_name": "keep", "day_of_week": "0", "start_time": "08:00", "end_time": "09:00"},
	}))
	require.Len(t, rowsFor(t, app, u.Id), 1)

	// The second event's course_name exceeds the text field's max length, which
	// the Save rejects — after the delete has already executed inside the tx.
	err = Replace(app, u.Id, []map[string]any{
		{"course_name": "new", "day_of_week": "0", "start_time": "08:00", "end_time": "09:00"},
		{"course_name": strings.Repeat("x", 6000)},
	})
	require.Error(t, err)

	rows := rowsFor(t, app, u.Id)
	require.Len(t, rows, 1, "delete must have been rolled back")
	require.Equal(t, "keep", rows[0].GetString("course_name"))
}

func neptunRequest(app core.App, auth *core.Record, body string) (*core.RequestEvent, *httptest.ResponseRecorder) {
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/neptun/replace", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{App: app, Auth: auth}
	e.Request = req
	e.Response = rec
	return e, rec
}

// TestHandlerRejectsOversizedSync: more than maxEvents events is a 400 and
// leaves the stored calendar untouched.
func TestHandlerRejectsOversizedSync(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	u := newUser(t, app, "big@test.local")
	require.NoError(t, Replace(app, u.Id, []map[string]any{{"course_name": "keep"}}))

	events := make([]string, maxEvents+1)
	for i := range events {
		events[i] = `{"course_name":"x"}`
	}
	e, _ := neptunRequest(app, u, `{"events":[`+strings.Join(events, ",")+`]}`)
	err = Handler(e)
	var apiErr *router.ApiError
	require.ErrorAs(t, err, &apiErr)
	require.Equal(t, http.StatusBadRequest, apiErr.Status)
	require.Len(t, rowsFor(t, app, u.Id), 1, "an oversized sync must not touch stored rows")
}

func TestHandlerRateLimitedPerUser(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	prev := limiter
	limiter = ratelimit.New(1, time.Minute)
	t.Cleanup(func() { limiter = prev })

	a := newUser(t, app, "rl-a@test.local")
	b := newUser(t, app, "rl-b@test.local")

	e, _ := neptunRequest(app, a, `{"events":[]}`)
	require.NoError(t, Handler(e))

	e, _ = neptunRequest(app, a, `{"events":[]}`)
	err = Handler(e)
	var apiErr *router.ApiError
	require.ErrorAs(t, err, &apiErr)
	require.Equal(t, http.StatusTooManyRequests, apiErr.Status)

	e, _ = neptunRequest(app, b, `{"events":[]}`)
	require.NoError(t, Handler(e), "the limit is per user")
}
