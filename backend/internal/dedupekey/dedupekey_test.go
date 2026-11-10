package dedupekey_test

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/crypto"
	"vectra/pb/internal/dedupekey"
	_ "vectra/pb/migrations"
)

// testKey is the only NEPTUN_ENCRYPTION_KEY this package uses: the crypto
// route memoises the key on first use, so every test must agree on it.
const testKey = "dedupekey-test-key-0123456789abcdef"

func digestKey(t *testing.T) []byte {
	t.Helper()
	t.Setenv("NEPTUN_ENCRYPTION_KEY", testKey)
	k, err := crypto.DigestKeyFromEnv()
	require.NoError(t, err)
	return k
}

// oldDigest is the formula the route used before dk2, written out
// independently of the code under test.
func oldDigest(parts ...string) string {
	sum := sha256.Sum256([]byte(testKey + "|" + strings.Join(parts, "|")))
	return hex.EncodeToString(sum[:])
}

func newUser(t *testing.T, app core.App, email string) *core.Record {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.SetEmail(email)
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))
	return u
}

func newTracker(t *testing.T, app core.App, user *core.Record, key string) *core.Record {
	t.Helper()
	col, err := app.FindCollectionByNameOrId("transit_trackers")
	require.NoError(t, err)
	r := core.NewRecord(col)
	r.Set("user", user.Id)
	r.Set("dedupe_key", key)
	r.Set("stop_id", "vc3:x")
	require.NoError(t, app.Save(r))
	return r
}

func storedKey(t *testing.T, app core.App, id string) string {
	t.Helper()
	var k string
	require.NoError(t, app.DB().NewQuery("SELECT dedupe_key FROM transit_trackers WHERE id = {:id}").
		Bind(dbx.Params{"id": id}).Row(&k))
	return k
}

// routeDigest asks the crypto route for the digest of parts as user.
func routeDigest(t *testing.T, app core.App, user *core.Record, parts ...string) string {
	t.Helper()
	body, err := json.Marshal(map[string]any{"op": "digest", "parts": [][]string{parts}})
	require.NoError(t, err)
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/crypto", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{App: app, Auth: user}
	e.Request = req
	e.Response = rec
	require.NoError(t, crypto.Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)
	var out struct {
		Digests []string `json:"digests"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &out))
	require.Len(t, out.Digests, 1)
	return out.Digests[0]
}

// The core promise: after the rewrite, a stored tracker's key equals what the
// route now returns for the same (route, stop, headsign) and owner, so the
// unique index still rejects adding that tracker again.
func TestUpgradeMatchesRouteDigest(t *testing.T) {
	key := digestKey(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	alice := newUser(t, app, "dedupe-alice@example.com")
	bob := newUser(t, app, "dedupe-bob@example.com")
	parts := []string{"BKK_0050", "BKK_F01234", "Újbuda-központ M"}
	old := oldDigest(parts...)
	// The old digest was global: both users stored the same key.
	ta := newTracker(t, app, alice, old)
	tb := newTracker(t, app, bob, old)
	other := newTracker(t, app, alice, oldDigest("BKK_0070", "BKK_F0", ""))

	rep, err := dedupekey.Upgrade(app, func() ([]byte, error) { return key, nil })
	require.NoError(t, err)
	require.Equal(t, 3, rep.Converted)
	require.Empty(t, rep.Left)

	newA := storedKey(t, app, ta.Id)
	newB := storedKey(t, app, tb.Id)
	require.True(t, strings.HasPrefix(newA, crypto.DigestPrefix))
	require.Equal(t, routeDigest(t, app, alice, parts...), newA, "alice's key must match the route's new digest")
	require.Equal(t, routeDigest(t, app, bob, parts...), newB, "bob's key must match the route's new digest")
	require.NotEqual(t, newA, newB, "keys are per user now")
	require.Equal(t, routeDigest(t, app, alice, "BKK_0070", "BKK_F0", ""), storedKey(t, app, other.Id))

	// Adding the same tracker again still collides on (user, dedupe_key).
	col, err := app.FindCollectionByNameOrId("transit_trackers")
	require.NoError(t, err)
	dup := core.NewRecord(col)
	dup.Set("user", alice.Id)
	dup.Set("dedupe_key", routeDigest(t, app, alice, parts...))
	require.Error(t, app.Save(dup), "re-adding an existing tracker must still be rejected")

	// A second run changes nothing.
	rep, err = dedupekey.Upgrade(app, func() ([]byte, error) { return nil, errors.New("must not be called") })
	require.NoError(t, err)
	require.Zero(t, rep.Converted)
	require.Equal(t, newA, storedKey(t, app, ta.Id))
}

func TestUpgradeWithoutKeyChangesNothing(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	// No rows: the key is not needed.
	_, err = dedupekey.Upgrade(app, func() ([]byte, error) { return nil, errors.New("no key") })
	require.NoError(t, err)

	alice := newUser(t, app, "dedupe-nokey@example.com")
	tr := newTracker(t, app, alice, "legacy-key")
	_, err = dedupekey.Upgrade(app, func() ([]byte, error) { return nil, errors.New("no key") })
	require.Error(t, err)
	require.Equal(t, "legacy-key", storedKey(t, app, tr.Id))
}

// A row whose new key is already taken (old-format rows imported after the
// switch, next to a tracker the user added since) is left alone, not deleted.
func TestUpgradeLeavesCollisionsInPlace(t *testing.T) {
	key := digestKey(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	alice := newUser(t, app, "dedupe-collide@example.com")
	old := oldDigest("R", "S", "")
	upgraded, _ := crypto.UpgradeDigest(key, alice.Id, old)
	current := newTracker(t, app, alice, upgraded)
	imported := newTracker(t, app, alice, old)

	rep, err := dedupekey.Upgrade(app, func() ([]byte, error) { return key, nil })
	require.NoError(t, err)
	require.Zero(t, rep.Converted)
	require.Len(t, rep.Left, 1)
	require.Equal(t, old, storedKey(t, app, imported.Id))
	require.Equal(t, upgraded, storedKey(t, app, current.Id))
}

func TestMigrationAppliesOnFreshDatabase(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	var applied []string
	require.NoError(t, app.DB().NewQuery("SELECT file FROM _migrations WHERE file = {:f}").
		Bind(dbx.Params{"f": "1700000014_rekey_transit_dedupe.go"}).Column(&applied))
	require.Equal(t, []string{"1700000014_rekey_transit_dedupe.go"}, applied)
}
