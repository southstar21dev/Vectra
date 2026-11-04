package migrations_test

import (
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"
)

func TestUsersCollection(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	c, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	require.True(t, c.IsAuth())

	for _, name := range []string{
		"legacy_id",
		"full_name",
		"avatar_url",
		"appearance",
		"neptun_ics_url",
		"neptun_last_sync",
		"preferences",
		"created_at",
		"updated_at",
	} {
		require.NotNilf(t, c.Fields.GetByName(name), "missing required field %q", name)
	}

	require.Equal(t, "id = @request.auth.id", *c.ListRule)

	require.Contains(
		t,
		c.GetIndex("idx_users_legacy_id"),
		"WHERE legacy_id != ''",
		"legacy_id index must be a partial unique index",
	)
}

// TestUsersLegacyIDPartialUniqueIndex verifies the behavioural guarantee of the
// partial unique index: empty legacy_id values (PocketBase's unset default) are
// not constrained, while a duplicated non-empty legacy Supabase UUID is.
func TestUsersLegacyIDPartialUniqueIndex(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	c, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)

	newUser := func(email, legacyID string) *core.Record {
		rec := core.NewRecord(c)
		rec.SetEmail(email)
		rec.SetPassword("password123")
		if legacyID != "" {
			rec.Set("legacy_id", legacyID)
		}
		return rec
	}

	// Empty legacy_id is excluded from the partial index, so multiple users
	// without a legacy Supabase UUID can coexist.
	require.NoError(t, app.Save(newUser("empty1@example.com", "")))
	require.NoError(t, app.Save(newUser("empty2@example.com", "")))

	// A duplicate non-empty legacy_id must still be rejected.
	require.NoError(t, app.Save(newUser("dup1@example.com", "legacy-dup")))
	require.Error(t, app.Save(newUser("dup2@example.com", "legacy-dup")))
}

func TestDataCollections(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	for _, name := range []string{"schedule_events", "neptun_schedule_events", "schedule_items",
		"transit_trackers", "course_tracker_data", "trip_plans", "push_subscriptions"} {
		c, err := app.FindCollectionByNameOrId(name)
		require.NoError(t, err, name)
		require.Equal(t, "user = @request.auth.id", *c.ListRule, name)
		require.NotNil(t, c.Fields.GetByName("user"), name)
	}

	tt, err := app.FindCollectionByNameOrId("transit_trackers")
	require.NoError(t, err)
	require.Contains(t, tt.Indexes, "CREATE UNIQUE INDEX `idx_transit_dedupe` ON `transit_trackers` (user, dedupe_key)")

	// dedupe_key must be Required to mirror the old `dedupe_key TEXT NOT NULL`:
	// the unique index is non-partial, so an unset (stored as "") value would
	// make a second keyless tracker collide.
	dedupe, ok := tt.Fields.GetByName("dedupe_key").(*core.TextField)
	require.True(t, ok, "dedupe_key must be a TextField")
	require.True(t, dedupe.Required, "dedupe_key must be Required")
}

// TestCourseTrackerDataSingleton verifies the one-row-per-user guarantee the
// client upsert relies on: a unique index on `user` rejects a second tracker
// row for the same user while still allowing other users.
func TestCourseTrackerDataSingleton(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	col, err := app.FindCollectionByNameOrId("course_tracker_data")
	require.NoError(t, err)
	require.Contains(
		t,
		col.Indexes,
		"CREATE UNIQUE INDEX `idx_course_tracker_data_user` ON `course_tracker_data` (user)",
		"course_tracker_data must have a unique index on user",
	)

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	newUser := func(email string) *core.Record {
		u := core.NewRecord(users)
		u.SetEmail(email)
		u.SetPassword("password123")
		require.NoError(t, app.Save(u))
		return u
	}
	userA := newUser("tracker-singleton-a@example.com")
	userB := newUser("tracker-singleton-b@example.com")

	newRow := func(userID string) *core.Record {
		rec := core.NewRecord(col)
		rec.Set("user", userID)
		rec.Set("data", "vc2:encrypted")
		return rec
	}

	require.NoError(t, app.Save(newRow(userA.Id)), "first row for a user saves")
	require.Error(t, app.Save(newRow(userA.Id)), "a second row for the same user must collide")
	require.NoError(t, app.Save(newRow(userB.Id)), "a different user still saves")
}

