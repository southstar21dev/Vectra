package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"

	"github.com/pocketbase/pocketbase/core"
	"golang.org/x/crypto/pbkdf2"
)

const (
	v2Prefix = "vc2:"
	// v3Prefix marks the user-bound format: the same AES-256-GCM scheme and
	// key as vc2, but the owning user's id is the GCM additional authenticated
	// data. A vc3 value copied into another account's rows therefore fails to
	// decrypt there, so one user cannot use the shared server key to read
	// another user's ciphertext through the crypto route.
	v3Prefix = "vc3:"
	salt     = "vectra-aes-gcm-v1"
	iter     = 100_000
	keyLen   = 32
)

var (
	keyOnce sync.Once
	keyVal  []byte
	keyErr  error
)

func deriveKey(password string) []byte {
	return pbkdf2.Key([]byte(password), []byte(salt), iter, keyLen, sha256.New)
}

func encryptionKey() ([]byte, error) {
	keyOnce.Do(func() {
		password := os.Getenv("NEPTUN_ENCRYPTION_KEY")
		if password == "" {
			keyErr = errors.New("NEPTUN_ENCRYPTION_KEY is not set")
			return
		}
		keyVal = deriveKey(password)
	})
	return keyVal, keyErr
}

func seal(key []byte, prefix, plain string, aad []byte) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	iv := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return "", err
	}
	ct := gcm.Seal(nil, iv, []byte(plain), aad)
	combined := append(iv, ct...)
	return prefix + base64.StdEncoding.EncodeToString(combined), nil
}

func unseal(key []byte, body string, aad []byte) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(body)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	n := gcm.NonceSize()
	if len(raw) < n {
		return "", errors.New("ciphertext too short")
	}
	pt, err := gcm.Open(nil, raw[:n], raw[n:], aad)
	if err != nil {
		return "", err
	}
	return string(pt), nil
}

// encryptValue produces a legacy, unbound `vc2:` ciphertext. The route no
// longer emits or accepts it; it remains for fixtures and the
// cross-implementation vector.
func encryptValue(key []byte, plain string) (string, error) {
	return seal(key, v2Prefix, plain, nil)
}

// decryptValue opens a `vc2:` ciphertext (no AAD). Only the one-off vc2 to vc3
// upgrade (internal/legacyvc2) and the importer's verifier use it, through
// DecryptValue; the route never does.
func decryptValue(key []byte, value string) (string, error) {
	return unseal(key, value[len(v2Prefix):], nil)
}

// encryptValueFor produces a `vc3:` ciphertext bound to userID.
func encryptValueFor(key []byte, plain, userID string) (string, error) {
	if userID == "" {
		return "", errors.New("crypto: vc3 encryption requires a user id")
	}
	return seal(key, v3Prefix, plain, []byte(userID))
}

// decryptValueFor opens a `vc3:` ciphertext for userID, failing for any other
// user. Legacy `vc2:` values are refused: they are not bound to an owner, so
// opening them here would let any account read another account's vc2 blob.
// Migration 1700000013 re-encrypts stored vc2 values to vc3.
func decryptValueFor(key []byte, value, userID string) (string, error) {
	switch {
	case strings.HasPrefix(value, v3Prefix):
		if userID == "" {
			return "", errors.New("crypto: vc3 decryption requires a user id")
		}
		return unseal(key, value[len(v3Prefix):], []byte(userID))
	case strings.HasPrefix(value, v2Prefix):
		return "", fmt.Errorf("crypto: legacy %q ciphertext is no longer accepted", v2Prefix)
	default:
		return "", fmt.Errorf("crypto: value is not a %q ciphertext", v3Prefix)
	}
}

// isCiphertext reports whether value carries one of the ciphertext prefixes.
// vc2 stays in the list so the route reports a vc2 value as a failed entry
// (echoed, index in `errors`) instead of passing it through as plaintext.
func isCiphertext(value string) bool {
	return strings.HasPrefix(value, v2Prefix) || strings.HasPrefix(value, v3Prefix)
}

func digestValue(password string, parts []string) string {
	h := sha256.New()
	io.WriteString(h, password)
	for _, p := range parts {
		io.WriteString(h, "|")
		io.WriteString(h, p)
	}
	return hex.EncodeToString(h.Sum(nil))
}

