package migrations_test

import (
	"testing"

	"ganigeorgiev.com/pocketbase/dbx"
	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"
)

const (
	vc2Migration    = "1700000013_reencrypt_legacy_vc2.go"
	dedupeMigration = "1700000014_rekey_transit_dedupe.go"
)

func guardApp(t *testing.T) (*tests.TestApp, *core.Record) {
	t.Helper()
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	t.Cleanup(app.Cleanup)
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.SetEmail("guard@example.com")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))
	return app, u
}

// A fresh database, and one that already holds vc3 values and dk2 keys, pass.
func TestLegacyGuardsAcceptCurrentData(t *testing.T) {
	app, u := guardApp(t)
	insertRaw(t, app, "transit_trackers", dbx.Params{"id": "current00000001", "user": u.Id, "dedupe_key": "dk2:abc", "stops": `"vc3:xyz"`})
	insertRaw(t, app, "schedule_items", dbx.Params{"id": "current00000002", "user": u.Id, "client_id": "c1", "payload": "vc3:abc"})

	require.NoError(t, findMigration(t, vc2Migration).Up(app))
	require.NoError(t, findMigration(t, dedupeMigration).Up(app))
}

func TestVC2GuardFailsOnAStoredVC2Value(t *testing.T) {
	for name, tc := range map[string]struct{ table, col, val string }{
		"text field":   {"schedule_items", "payload", "vc2:AAAA"},
		"json string":  {"transit_trackers", "stops", `"vc2:AAAA"`},
		"users column": {"users", "neptun_ics_url", "vc2:AAAA"},
	} {
		t.Run(name, func(t *testing.T) {
			app, u := guardApp(t)
			switch tc.table {
			case "schedule_items":
				insertRaw(t, app, "schedule_items", dbx.Params{"id": "legacy000000001", "user": u.Id, "client_id": "c1", tc.col: tc.val})
			case "transit_trackers":
				insertRaw(t, app, "transit_trackers", dbx.Params{"id": "legacy000000001", "user": u.Id, "dedupe_key": "dk2:abc", tc.col: tc.val})
			case "users":
				_, err := app.DB().Update("users", dbx.Params{tc.col: tc.val}, dbx.HashExp{"id": u.Id}).Execute()
				require.NoError(t, err)
			}
			err := findMigration(t, vc2Migration).Up(app)
			require.Error(t, err)
			require.Contains(t, err.Error(), tc.table+"."+tc.col+" (1)")
			require.Contains(t, err.Error(), "before 2026-11-10")
		})
	}
}

func TestDedupeGuardFailsOnAnOldKey(t *testing.T) {
	app, u := guardApp(t)
	insertRaw(t, app, "transit_trackers", dbx.Params{"id": "oldkey000000001", "user": u.Id, "dedupe_key": "9f86d081884c7d659a2feaa0c55ad015"})

	err := findMigration(t, dedupeMigration).Up(app)
	require.Error(t, err)
	require.Contains(t, err.Error(), "1 transit_trackers row(s)")
}
