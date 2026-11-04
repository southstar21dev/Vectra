package crypto

import (
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	_ "vectra/pb/migrations"
)

func TestDeriveKeyMatchesWebCrypto(t *testing.T) {
	key := deriveKey("test-key")
	require.Equal(t, "ff69cd030220d6802b31aa8288e5d1508c39000c2acf4039024f05d37854b157", hex.EncodeToString(key))
}

func TestDecryptWebCryptoVector(t *testing.T) {
	key := deriveKey("test-key")
	got, err := decryptValue(key, "vc2:AAECAwQFBgcICQoLOY8X/pUZ4LVnmsNxo2f1Y8NHKaeqONMAhR0t8Hgk5/p5tAAZWLM=")
	require.NoError(t, err)
	require.Equal(t, "Vectra cross-impl test", got)
}

func TestRoundTrip(t *testing.T) {
	key := deriveKey("another-key")
	ct, err := encryptValue(key, "hello world")
	require.NoError(t, err)
	require.Contains(t, ct, "vc2:")
	got, err := decryptValue(key, ct)
	require.NoError(t, err)
	require.Equal(t, "hello world", got)
}

func TestDigestVector(t *testing.T) {
	require.Equal(t, "71e76c7947e7bfdb5e04d5a99d0ff33e86e403021bbcc2e95d6f02e32634a890", digestValue("test-key", []string{"a", "b"}))
}

// TestExportedValueHelpers locks the shared decrypt path the migration verifier
// uses: EncryptValue/DecryptValue are thin wrappers over the same primitives the
// HTTP route calls, and DecryptValue rejects values without the vc2: prefix
// instead of mis-slicing them.
func TestExportedValueHelpers(t *testing.T) {
	key := deriveKey("exported-helpers")

	ct, err := EncryptValue(key, "secret")
	require.NoError(t, err)
	require.Contains(t, ct, V2Prefix)

	got, err := DecryptValue(key, ct)
	require.NoError(t, err)
	require.Equal(t, "secret", got)

	// A value without the prefix must be rejected, not silently mangled.
	_, err = DecryptValue(key, "plaintext")
	require.Error(t, err)

	// A different key must not decrypt it.
	_, err = DecryptValue(deriveKey("other-key"), ct)
	require.Error(t, err)
}

// TestVC3BoundToOwner: a vc3 ciphertext opens only for the user it was sealed
// for, while legacy vc2 values still open for anyone holding the key.
func TestVC3BoundToOwner(t *testing.T) {
	key := deriveKey("vc3-key")

	ct, err := encryptValueFor(key, "secret", "userA")
	require.NoError(t, err)
	require.True(t, strings.HasPrefix(ct, V3Prefix))

	got, err := decryptValueFor(key, ct, "userA")
	require.NoError(t, err)
	require.Equal(t, "secret", got)

	_, err = decryptValueFor(key, ct, "userB")
	require.Error(t, err, "another user must not decrypt a vc3 value")
	_, err = decryptValueFor(key, ct, "")
	require.Error(t, err)

	// Legacy vc2 still decrypts regardless of caller.
	v2, err := encryptValue(key, "legacy")
	require.NoError(t, err)
	got, err = decryptValueFor(key, v2, "userB")
	require.NoError(t, err)
	require.Equal(t, "legacy", got)

	// The cross-implementation vc2 vector keeps working through the new path.
	got, err = DecryptValueFor(deriveKey("test-key"), "vc2:AAECAwQFBgcICQoLOY8X/pUZ4LVnmsNxo2f1Y8NHKaeqONMAhR0t8Hgk5/p5tAAZWLM=", "anyone")
	require.NoError(t, err)
	require.Equal(t, "Vectra cross-impl test", got)

	_, err = encryptValueFor(key, "x", "")
	require.Error(t, err, "vc3 needs an owner")
	_, err = DecryptValueFor(key, "plaintext", "userA")
	require.Error(t, err)
}

// resetKey clears the memoised key so a test can set NEPTUN_ENCRYPTION_KEY.
func resetKey(t *testing.T, password string) {
	t.Helper()
	t.Setenv("NEPTUN_ENCRYPTION_KEY", password)
	keyOnce = sync.Once{}
	keyVal, keyErr = nil, nil
	t.Cleanup(func() {
		keyOnce = sync.Once{}
		keyVal, keyErr = nil, nil
	})
}

func callHandler(t *testing.T, app *tests.TestApp, auth *core.Record, body string) map[string]any {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/crypto", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{App: app, Auth: auth}
	e.Request = req
	e.Response = rec
	require.NoError(t, Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)
	var out map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &out))
	return out
}

// TestHandlerVC3RoundTripAndCrossUser drives the route: encrypt emits vc3 for
// the caller, the caller decrypts it, another user gets the input echoed back
// with its index in `errors` (not a 500 for the whole batch), and vc2 and
// plaintext entries in the same batch are unaffected.
func TestHandlerVC3RoundTripAndCrossUser(t *testing.T) {
	resetKey(t, "handler-key")
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
	alice := newUser("crypto-alice@example.com")
	bob := newUser("crypto-bob@example.com")

	enc := callHandler(t, app, alice, `{"op":"encrypt","values":["hello",""]}`)
	values := enc["values"].([]any)
	require.Len(t, values, 2)
	aliceCT := values[0].(string)
	require.True(t, strings.HasPrefix(aliceCT, V3Prefix), "encrypt must emit vc3")
	require.Equal(t, "", values[1], "empty strings pass through")

	key, err := encryptionKey()
	require.NoError(t, err)
	legacy, err := encryptValue(key, "old row")
	require.NoError(t, err)

	batch, _ := json.Marshal(map[string]any{"op": "decrypt", "values": []string{aliceCT, legacy, "plain"}})

	own := callHandler(t, app, alice, string(batch))
	require.Equal(t, []any{"hello", "old row", "plain"}, own["values"])
	require.NotContains(t, own, "errors")

	cross := callHandler(t, app, bob, string(batch))
	require.Equal(t, []any{aliceCT, "old row", "plain"}, cross["values"],
		"a failed entry echoes its input; the rest of the batch still decrypts")
	require.Equal(t, []any{float64(0)}, cross["errors"])
}
