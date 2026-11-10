package migrations_test

import (
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"
)

const clientIDMigration = "1700000004_schedule_items_client_id.go"

func findMigration(t *testing.T, file string) *core.Migration {
	t.Helper()
	for _, mig := range core.AppMigrations.Items() {
		if mig.File == file {
			return mig
		}
	}
	t.Fatalf("migration %s is not registered", file)
	return nil
}

// oldDatabase turns a fresh test database back into the shape 1700000004
// meets on an old backup: no client_id or tracker indexes yet.
func oldDatabase(t *testing.T) (*tests.TestApp, *core.Record) {
	t.Helper()
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	t.Cleanup(app.Cleanup)

	si, err := app.FindCollectionByNameOrId("schedule_items")
	require.NoError(t, err)
	si.RemoveIndex("idx_schedule_items_user_client_id")
	si.RemoveIndex("idx_schedule_items_client_id")
	require.NoError(t, app.Save(si))
	ct, err := app.FindCollectionByNameOrId("course_tracker_data")
	require.NoError(t, err)
	ct.RemoveIndex("idx_course_tracker_data_user")
	require.NoError(t, app.Save(ct))

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.SetEmail("populated@example.com")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))
	return app, u
}

func insertRaw(t *testing.T, app core.App, table string, cols dbx.Params) {
	t.Helper()
	_, err := app.DB().Insert(table, cols).Execute()
	require.NoError(t, err)
}

// Rows that predate client_id all hold "": the migration backfills them with
// their record id before building the unique index, so it applies.
func TestClientIDMigrationOnPopulatedDatabase(t *testing.T) {
	app, u := oldDatabase(t)
	insertRaw(t, app, "schedule_items", dbx.Params{"id": "olditem0000001a", "user": u.Id, "client_id": "", "payload": "vc3:a"})
	insertRaw(t, app, "schedule_items", dbx.Params{"id": "olditem0000002b", "user": u.Id, "client_id": "", "payload": "vc3:b"})

	require.NoError(t, findMigration(t, clientIDMigration).Up(app))

	var ids []string
	require.NoError(t, app.DB().NewQuery("SELECT client_id FROM schedule_items ORDER BY id").Column(&ids))
	require.Equal(t, []string{"olditem0000001a", "olditem0000002b"}, ids)
	si, err := app.FindCollectionByNameOrId("schedule_items")
	require.NoError(t, err)
	require.NotEmpty(t, si.GetIndex("idx_schedule_items_client_id"))
}

// Two course_tracker_data rows for one user: the migration stops with an
// error that names the user, and deletes nothing.
func TestClientIDMigrationReportsDuplicateTrackerRows(t *testing.T) {
	app, u := oldDatabase(t)
	insertRaw(t, app, "course_tracker_data", dbx.Params{"id": "dupetracker0001", "user": u.Id, "data": "vc3:one"})
	insertRaw(t, app, "course_tracker_data", dbx.Params{"id": "dupetracker0002", "user": u.Id, "data": "vc3:two"})

	err := findMigration(t, clientIDMigration).Up(app)
	require.Error(t, err)
	require.Contains(t, err.Error(), "course_tracker_data has more than one row for 1 user(s) ("+u.Id+")")

	var n int
	require.NoError(t, app.DB().NewQuery("SELECT COUNT(*) FROM course_tracker_data").Row(&n))
	require.Equal(t, 2, n, "no row may be deleted")
}