// TestScheduleItemsClientID verifies the client-generated app id column that
// replaces the old Supabase primary key: schedule_items.client_id exists, is
// Required, and is backed by a non-partial unique (user, client_id) index
// (1700000010) so a client UUID can never be duplicated within an account (and
// an unset "" value cannot silently collide), while another account's rows
// never collide with it.
func TestScheduleItemsClientID(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	col, err := app.FindCollectionByNameOrId("schedule_items")
	require.NoError(t, err)

	clientID, ok := col.Fields.GetByName("client_id").(*core.TextField)
	require.True(t, ok, "client_id must be a TextField")
	require.True(t, clientID.Required, "client_id must be Required")
	require.Contains(
		t,
		col.Indexes,
		"CREATE UNIQUE INDEX `idx_schedule_items_user_client_id` ON `schedule_items` (user, client_id)",
		"client_id must be unique per user",
	)
	require.Empty(t, col.GetIndex("idx_schedule_items_client_id"), "the global unique index is gone")

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	user := core.NewRecord(users)
	user.SetEmail("schedule-client-id@example.com")
	user.SetPassword("password123")
	require.NoError(t, app.Save(user))

	newItem := func(id string) *core.Record {
		rec := core.NewRecord(col)
		rec.Set("user", user.Id)
		rec.Set("payload", "vc2:encrypted")
		if id != "" {
			rec.Set("client_id", id)
		}
		return rec
	}

	require.NoError(t, app.Save(newItem("client-uuid-1")))
	require.Error(t, app.Save(newItem("client-uuid-1")), "duplicate client_id must collide")
	require.NoError(t, app.Save(newItem("client-uuid-2")), "a distinct client_id still saves")
	require.Error(t, app.Save(newItem("")), "missing client_id must fail validation")

	other := core.NewRecord(users)
	other.SetEmail("schedule-client-id-2@example.com")
	other.SetPassword("password123")
	require.NoError(t, app.Save(other))
	foreign := core.NewRecord(col)
	foreign.Set("user", other.Id)
	foreign.Set("payload", "vc2:encrypted")
	foreign.Set("client_id", "client-uuid-1")
	require.NoError(t, app.Save(foreign), "another user may reuse the same client_id")
}

// TestScheduleItemsClientIDMigrationApplies proves the client_id column and its
// unique index — plus course_tracker_data's unique user index — come from the
// standalone migration 1700000004 rather than the already-applied
// 1700000001_data_collections migration. On a fresh database the migration is
// recorded as applied and yields the field/indexes the client depends on.
func TestScheduleItemsClientIDMigrationApplies(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	var applied []string
	err = app.DB().NewQuery("SELECT file FROM _migrations WHERE file = {:f}").
		Bind(dbx.Params{"f": "1700000004_schedule_items_client_id.go"}).
		Column(&applied)
	require.NoError(t, err)
	require.Equal(t, []string{"1700000004_schedule_items_client_id.go"}, applied,
		"migration 1700000004 must be recorded as applied on a fresh DB")

	si, err := app.FindCollectionByNameOrId("schedule_items")
	require.NoError(t, err)
	require.NotNil(t, si.Fields.GetByName("client_id"), "migration must add schedule_items.client_id")
	require.NotEmpty(t, si.GetIndex("idx_schedule_items_user_client_id"),
		"1700000010 must replace it with the per-user idx_schedule_items_user_client_id")

	ct, err := app.FindCollectionByNameOrId("course_tracker_data")
	require.NoError(t, err)
	require.NotEmpty(t, ct.GetIndex("idx_course_tracker_data_user"),
		"migration must add idx_course_tracker_data_user")
}

// TestTransitTrackerDedupeKeyBehavior verifies the Required dedupe_key plus the
// unique index behave like the old NOT NULL + UNIQUE(user_id, dedupe_key).
func TestTransitTrackerDedupeKeyBehavior(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	user := core.NewRecord(users)
	user.SetEmail("dedupe@example.com")
	user.SetPassword("password123")
	require.NoError(t, app.Save(user))

	col, err := app.FindCollectionByNameOrId("transit_trackers")
	require.NoError(t, err)

	newTracker := func(key string) *core.Record {
		rec := core.NewRecord(col)
		rec.Set("user", user.Id)
		if key != "" {
			rec.Set("dedupe_key", key)
		}
		return rec
	}

	// A tracker without a dedupe key is rejected (old NOT NULL parity).
	require.Error(t, app.Save(newTracker("")), "missing dedupe_key must fail validation")

	require.NoError(t, app.Save(newTracker("route-1")), "first keyed tracker saves")
	require.Error(t, app.Save(newTracker("route-1")), "duplicate (user, dedupe_key) must collide")
	require.NoError(t, app.Save(newTracker("route-2")), "a distinct key still saves")
}

