package migrate

import (
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/crypto"
)

const (
	testNeptunUUID = "55555555-5555-5555-5555-555555555555"
	testTripUUID   = "66666666-6666-6666-6666-666666666666"

	// verifyTestKey is the NEPTUN_ENCRYPTION_KEY every verify test uses. The
	// crypto package memoises the derived key behind a sync.Once, so all tests in
	// this process must agree on it.
	verifyTestKey = "verify-test-key"
)

// withVerifyEnv installs the encryption key and returns the derived bytes.
func withVerifyEnv(t *testing.T) []byte {
	t.Helper()
	t.Setenv("NEPTUN_ENCRYPTION_KEY", verifyTestKey)
	key, err := crypto.EncryptionKeyFromEnv()
	require.NoError(t, err)
	require.NotEmpty(t, key)
	return key
}

// verifySourceFixture is seedSource with real, decryptable `vc2:` ciphertext in
// every encrypted table so the decrypt checks exercise the shared crypto path.
func verifySourceFixture(t *testing.T, key []byte) *stubSource {
	t.Helper()
	enc := func(plain string) string {
		v, err := crypto.EncryptValue(key, plain)
		require.NoError(t, err)
		return v
	}

	src := seedSource()
	src.tableRows["schedule_items"] = []DataRow{{
		ID: testItemUUID, UserID: testUserUUID,
		Fields: map[string]any{"payload": enc(`{"queued":true}`)},
	}}
	src.tableRows["schedule_events"] = []DataRow{{
		ID: testEventUUID, UserID: testUserUUID,
		Fields: map[string]any{
			"course_name": enc("Calculus"),
			"instructor":  enc("Dr. X"),
			"location":    enc("Room 1"),
			"day_of_week": 2,
			"start_time":  "08:00",
		},
	}}
	src.tableRows["neptun_schedule_events"] = []DataRow{{
		ID: testNeptunUUID, UserID: testUserUUID,
		Fields: map[string]any{"course_name": enc("Neptun Course")},
	}}
	src.tableRows["trip_plans"] = []DataRow{{
		ID: testTripUUID, UserID: testUserUUID,
		Fields: map[string]any{"name": enc("Home"), "legs": enc(`[{"stop":"A"}]`)},
	}}
	src.tableRows["transit_trackers"] = []DataRow{{
		ID: testTTUUID, UserID: testUserUUID,
		Fields: map[string]any{"stop_id": enc("S1"), "dedupe_key": "route-1"},
	}}
	src.tableRows["course_tracker_data"] = []DataRow{{
		UserID: testUserUUID,
		Fields: map[string]any{"data": enc(`{"points":1}`)},
	}}
	return src
}

func checksByName(rep VerifyReport) map[string]Check {
	out := make(map[string]Check, len(rep.Checks))
	for _, c := range rep.Checks {
		out[c.Name] = c
	}
	return out
}

// TestVerifyPassesAfterImport is the happy path: import the fixture, then every
// check passes.
func TestVerifyPassesAfterImport(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	key := withVerifyEnv(t)
	src := verifySourceFixture(t, key)
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	rep, err := verifySource(app, src, VerifyOptions{})
	require.NoError(t, err)
	require.True(t, rep.OK(), "all checks should pass:\n%s", rep.String())
	require.NoError(t, rep.Err())
	require.Contains(t, rep.String(), "all checks passed")

	byName := checksByName(rep)
	for _, name := range []string{
		"count:users", "count:passkeys",
		"count:schedule_events", "count:neptun_schedule_events",
		"count:transit_trackers", "count:trip_plans",
		"count:course_tracker_data", "count:schedule_items",
		"per-user:" + testUserUUID,
		"integrity:data.db", "login",
	} {
		require.True(t, byName[name].Passed, "%s should pass: %s", name, byName[name].Detail)
	}

	// Every encrypted table's sample decrypts through the shared crypto path.
	for _, name := range []string{
		"decrypt:schedule_events", "decrypt:neptun_schedule_events",
		"decrypt:transit_trackers", "decrypt:trip_plans",
		"decrypt:course_tracker_data", "decrypt:schedule_items",
	} {
		require.True(t, byName[name].Passed, "%s should pass: %s", name, byName[name].Detail)
		require.Contains(t, byName[name].Detail, "decrypted", "%s must report decrypted values", name)
	}

	// Without a test account the login check validates the bcrypt hash only and
	// says a live login is manual.
	require.Contains(t, byName["login"].Detail, "manual step")
	require.Contains(t, byName["integrity:data.db"].Detail, "=> ok")
}

// TestVerifyDetectsCountMismatch proves a lost PocketBase row fails parity.
func TestVerifyDetectsCountMismatch(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	key := withVerifyEnv(t)
	src := verifySourceFixture(t, key)
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	// Simulate a row lost after import.
	events, err := app.FindRecordsByFilter("schedule_events", "", "", 0, 0)
	require.NoError(t, err)
	require.Len(t, events, 1)
	require.NoError(t, app.Delete(events[0]))

	rep, err := verifySource(app, src, VerifyOptions{})
	require.NoError(t, err)
	require.False(t, rep.OK())
	require.Error(t, rep.Err())

	byName := checksByName(rep)
	require.False(t, byName["count:schedule_events"].Passed)
	require.Contains(t, byName["count:schedule_events"].Detail, "source=1 pocketbase=0")
	require.False(t, byName["per-user:"+testUserUUID].Passed)
}

