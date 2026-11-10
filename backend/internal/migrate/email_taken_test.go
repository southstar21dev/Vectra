package migrate

import (
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"
)

func signUp(t *testing.T, app core.App, email string) *core.Record {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.SetEmail(email)
	u.SetPassword("native-password")
	u.Set("full_name", "Native")
	require.NoError(t, app.Save(u))
	return u
}

// TestImportSkipsUserWhoseEmailIsTaken: someone signed up in the app with an
// email that is also in Supabase. A (re-)run must not fail the whole users
// transaction on the unique email, and must not attach the Supabase user's
// data to that account; the user is skipped and reported.
func TestImportSkipsUserWhoseEmailIsTaken(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	native := signUp(t, app, "alice@example.com")

	rep, err := importAll(app, seedSource(), Options{})
	require.NoError(t, err)
	byTable := reportsByTable(rep)
	require.Equal(t, 0, byTable["users"].Imported)
	require.Equal(t, 1, byTable["users"].SkippedEmailTaken)
	require.Equal(t, 1, byTable["schedule_items"].SkippedUnknownUser, "the skipped user's data is not imported")
	require.Contains(t, rep.String(), "skipped_email_taken=1")

	got, err := app.FindRecordById("users", native.Id)
	require.NoError(t, err)
	require.Empty(t, got.GetString("legacy_id"), "the native account is not linked to the Supabase user")
	require.Equal(t, "Native", got.GetString("full_name"))
	require.True(t, got.ValidatePassword("native-password"))
	items, err := app.FindRecordsByFilter("schedule_items", "user = {:u}", "", 0, 0, dbx.Params{"u": native.Id})
	require.NoError(t, err)
	require.Empty(t, items, "no Supabase data may land in the native account")
}

// TestRefreshSkipsUserWhoseSourceEmailIsTaken: with Refresh, an imported user
// whose Supabase email now belongs to another account keeps its record as it
// is, and the rest of the run still succeeds.
func TestRefreshSkipsUserWhoseSourceEmailIsTaken(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	src := seedSource()
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)
	signUp(t, app, "bob@example.com")

	src.userRows[0].Email = "bob@example.com"
	rep, err := importAll(app, src, Options{Refresh: true})
	require.NoError(t, err)
	require.Equal(t, 1, reportsByTable(rep)["users"].SkippedEmailTaken)

	alice, err := app.FindFirstRecordByFilter("users", "legacy_id = {:id}", dbx.Params{"id": testUserUUID})
	require.NoError(t, err)
	require.Equal(t, "alice@example.com", alice.Email())
}