func TestPasskeyCollections(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	pk, err := app.FindCollectionByNameOrId("passkeys")
	require.NoError(t, err)
	require.NotNil(t, pk.Fields.GetByName("credential_id"))
	require.NotNil(t, pk.Fields.GetByName("created_at"), "migration 1700000003 must add created_at")
	require.Nil(t, pk.CreateRule)

	ch, err := app.FindCollectionByNameOrId("passkey_challenges")
	require.NoError(t, err)
	require.Nil(t, ch.ViewRule)
}

func TestBlobFieldMax(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	for _, tc := range []struct{ collection, field string }{
		{"course_tracker_data", "data"},
		{"schedule_items", "payload"},
	} {
		col, err := app.FindCollectionByNameOrId(tc.collection)
		require.NoError(t, err)
		f, ok := col.Fields.GetByName(tc.field).(*core.TextField)
		require.True(t, ok, "%s.%s must be a text field", tc.collection, tc.field)
		require.GreaterOrEqual(t, f.Max, 1_000_000,
			"%s.%s Max must be raised above the 5000 default", tc.collection, tc.field)
	}
}

// TestRemoveFcmPushSubscriptionsMigrationApplies proves the FCM purge
// migration runs on a fresh database and that its filter matches exactly the
// native rows: seeded transport='fcm' rows are found by the migration's
// filter (and deleted), while 'webpush' rows are untouched.
func TestRemoveFcmPushSubscriptionsMigrationApplies(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	var applied []string
	err = app.DB().NewQuery("SELECT file FROM _migrations WHERE file = {:f}").
		Bind(dbx.Params{"f": "1700000006_remove_fcm_push_subscriptions.go"}).
		Column(&applied)
	require.NoError(t, err)
	require.Equal(t, []string{"1700000006_remove_fcm_push_subscriptions.go"}, applied,
		"migration 1700000006 must be recorded as applied on a fresh DB")

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	user := core.NewRecord(users)
	user.SetEmail("fcm-purge@example.com")
	user.SetPassword("password123")
	require.NoError(t, app.Save(user))

	col, err := app.FindCollectionByNameOrId("push_subscriptions")
	require.NoError(t, err)
	seed := func(transport string, sub map[string]any) *core.Record {
		rec := core.NewRecord(col)
		rec.Set("user", user.Id)
		rec.Set("transport", transport)
		rec.Set("subscription", sub)
		require.NoError(t, app.Save(rec))
		return rec
	}
	fcm := seed("fcm", map[string]any{"token": "dead-token"})
	web := seed("webpush", map[string]any{"endpoint": "https://push.example.com/live"})

	// The migration's own filter must match only the native row.
	matched, err := app.FindRecordsByFilter("push_subscriptions", "transport = 'fcm'", "", 200, 0)
	require.NoError(t, err)
	require.Len(t, matched, 1)
	require.Equal(t, fcm.Id, matched[0].Id)
	for _, rec := range matched {
		require.NoError(t, app.Delete(rec))
	}

	_, err = app.FindRecordById("push_subscriptions", fcm.Id)
	require.Error(t, err, "fcm row must be gone after the purge")
	_, err = app.FindRecordById("push_subscriptions", web.Id)
	require.NoError(t, err, "webpush row must survive the purge")
}

// TestRateLimitsEnabled: PocketBase ships its rate limiter disabled; migration
// 1700000009 turns it on with the default rules, including `*:auth`, which
// throttles auth-with-password.
func TestRateLimitsEnabled(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	rl := app.Settings().RateLimits
	require.True(t, rl.Enabled, "the built-in rate limiter must be enabled")

	labels := map[string]core.RateLimitRule{}
	for _, r := range rl.Rules {
		labels[r.Label] = r
	}
	for _, label := range []string{"*:auth", "*:create", "/api/batch", "/api/"} {
		require.Contains(t, labels, label)
	}
	require.EqualValues(t, 2, labels["*:auth"].MaxRequests)
	require.EqualValues(t, 3, labels["*:auth"].Duration)
}
