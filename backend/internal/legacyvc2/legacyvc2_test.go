package legacyvc2_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/types"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/crypto"
	"vectra/pb/internal/legacyvc2"
	_ "vectra/pb/migrations"
)

// testKey is the only NEPTUN_ENCRYPTION_KEY this package uses: the crypto
// route memoises the key on first use, so every test must agree on it.
const testKey = "legacyvc2-test-key"

func envKey(t *testing.T) []byte {
	t.Helper()
	t.Setenv("NEPTUN_ENCRYPTION_KEY", testKey)
	key, err := crypto.EncryptionKeyFromEnv()
	require.NoError(t, err)
	return key
}

type fixture struct {
	app   *tests.TestApp
	key   []byte
	alice *core.Record
	bob   *core.Record
	// plain maps "collection/id.field" to the plaintext of each vc2 seed.
	plain map[string]string
	owner map[string]string
	// corrupt are seeds that must survive the upgrade untouched.
	corrupt map[string]string
}

func vc2(t *testing.T, key []byte, plain string) string {
	t.Helper()
	v, err := crypto.EncryptValue(key, plain)
	require.NoError(t, err)
	return v
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	key := envKey(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	t.Cleanup(app.Cleanup)

	f := &fixture{app: app, key: key, plain: map[string]string{}, owner: map[string]string{}, corrupt: map[string]string{}}

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	newUser := func(email string) *core.Record {
		u := core.NewRecord(users)
		u.SetEmail(email)
		u.SetPassword("password123")
		require.NoError(t, app.Save(u))
		return u
	}
	f.alice = newUser("vc2-alice@example.com")
	f.bob = newUser("vc2-bob@example.com")

	// users.neptun_ics_url: the owner is the user record itself.
	icsPlain := "https://neptun.example.com/ics/alice"
	f.alice.Set("neptun_ics_url", vc2(t, key, icsPlain))
	require.NoError(t, app.Save(f.alice))
	f.track("users", f.alice.Id, "neptun_ics_url", icsPlain, f.alice.Id)

	seed := func(collection string, owner *core.Record, fields map[string]any) *core.Record {
		col, err := app.FindCollectionByNameOrId(collection)
		require.NoError(t, err)
		rec := core.NewRecord(col)
		rec.Set("user", owner.Id)
		for k, v := range fields {
			rec.Set(k, v)
		}
		require.NoError(t, app.Save(rec))
		return rec
	}
	enc := func(collection string, owner *core.Record, plain map[string]string, extra map[string]any) {
		fields := map[string]any{}
		for k, v := range extra {
			fields[k] = v
		}
		for k, v := range plain {
			fields[k] = vc2(t, key, v)
		}
		rec := seed(collection, owner, fields)
		for k, v := range plain {
			f.track(collection, rec.Id, k, v, owner.Id)
		}
	}

	enc("schedule_events", f.alice, map[string]string{"course_name": "Calculus", "instructor": "Dr. X", "location": "Room 1"},
		map[string]any{"day_of_week": 2, "start_time": "08:00"})
	enc("neptun_schedule_events", f.bob, map[string]string{"course_name": "Neptun Course", "day_of_week": "3", "semester_start": "2026-09-01"}, nil)
	enc("schedule_items", f.bob, map[string]string{"payload": `{"title":"Exam"}`}, map[string]any{"client_id": "11111111-1111-4111-8111-111111111111"})
	enc("course_tracker_data", f.alice, map[string]string{"data": `{"courses":[]}`}, nil)
	// JSON fields hold the ciphertext as a JSON string.
	enc("trip_plans", f.alice, map[string]string{"name": "Home", "legs": `[{"route":"4"}]`}, map[string]any{"sort_order": 1})
	enc("transit_trackers", f.bob, map[string]string{"stop_name": "Blaha", "stops": `[{"stop_id":"F1"}]`}, map[string]any{"dedupe_key": "dk-1"})

	// Left as they are: a corrupt blob, and a vc2 value under another key.
	bad := seed("schedule_items", f.alice, map[string]any{"payload": "vc2:not-valid-base64!!", "client_id": "22222222-2222-4222-8222-222222222222"})
	f.corrupt["schedule_items/"+bad.Id+".payload"] = "vc2:not-valid-base64!!"
	otherKey := vc2(t, bytes.Repeat([]byte{7}, 32), "secret")
	wrong := seed("course_tracker_data", f.bob, map[string]any{"data": otherKey})
	f.corrupt["course_tracker_data/"+wrong.Id+".data"] = otherKey

	return f
}

func (f *fixture) track(collection, id, field, plain, owner string) {
	k := collection + "/" + id + "." + field
	f.plain[k] = plain
	f.owner[k] = owner
}

// stored reads a field's value as a string, unwrapping a JSON string.
func stored(t *testing.T, app core.App, key string) string {
	t.Helper()
	collection, rest, _ := strings.Cut(key, "/")
	id, field, _ := strings.Cut(rest, ".")
	rec, err := app.FindRecordById(collection, id)
	require.NoError(t, err)
	switch v := rec.Get(field).(type) {
	case string:
		return v
	default:
		raw, err := json.Marshal(v)
		require.NoError(t, err)
		var s string
		require.NoError(t, json.Unmarshal(raw, &s), "field %s is not a JSON string: %s", key, raw)
		return s
	}
}

func snapshot(t *testing.T, f *fixture) map[string]string {
	t.Helper()
	out := map[string]string{}
	for k := range f.plain {
		out[k] = stored(t, f.app, k)
	}
	for k := range f.corrupt {
		out[k] = stored(t, f.app, k)
	}
	return out
}

func TestUpgradeReencryptsVC2ForOwner(t *testing.T) {
	f := newFixture(t)

	// A field that only mentions vc2 inside a JSON object is reported, not changed.
	events, err := f.app.FindRecordsByFilter("schedule_events", "", "", 1, 0)
	require.NoError(t, err)
	ev := events[0]
	ev.Set("csv_metadata", map[string]any{"note": "vc2:inside"})
	require.NoError(t, f.app.Save(ev))

	report, err := legacyvc2.Upgrade(f.app, crypto.EncryptionKeyFromEnv)
	require.NoError(t, err)
	require.Equal(t, len(f.plain), report.Converted)
	require.Len(t, report.Left, len(f.corrupt))
	for _, v := range report.Left {
		_, ok := f.corrupt[v.Collection+"/"+v.RecordID+"."+v.Field]
		require.True(t, ok, "unexpected value left: %s", v)
		require.Equal(t, "does not decrypt", v.Reason)
	}
	require.Equal(t, []legacyvc2.Value{{
		Collection: "schedule_events", RecordID: ev.Id, Field: "csv_metadata",
		Reason: "mentions vc2: but is not a vc2 value",
	}}, report.Nested)

	for k, plain := range f.plain {
		v := stored(t, f.app, k)
		require.Truef(t, strings.HasPrefix(v, crypto.V3Prefix), "%s must be vc3, got %.8s", k, v)
		got, err := crypto.DecryptValueFor(f.key, v, f.owner[k])
		require.NoErrorf(t, err, "%s must decrypt for its owner", k)
		require.Equal(t, plain, got)
		other := f.alice.Id
		if f.owner[k] == f.alice.Id {
			other = f.bob.Id
		}
		_, err = crypto.DecryptValueFor(f.key, v, other)
		require.Errorf(t, err, "%s must not decrypt for another user", k)
	}
	for k, v := range f.corrupt {
		require.Equal(t, v, stored(t, f.app, k), "%s must be left as it was", k)
	}

	// The nested mention is unchanged.
	ev, err = f.app.FindRecordById("schedule_events", ev.Id)
	require.NoError(t, err)
	require.JSONEq(t, `{"note":"vc2:inside"}`, string(ev.Get("csv_metadata").(types.JSONRaw)))
}

func TestUpgradeSecondRunIsNoOp(t *testing.T) {
	f := newFixture(t)

	_, err := legacyvc2.Upgrade(f.app, crypto.EncryptionKeyFromEnv)
	require.NoError(t, err)
	after := snapshot(t, f)

	report, err := legacyvc2.Upgrade(f.app, crypto.EncryptionKeyFromEnv)
	require.NoError(t, err)
	require.Zero(t, report.Converted)
	require.Len(t, report.Left, len(f.corrupt), "only the undecryptable values remain")
	require.Equal(t, after, snapshot(t, f), "a second run must change nothing")
}

func TestUpgradeWithoutKey(t *testing.T) {
	f := newFixture(t)
	before := snapshot(t, f)

	missing := func() ([]byte, error) { return nil, errors.New("NEPTUN_ENCRYPTION_KEY is not set") }
	_, err := legacyvc2.Upgrade(f.app, missing)
	require.ErrorContains(t, err, "NEPTUN_ENCRYPTION_KEY is not set")
	require.ErrorContains(t, err, "vc2 value(s) must be re-encrypted")
	require.Equal(t, before, snapshot(t, f), "a failed run must change nothing")

	// With no vc2 left, the key is never needed.
	_, err = legacyvc2.Upgrade(f.app, crypto.EncryptionKeyFromEnv)
	require.NoError(t, err)
	for k := range f.corrupt {
		collection, rest, _ := strings.Cut(k, "/")
		id, _, _ := strings.Cut(rest, ".")
		rec, err := f.app.FindRecordById(collection, id)
		require.NoError(t, err)
		require.NoError(t, f.app.Delete(rec))
	}
	called := false
	_, err = legacyvc2.Upgrade(f.app, func() ([]byte, error) { called = true; return nil, errors.New("unset") })
	require.NoError(t, err)
	require.False(t, called, "the key must not be loaded when there is no vc2 value")
}

// TestRouteRefusesVC2AfterUpgrade drives the crypto route: the owner reads the
// upgraded vc3 value, while the original vc2 blob is refused (echoed back with
// its index in `errors`), for its owner as much as anyone else.
func TestRouteRefusesVC2AfterUpgrade(t *testing.T) {
	f := newFixture(t)
	k := "users/" + f.alice.Id + ".neptun_ics_url"
	original := stored(t, f.app, k)
	require.True(t, strings.HasPrefix(original, crypto.V2Prefix))

	_, err := legacyvc2.Upgrade(f.app, crypto.EncryptionKeyFromEnv)
	require.NoError(t, err)
	upgraded := stored(t, f.app, k)

	body, _ := json.Marshal(map[string]any{"op": "decrypt", "values": []string{upgraded, original}})
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/crypto", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{App: f.app, Auth: f.alice}
	e.Request = req
	e.Response = rec
	require.NoError(t, crypto.Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)

	var out map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &out))
	require.Equal(t, []any{f.plain[k], original}, out["values"])
	require.Equal(t, []any{float64(1)}, out["errors"])
}

func TestMigrationAppliesOnFreshDatabase(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	var applied []string
	err = app.DB().NewQuery("SELECT file FROM _migrations WHERE file = {:f}").
		Bind(dbx.Params{"f": "1700000013_reencrypt_legacy_vc2.go"}).
		Column(&applied)
	require.NoError(t, err)
	require.Equal(t, []string{"1700000013_reencrypt_legacy_vc2.go"}, applied,
		"with no vc2 data the migration needs no key and is recorded as applied")
}