// TestVerifyDetectsUndecryptableCiphertext proves a corrupt vc2 value fails.
func TestVerifyDetectsUndecryptableCiphertext(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	key := withVerifyEnv(t)
	src := verifySourceFixture(t, key)
	// Corrupt one ciphertext before importing it.
	src.tableRows["schedule_items"] = []DataRow{{
		ID: testItemUUID, UserID: testUserUUID,
		Fields: map[string]any{"payload": "vc2:not-valid-base64!!"},
	}}
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	rep, err := verifySource(app, src, VerifyOptions{})
	require.NoError(t, err)
	require.False(t, rep.OK())

	byName := checksByName(rep)
	require.False(t, byName["decrypt:schedule_items"].Passed)
	require.Contains(t, byName["decrypt:schedule_items"].Detail, "failed to decrypt")
	// The other tables still decrypt cleanly.
	require.True(t, byName["decrypt:schedule_events"].Passed)
}

// TestVerifyLoginWithTestAccount proves the live-login assertion path.
func TestVerifyLoginWithTestAccount(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	key := withVerifyEnv(t)
	src := verifySourceFixture(t, key)
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	rep, err := verifySource(app, src, VerifyOptions{TestEmail: "alice@example.com", TestPassword: "test-password"})
	require.NoError(t, err)
	require.True(t, checksByName(rep)["login"].Passed, "%s", checksByName(rep)["login"].Detail)
	require.Contains(t, checksByName(rep)["login"].Detail, "ValidatePassword passed")

	// A wrong password must fail the check.
	rep, err = verifySource(app, src, VerifyOptions{TestEmail: "alice@example.com", TestPassword: "wrong"})
	require.NoError(t, err)
	require.False(t, checksByName(rep)["login"].Passed)

	// An unknown email must fail too.
	rep, err = verifySource(app, src, VerifyOptions{TestEmail: "nobody@example.com", TestPassword: "x"})
	require.NoError(t, err)
	require.False(t, checksByName(rep)["login"].Passed)
}

// TestVerifyRequiresDSN guards the empty-DSN entry point.
func TestVerifyRequiresDSN(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	_, err = Verify(app, "   ")
	require.Error(t, err)
}

// TestVerifySamplesUsers proves SampleUsers bounds the spot check.
func TestVerifySamplesUsers(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	key := withVerifyEnv(t)
	src := verifySourceFixture(t, key)
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	rep, err := verifySource(app, src, VerifyOptions{SampleUsers: 1})
	require.NoError(t, err)

	n := 0
	for _, c := range rep.Checks {
		if len(c.Name) > len("per-user:") && c.Name[:len("per-user:")] == "per-user:" {
			n++
		}
	}
	require.Equal(t, 1, n, "SampleUsers must bound the per-user checks")
}

// TestVerifyLoginFallbackSelectsPasswordUser proves the bcrypt fallback picks an
// imported user that actually has a hash. Supabase auth.users legitimately
// contains OAuth/magic-link accounts with no encrypted_password; when such a
// user sorts first by legacy_id the check must still find the password user
// instead of false-failing.
func TestVerifyLoginFallbackSelectsPasswordUser(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	withVerifyEnv(t)

	const (
		// The no-password (OAuth/magic-link) user sorts first by legacy_id.
		oauthUUID = "00000000-0000-0000-0000-000000000001"
		pwUUID    = "ffffffff-ffff-ffff-ffff-ffffffffffffff"
	)

	// Import the password user through the normal path...
	src := &stubSource{userRows: []UserRow{{
		ID: pwUUID, Email: "pw@example.com", EncryptedPassword: testPasswordHash, EmailConfirmed: true,
	}}}
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	// ...then simulate an OAuth/magic-link user imported with no password hash.
	uc, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	oauth := core.NewRecord(uc)
	oauth.SetEmail("oauth@example.com")
	oauth.Set("legacy_id", oauthUUID)
	require.NoError(t, app.SaveNoValidate(oauth))
	require.Equal(t, "", oauth.GetString("password:hash"), "fixture user must have no hash")

	full := &stubSource{userRows: []UserRow{
		{ID: oauthUUID, Email: "oauth@example.com", EncryptedPassword: "", EmailConfirmed: true},
		{ID: pwUUID, Email: "pw@example.com", EncryptedPassword: testPasswordHash, EmailConfirmed: true},
	}}
	rep, err := verifySource(app, full, VerifyOptions{})
	require.NoError(t, err)
	require.True(t, rep.OK(), "run should pass:\n%s", rep.String())

	login := checksByName(rep)["login"]
	require.True(t, login.Passed, "%s", login.Detail)
	require.False(t, login.Skipped)
	require.Contains(t, login.Detail, "pw@example.com", "must report the user it checked")
	require.Contains(t, login.Detail, "valid bcrypt hash")
}

// TestVerifyLoginFallbackSkipsWithoutPasswords proves an all-passwordless import
// reports a neutral skipped login check rather than a failure.
func TestVerifyLoginFallbackSkipsWithoutPasswords(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	withVerifyEnv(t)

	const oauthUUID = "00000000-0000-0000-0000-000000000002"

	uc, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	oauth := core.NewRecord(uc)
	oauth.SetEmail("oauth@example.com")
	oauth.Set("legacy_id", oauthUUID)
	require.NoError(t, app.SaveNoValidate(oauth))

	src := &stubSource{userRows: []UserRow{{
		ID: oauthUUID, Email: "oauth@example.com", EncryptedPassword: "", EmailConfirmed: true,
	}}}
	rep, err := verifySource(app, src, VerifyOptions{})
	require.NoError(t, err)

	login := checksByName(rep)["login"]
	require.True(t, login.Passed, "a skipped login must not fail: %s", login.Detail)
	require.True(t, login.Skipped, "login must be reported as skipped")
	require.Contains(t, login.Detail, "skipped")
	require.NoError(t, rep.Err(), "a skipped login must not fail the run")
	require.True(t, rep.OK(), "run should pass:\n%s", rep.String())
	require.Contains(t, rep.String(), "[SKIP] login")
}
