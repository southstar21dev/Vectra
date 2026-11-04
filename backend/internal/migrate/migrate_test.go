package migrate

import (
	"context"
	"database/sql"
	"encoding/base64"
	"testing"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	_ "vectra/pb/migrations"
)

// stubSource is an in-memory [source] so the whole import can be exercised
// without a live Postgres.
type stubSource struct {
	userRows    []UserRow
	profileRows map[string]ProfileRow
	passkeyRows []PasskeyRow
	tableRows   map[string][]DataRow
	closed      bool
}

func (s *stubSource) users(context.Context) ([]UserRow, error) { return s.userRows, nil }
func (s *stubSource) profiles(context.Context) (map[string]ProfileRow, error) {
	return s.profileRows, nil
}
func (s *stubSource) passkeys(context.Context) ([]PasskeyRow, error) { return s.passkeyRows, nil }
func (s *stubSource) table(_ context.Context, name string) ([]DataRow, error) {
	return s.tableRows[name], nil
}
func (s *stubSource) close() error { s.closed = true; return nil }

func reportsByTable(rep Report) map[string]TableReport {
	out := make(map[string]TableReport, len(rep.Tables))
	for _, tr := range rep.Tables {
		out[tr.Table] = tr
	}
	return out
}

const (
	testUserUUID  = "11111111-1111-1111-1111-111111111111"
	testItemUUID  = "22222222-2222-2222-2222-222222222222"
	testEventUUID = "33333333-3333-3333-3333-333333333333"
	testTTUUID    = "44444444-4444-4444-4444-444444444444"
	// Valid bcrypt $2a$10$ hash of "test-password" (the Plan 0 spike vector).
	testPasswordHash = "$2a$10$gQtmApdMNxMS42Qnp55Fy.SEKH4feREFEnXTLvgooPsEwpjqTnNuu"
)

func seedSource() *stubSource {
	created := time.Date(2024, 3, 1, 10, 0, 0, 0, time.UTC)
	updated := time.Date(2024, 4, 1, 12, 30, 0, 0, time.UTC)
	lastSync := time.Date(2024, 4, 2, 8, 0, 0, 0, time.UTC)

	return &stubSource{
		userRows: []UserRow{{
			ID:                testUserUUID,
			Email:             "alice@example.com",
			EncryptedPassword: testPasswordHash,
			EmailConfirmed:    true,
			RawUserMetaData: map[string]any{
				"full_name":  "Alice Meta",
				"avatar_url": "https://meta.example/a.png",
			},
			CreatedAt: created,
			UpdatedAt: updated,
		}},
		profileRows: map[string]ProfileRow{
			testUserUUID: {
				ID:             testUserUUID,
				FullName:       "Alice Profile",
				AvatarURL:      "https://profile.example/a.png",
				Appearance:     map[string]any{"theme": "dark"},
				NeptunICSURL:   "https://neptun.example/ics",
				NeptunLastSync: &lastSync,
				Preferences:    map[string]any{"lang": "en"},
				CreatedAt:      created,
				UpdatedAt:      updated,
			},
		},
		passkeyRows: []PasskeyRow{{
			UserID:          testUserUUID,
			CredentialID:    []byte{1, 2, 3, 250},
			PublicKey:       []byte{9, 8, 7},
			AttestationType: "none",
			AAGUID:          make([]byte, 16),
			SignCount:       7,
			Transports:      []string{"internal"},
			BackupEligible:  true,
			BackedUp:        true,
			FriendlyName:    "Yubi",
			CreatedAt:       created,
			UpdatedAt:       updated,
			LastUsedAt:      &lastSync,
		}},
		tableRows: map[string][]DataRow{

			"schedule_items": {{
				ID: testItemUUID, UserID: testUserUUID,
				Fields: map[string]any{"payload": "vc2:item-payload"},
			}},
			"schedule_events": {{
				ID: testEventUUID, UserID: testUserUUID,
				Fields: map[string]any{"course_name": "vc2:event-name", "day_of_week": 2, "start_time": "08:00"},
			}},
			"course_tracker_data": {{
				UserID: testUserUUID,
				Fields: map[string]any{"data": "vc2:tracker-data", "updated_at": updated},
			}},
			"transit_trackers": {{
				ID: testTTUUID, UserID: testUserUUID,
				Fields: map[string]any{"stop_id": "S1", "dedupe_key": "route-1"},
			}},
		},
	}
}

