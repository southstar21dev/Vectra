package schema_test

import (
	"testing"
	"time"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/schema"

	_ "vectra/pb/migrations"
)

// newUser creates and saves a user record so relation fields can be satisfied.
func newUser(t *testing.T, app *tests.TestApp, email string) *core.Record {
	t.Helper()

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)

	user := core.NewRecord(users)
	user.SetEmail(email)
	user.SetPassword("password123")
	require.NoError(t, app.Save(user))
	return user
}

// TestRegisterTimestampsCreateAndUpdate drives the same OnRecordCreateRequest /
// OnRecordUpdateRequest hooks that the record CRUD API handlers trigger. The
// one-off handler mirrors the API handler's save step so the persisted values
// are checked, not just the in-memory record.
func TestRegisterTimestampsCreateAndUpdate(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	schema.RegisterTimestamps(app)

	user := newUser(t, app, "timestamps@example.com")

	col, err := app.FindCollectionByNameOrId("schedule_events")
	require.NoError(t, err)

	rec := core.NewRecord(col)
	rec.Set("user", user.Id)
	rec.Set("course_name", "Hooks 101")

	require.NoError(t, app.OnRecordCreateRequest().Trigger(
		&core.RecordRequestEvent{Record: rec},
		func(e *core.RecordRequestEvent) error { return app.Save(e.Record) },
	))

	createdAt := rec.GetString("created_at")
	updatedAt := rec.GetString("updated_at")
	require.NotEmpty(t, createdAt, "created_at must be set on create")
	require.NotEmpty(t, updatedAt, "updated_at must be set on create")

	// Prove the values actually persisted to the database.
	reloaded, err := app.FindRecordById(col.Id, rec.Id)
	require.NoError(t, err)
	require.Equal(t, createdAt, reloaded.GetString("created_at"))
	require.Equal(t, updatedAt, reloaded.GetString("updated_at"))

	// DateTime storage has sub-second precision; wait so the update is strictly
	// later and the advance is observable.
	time.Sleep(1100 * time.Millisecond)

	require.NoError(t, app.OnRecordUpdateRequest().Trigger(
		&core.RecordRequestEvent{Record: reloaded},
		func(e *core.RecordRequestEvent) error { return app.Save(e.Record) },
	))

	require.Greater(t, reloaded.GetString("updated_at"), updatedAt, "updated_at must advance on update")
	require.Equal(t, createdAt, reloaded.GetString("created_at"), "created_at must not change on update")
}

// TestRegisterTimestampsSkipsCollectionsWithoutFields proves the field guard:
// passkey_challenges has neither created_at nor updated_at, so the hooks must
// leave the record untouched and the save must not fail.
func TestRegisterTimestampsSkipsCollectionsWithoutFields(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	schema.RegisterTimestamps(app)

	user := newUser(t, app, "passkey-hooks@example.com")

	ch, err := app.FindCollectionByNameOrId("passkey_challenges")
	require.NoError(t, err)
	require.Nil(t, ch.Fields.GetByName("created_at"))
	require.Nil(t, ch.Fields.GetByName("updated_at"))

	rec := core.NewRecord(ch)
	rec.Set("challenge_type", "registration")
	rec.Set("user", user.Id)
	rec.Set("session_data", map[string]any{"challenge": "c"})
	rec.Set("expires_at", time.Now().Add(time.Minute))

	require.NoError(t, app.OnRecordCreateRequest().Trigger(
		&core.RecordRequestEvent{Record: rec},
		func(e *core.RecordRequestEvent) error { return app.Save(e.Record) },
	))

	require.Empty(t, rec.GetString("created_at"), "passkey_challenges must not receive created_at")
	require.Empty(t, rec.GetString("updated_at"), "passkey_challenges must not receive updated_at")
}

// TestRegisterTimestampsIgnoresClientCreatedAt: created_at is server-owned, so
// a value submitted with the create request is overwritten.
func TestRegisterTimestampsIgnoresClientCreatedAt(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	schema.RegisterTimestamps(app)
	user := newUser(t, app, "forged-created@example.com")

	col, err := app.FindCollectionByNameOrId("schedule_events")
	require.NoError(t, err)
	rec := core.NewRecord(col)
	rec.Set("user", user.Id)
	rec.Set("created_at", "2000-01-01 00:00:00.000Z")

	before := time.Now().UTC().Add(-time.Minute)
	require.NoError(t, app.OnRecordCreateRequest().Trigger(
		&core.RecordRequestEvent{Record: rec},
		func(e *core.RecordRequestEvent) error { return app.Save(e.Record) },
	))
	require.True(t, rec.GetDateTime("created_at").Time().After(before), "client created_at must be replaced by the server time")
}
