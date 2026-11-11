package crypto

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"
)

// TestDigestIsPerUserAndKeyed: the route's digest carries the dk2 prefix, is
// different for each account, is not the old unkeyed SHA-256 (a fast offline
// oracle on the server key), and two different part lists no longer collide
// through the "|" join.
func TestDigestIsPerUserAndKeyed(t *testing.T) {
	resetKey(t, "digest-key")
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	newUser := func(email string) *core.Record {
		u := core.NewRecord(users)
		u.SetEmail(email)
		u.SetPassword("password123")
		require.NoError(t, app.Save(u))
		return u
	}
	alice := newUser("digest-alice@example.com")
	bob := newUser("digest-bob@example.com")

	body := `{"op":"digest","parts":[["a","b"],["a|b"],["a","b"]]}`
	a := callHandler(t, app, alice, body)["digests"].([]any)
	b := callHandler(t, app, bob, body)["digests"].([]any)

	require.Len(t, a, 3)
	for _, d := range a {
		require.True(t, strings.HasPrefix(d.(string), DigestPrefix), "digest must carry the dk2 prefix")
		require.Len(t, d.(string), len(DigestPrefix)+64)
	}
	require.Equal(t, a[0], a[2], "the same parts give the same digest for the same user")
	require.NotEqual(t, a[0], a[1], `["a","b"] and ["a|b"] must not collide`)
	require.NotEqual(t, a[0], b[0], "digests are per user")
	require.NotContains(t, a[0], oldStyle("digest-key", "a", "b"),
		"the unkeyed SHA-256 must not be returned")

	// A stored old key upgrades to exactly what the route returns now.
	up, changed := UpgradeDigest(digestKey, alice.Id, oldStyle("digest-key", "a", "b"))
	require.True(t, changed)
	require.Equal(t, a[0], up)
	same, changed := UpgradeDigest(digestKey, alice.Id, up)
	require.False(t, changed)
	require.Equal(t, up, same)
}

// TestDigestEscapingKeepsPlainParts: escaping only changes parts that contain
// "|" or "\", so the inner digest of ordinary ids is the old value.
func TestDigestEscapingKeepsPlainParts(t *testing.T) {
	require.Equal(t, oldStyle("k", "BKK_1", "F0", ""), digestValue("k", []string{"BKK_1", "F0", ""}))
	require.NotEqual(t, oldStyle("k", "a|b"), digestValue("k", []string{"a|b"}))
	require.NotEqual(t, digestValue("k", []string{`a\`, "b"}), digestValue("k", []string{"a", `\b`}))
}

// oldStyle is the pre-dk2 route digest, written out independently.
func oldStyle(password string, parts ...string) string {
	sum := sha256.Sum256([]byte(password + "|" + strings.Join(parts, "|")))
	return hex.EncodeToString(sum[:])
}