// TestImportRemapsPreservesAndIsIdempotent covers the required behaviours in
// one run: user_id -> user remapping, legacy_id/client_id, bcrypt preservation,
// verbatim vc2: blobs, and a re-run that duplicates nothing.
func TestImportRemapsPreservesAndIsIdempotent(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	src := seedSource()
	created := time.Date(2024, 3, 1, 10, 0, 0, 0, time.UTC)

	// tests.NewTestApp() ships a seeded users collection; count relative to it.
	beforeUsers, err := app.FindAllRecords("users")
	require.NoError(t, err)

	rep, err := importAll(app, src, Options{})
	require.NoError(t, err)
	require.Contains(t, rep.String(), "migrate-supabase report")

	// --- users ---------------------------------------------------------------
	u, err := app.FindFirstRecordByFilter("users", "legacy_id = {:id}", dbx.Params{"id": testUserUUID})
	require.NoError(t, err, "user must be found by legacy_id")
	require.Equal(t, testUserUUID, u.GetString("legacy_id"))
	require.Equal(t, "alice@example.com", u.Email())
	require.True(t, u.Verified(), "email_confirmed_at != null => verified")
	// profiles wins over raw_user_meta_data.
	require.Equal(t, "Alice Profile", u.GetString("full_name"))
	require.Equal(t, "https://profile.example/a.png", u.GetString("avatar_url"))
	require.Equal(t, "https://neptun.example/ics", u.GetString("neptun_ics_url"))
	require.Contains(t, u.GetString("appearance"), "dark")
	require.Contains(t, u.GetString("preferences"), "en")
	require.True(t, created.Equal(u.GetDateTime("created_at").Time()), "created_at verbatim")

	// bcrypt hash transplanted verbatim and still authenticates.
	require.Equal(t, testPasswordHash, u.GetString("password:hash"))
	require.True(t, u.ValidatePassword("test-password"), "imported hash must authenticate")

	// --- passkeys ------------------------------------------------------------
	pk, err := app.FindFirstRecordByFilter("passkeys", "user = {:u}", dbx.Params{"u": u.Id})
	require.NoError(t, err)
	require.Equal(t, base64.RawURLEncoding.EncodeToString([]byte{1, 2, 3, 250}), pk.GetString("credential_id"))
	require.Equal(t, base64.RawURLEncoding.EncodeToString([]byte{9, 8, 7}), pk.GetString("public_key"))
	require.Equal(t, base64.RawURLEncoding.EncodeToString(make([]byte, 16)), pk.GetString("aaguid"))
	require.Equal(t, 7, pk.GetInt("sign_count"))
	require.True(t, pk.GetBool("backup_eligible"))
	require.True(t, pk.GetBool("backed_up"))
	require.Equal(t, "Yubi", pk.GetString("friendly_name"))
	require.Equal(t, []string{"internal"}, pk.GetStringSlice("transports"), "transports must round-trip")

	// --- schedule_items: client_id set, PB id minted, vc2 preserved ----------
	items, err := app.FindRecordsByFilter("schedule_items", "user = {:u}", "", 0, 0, dbx.Params{"u": u.Id})
	require.NoError(t, err)
	require.Len(t, items, 1)
	require.Equal(t, testItemUUID, items[0].GetString("client_id"))
	require.Equal(t, "vc2:item-payload", items[0].GetString("payload"))
	require.NotEqual(t, testItemUUID, items[0].Id, "the PocketBase id must not be the source UUID")

	// --- schedule_events: user remapped, vc2 preserved -----------------------
	events, err := app.FindRecordsByFilter("schedule_events", "user = {:u}", "", 0, 0, dbx.Params{"u": u.Id})
	require.NoError(t, err)
	require.Len(t, events, 1)
	require.Equal(t, "vc2:event-name", events[0].GetString("course_name"))
	require.Equal(t, 2, events[0].GetInt("day_of_week"))

	// --- course_tracker_data: singleton per user, vc2 preserved --------------
	tracker, err := app.FindRecordsByFilter("course_tracker_data", "user = {:u}", "", 0, 0, dbx.Params{"u": u.Id})
	require.NoError(t, err)
	require.Len(t, tracker, 1)
	require.Equal(t, "vc2:tracker-data", tracker[0].GetString("data"))

	// --- transit_trackers: remapped + natural key preserved ------------------
	tt, err := app.FindRecordsByFilter("transit_trackers", "user = {:u}", "", 0, 0, dbx.Params{"u": u.Id})
	require.NoError(t, err)
	require.Len(t, tt, 1)
	require.Equal(t, "route-1", tt[0].GetString("dedupe_key"))

	// --- report --------------------------------------------------------------
	first := reportsByTable(rep)
	for _, name := range []string{"users", "passkeys", "schedule_items", "schedule_events", "course_tracker_data", "transit_trackers"} {
		require.Equal(t, 1, first[name].Imported, "%s imported", name)
		require.Equal(t, 0, first[name].SkippedExisting, "%s skipped", name)
	}

	// --- idempotent re-run ---------------------------------------------------
	rep2, err := importAll(app, src, Options{})
	require.NoError(t, err)
	second := reportsByTable(rep2)
	for _, name := range []string{"users", "passkeys", "schedule_items", "schedule_events", "course_tracker_data", "transit_trackers"} {
		require.Equal(t, 0, second[name].Imported, "%s must not re-import", name)
		require.Equal(t, 1, second[name].SkippedExisting, "%s must be skipped", name)
	}

	// No duplicates materialised.
	items, err = app.FindRecordsByFilter("schedule_items", "", "", 0, 0)
	require.NoError(t, err)
	require.Len(t, items, 1)
	tracker, err = app.FindRecordsByFilter("course_tracker_data", "", "", 0, 0)
	require.NoError(t, err)
	require.Len(t, tracker, 1)
	users, err := app.FindAllRecords("users")
	require.NoError(t, err)
	require.Len(t, users, len(beforeUsers)+1, "exactly one user must be imported")
}