// ---------------------------------------------------------------------------
// Exported helpers
//
// The /api/vectra/crypto route is the only place these primitives are used at
// runtime, but the migration verifier (internal/migrate) needs to decrypt
// stored ciphertext through the *exact* same code path. These thin wrappers
// expose that path without changing the route's behaviour.
// ---------------------------------------------------------------------------

// V2Prefix marks a legacy, unbound AES-256-GCM ciphertext (the format the
// Supabase-era web client's Web Crypto implementation produced).
const V2Prefix = v2Prefix

// V3Prefix marks an AES-256-GCM ciphertext bound to its owning user's id.
const V3Prefix = v3Prefix

// EncryptionKeyFromEnv derives the AES-256-GCM key from NEPTUN_ENCRYPTION_KEY.
// It is the same key the crypto route uses (memoised exactly like the route).
func EncryptionKeyFromEnv() ([]byte, error) {
	return encryptionKey()
}

// DecryptValue decrypts a single legacy `vc2:` ciphertext with key. A value
// without the `vc2:` prefix is rejected rather than mis-sliced. The route no
// longer opens vc2; this stays for the vc2 to vc3 upgrade (internal/legacyvc2)
// and the importer's verifier.
func DecryptValue(key []byte, value string) (string, error) {
	if len(value) < len(V2Prefix) || value[:len(V2Prefix)] != V2Prefix {
		return "", fmt.Errorf("crypto: value is not a %q ciphertext", V2Prefix)
	}
	return decryptValue(key, value)
}

// EncryptValue encrypts plain with key, producing a `vc2:` ciphertext. Exposed
// alongside DecryptValue so callers can build fixtures through the same path.
func EncryptValue(key []byte, plain string) (string, error) {
	return encryptValue(key, plain)
}

// DecryptValueFor decrypts a `vc3:` ciphertext owned by userID, exactly as the
// route does. Legacy `vc2:` values and anything else are rejected; use
// DecryptValue for vc2.
func DecryptValueFor(key []byte, value, userID string) (string, error) {
	return decryptValueFor(key, value, userID)
}

// EncryptValueFor encrypts plain with key, producing a `vc3:` ciphertext bound
// to userID.
func EncryptValueFor(key []byte, plain, userID string) (string, error) {
	return encryptValueFor(key, plain, userID)
}

type request struct {
	Op     string     `json:"op"`
	Values []string   `json:"values"`
	Parts  [][]string `json:"parts"`
}

// Handler implements POST /api/vectra/crypto (JWT-gated).
//
// encrypt emits `vc3:` ciphertext bound to the caller (e.Auth.Id). decrypt
// opens the caller's own `vc3:` values and passes anything without a
// ciphertext prefix through unchanged. A value that fails to decrypt (another
// user's vc3, a legacy unbound vc2, a corrupted blob) does not fail the batch:
// its slot in `values` echoes the original input, so the client's existing
// "still starts with vc2:/vc3:" guard sees the failure, and its index is
// listed in `errors`.
func Handler(e *core.RequestEvent) error {
	if e.Auth == nil || e.Auth.Id == "" {
		return e.UnauthorizedError("Authentication required", nil)
	}
	key, err := encryptionKey()
	if err != nil {
		return e.InternalServerError("Crypto is not configured", err)
	}
	var body request
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	switch body.Op {
	case "encrypt":
		out := make([]string, len(body.Values))
		for i, v := range body.Values {
			if v == "" {
				out[i] = v
				continue
			}
			enc, err := encryptValueFor(key, v, e.Auth.Id)
			if err != nil {
				return e.InternalServerError("Encrypt failed", err)
			}
			out[i] = enc
		}
		return e.JSON(200, map[string]any{"values": out})
	case "decrypt":
		out := make([]string, len(body.Values))
		failed := []int{}
		for i, v := range body.Values {
			if !isCiphertext(v) {
				out[i] = v
				continue
			}
			dec, err := decryptValueFor(key, v, e.Auth.Id)
			if err != nil {
				out[i] = v
				failed = append(failed, i)
				continue
			}
			out[i] = dec
		}
		resp := map[string]any{"values": out}
		if len(failed) > 0 {
			resp["errors"] = failed
		}
		return e.JSON(200, resp)
	case "digest":
		digests := make([]string, len(body.Parts))
		for i, p := range body.Parts {
			digests[i] = digestValue(os.Getenv("NEPTUN_ENCRYPTION_KEY"), p)
		}
		return e.JSON(200, map[string]any{"digests": digests})
	default:
		return e.BadRequestError("Unknown op — expected encrypt, decrypt or digest", nil)
	}
}