// TestDryRunReportsWithoutWriting proves --dry-run connects/reports but leaves
// PocketBase untouched.
func TestDryRunReportsWithoutWriting(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	before, err := app.FindAllRecords("users")
	require.NoError(t, err)

	rep, err := importAll(app, seedSource(), Options{DryRun: true})
	require.NoError(t, err)

	byTable := reportsByTable(rep)
	require.Equal(t, 1, byTable["users"].Imported)
	require.Equal(t, 1, byTable["schedule_items"].Imported)

	users, err := app.FindAllRecords("users")
	require.NoError(t, err)
	require.Len(t, users, len(before), "dry-run must not create users")
	items, err := app.FindRecordsByFilter("schedule_items", "", "", 0, 0)
	require.NoError(t, err)
	require.Empty(t, items, "dry-run must not create data rows")
}

func TestDerivedIDIsStableAndValid(t *testing.T) {
	a := derivedID("schedule_events", testEventUUID)
	b := derivedID("schedule_events", testEventUUID)
	require.Equal(t, a, b, "derived id must be deterministic")
	require.Len(t, a, 15, "derived id must be a valid PocketBase id length")
	require.Regexp(t, "^[a-z0-9]{15}$", a)
	require.NotEqual(t, a, derivedID("trip_plans", testEventUUID), "table must be part of the key")
}

// TestDecodeTransports covers the passkey transports fix directly: the query
// asks Postgres for JSON, so the decoder must accept a JSON array and reject a
// raw text[] literal instead of swallowing the error.
func TestDecodeTransports(t *testing.T) {
	got, err := decodeTransports(`["internal","usb"]`)
	require.NoError(t, err)
	require.Equal(t, []string{"internal", "usb"}, got)

	got, err = decodeTransports("null")
	require.NoError(t, err)
	require.Nil(t, got)

	got, err = decodeTransports("")
	require.NoError(t, err)
	require.Nil(t, got)

	// The pre-fix driver value (a raw text[] literal) must fail loudly.
	_, err = decodeTransports("{internal}")
	require.Error(t, err, "a raw text[] literal must not be silently dropped")
}

// TestSkipsAreCategorized proves data not represented (an email-less user and
// a row whose user was not imported) is counted separately from expected
// idempotent skips, so Task 4's count parity can reconcile it.
func TestSkipsAreCategorized(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	const (
		goodUUID    = "aaaa1111-1111-1111-1111-111111111111"
		noEmailUUID = "bbbb2222-2222-2222-2222-222222222222"
		orphanUUID  = "cccc3333-3333-3333-3333-333333333333"
	)

	before, err := app.FindAllRecords("users")
	require.NoError(t, err)

	src := &stubSource{
		userRows: []UserRow{
			{ID: goodUUID, Email: "good@example.com", EncryptedPassword: testPasswordHash, EmailConfirmed: true},
			{ID: noEmailUUID, Email: "", EmailConfirmed: false},
		},
		tableRows: map[string][]DataRow{
			"schedule_events": {
				{ID: "dddd4444-4444-4444-4444-444444444444", UserID: goodUUID, Fields: map[string]any{"course_name": "ok"}},
				{ID: "eeee5555-5555-5555-5555-555555555555", UserID: orphanUUID, Fields: map[string]any{"course_name": "orphan"}},
			},
		},
	}

	rep, err := importAll(app, src, Options{})
	require.NoError(t, err)
	byTable := reportsByTable(rep)

	require.Equal(t, 1, byTable["users"].Imported)
	require.Equal(t, 1, byTable["users"].SkippedNoEmail, "email-less user must be counted separately")
	require.Equal(t, 0, byTable["users"].SkippedExisting)
	require.Equal(t, 0, byTable["users"].SkippedUnknownUser)

	require.Equal(t, 1, byTable["schedule_events"].Imported)
	require.Equal(t, 1, byTable["schedule_events"].SkippedUnknownUser, "orphan row must be counted separately")
	require.Equal(t, 0, byTable["schedule_events"].SkippedExisting)
	require.Equal(t, 0, byTable["schedule_events"].SkippedNoEmail)

	// Neither the email-less user nor the orphan row was written.
	users, err := app.FindAllRecords("users")
	require.NoError(t, err)
	require.Len(t, users, len(before)+1)
	events, err := app.FindRecordsByFilter("schedule_events", "", "", 0, 0)
	require.NoError(t, err)
	require.Len(t, events, 1)
	require.Equal(t, "ok", events[0].GetString("course_name"))

	// The report string exposes the categories.
	require.Contains(t, rep.String(), "skipped_no_email=1")
	require.Contains(t, rep.String(), "skipped_unknown_user=1")
}

// TestRerunIsInsertOnlyUnlessRefresh: after cutover, imported rows are live. A
// default re-run must leave in-app changes alone; only --refresh (Refresh)
// overwrites them from the source.
func TestRerunIsInsertOnlyUnlessRefresh(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	src := seedSource()
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	u, err := app.FindFirstRecordByFilter("users", "legacy_id = {:id}", dbx.Params{"id": testUserUUID})
	require.NoError(t, err)
	pk, err := app.FindFirstRecordByFilter("passkeys", "user = {:u}", dbx.Params{"u": u.Id})
	require.NoError(t, err)
	item, err := app.FindFirstRecordByFilter("schedule_items", "client_id = {:c}", dbx.Params{"c": testItemUUID})
	require.NoError(t, err)

	// Changes made in the app after cutover.
	u.SetEmail("alice-new@example.com")
	u.SetPassword("changed-password")
	require.NoError(t, app.Save(u))
	pk.Set("sign_count", 42)
	require.NoError(t, app.Save(pk))
	item.Set("payload", "vc3:edited-in-app")
	require.NoError(t, app.Save(item))

	reload := func() (string, bool, int, string) {
		u2, err := app.FindRecordById("users", u.Id)
		require.NoError(t, err)
		pk2, err := app.FindRecordById("passkeys", pk.Id)
		require.NoError(t, err)
		item2, err := app.FindRecordById("schedule_items", item.Id)
		require.NoError(t, err)
		return u2.Email(), u2.ValidatePassword("changed-password"), pk2.GetInt("sign_count"), item2.GetString("payload")
	}

	rep, err := importAll(app, src, Options{})
	require.NoError(t, err)
	for _, name := range []string{"users", "passkeys", "schedule_items", "course_tracker_data"} {
		require.Equal(t, 1, reportsByTable(rep)[name].SkippedExisting, name)
	}
	email, pwOK, signCount, payload := reload()
	require.Equal(t, "alice-new@example.com", email, "default re-run must not roll back the email")
	require.True(t, pwOK, "default re-run must not roll back the password")
	require.Equal(t, 42, signCount, "default re-run must not roll back the sign counter")
	require.Equal(t, "vc3:edited-in-app", payload, "default re-run must not overwrite app data")

	_, err = importAll(app, src, Options{Refresh: true})
	require.NoError(t, err)
	email, pwOK, signCount, payload = reload()
	require.Equal(t, "alice@example.com", email, "refresh restores the source email")
	require.False(t, pwOK, "refresh restores the source password hash")
	require.Equal(t, 7, signCount)
	require.Equal(t, "vc2:item-payload", payload)
}

func TestResolveBackupEligible(t *testing.T) {
	null := sql.NullBool{}
	yes := sql.NullBool{Bool: true, Valid: true}
	no := sql.NullBool{Bool: false, Valid: true}

	require.True(t, resolveBackupEligible(yes, no))
	require.False(t, resolveBackupEligible(no, no))
	require.True(t, resolveBackupEligible(null, yes), "backed up implies backup eligible")
	require.True(t, resolveBackupEligible(no, yes), "BE=0 with BS=1 is invalid; BS wins")
	require.False(t, resolveBackupEligible(null, null), "no evidence keeps the previous default")
}
