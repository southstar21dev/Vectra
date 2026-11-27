package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"golang.org/x/crypto/pbkdf2"

	"vectra/pb/internal/limits"
	"vectra/pb/internal/ratelimit"
)

const (
	// v3Prefix marks the user-bound format: AES-256-GCM under the server key, but the owning user's id is the GCM additional authenticated
	// data. A vc3 value copied into another account's rows therefore fails to
	// decrypt there, so one user cannot use the shared server key to read
	// another user's ciphertext through the crypto route.
	v3Prefix = "vc3:"
	salt     = "vectra-aes-gcm-v1"
	iter     = 100_000
	keyLen   = 32

	// DigestPrefix marks a per-user keyed digest (see bindDigest).
	DigestPrefix = "dk2:"
	// digestKeyInfo separates the digest subkey from the encryption key.
	digestKeyInfo = "vectra-dedupe-digest-v2"
	// minKeyLen is the length below which a warning is logged at first use.
	// The key is not rejected: the production key's length is not known here,
	// and refusing it would stop the server.
	minKeyLen = 32
)

var (
	keyOnce     sync.Once
	keyVal      []byte
	keyErr      error
	keyPassword string
	digestKey   []byte
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
		if len(password) < minKeyLen {
			log.Printf("crypto: NEPTUN_ENCRYPTION_KEY is shorter than %d characters; use a long random value (openssl rand -hex 32)", minKeyLen)
		}
		keyPassword = password
		keyVal = deriveKey(password)
		digestKey = deriveDigestKey(keyVal)
	})
	return keyVal, keyErr
}

// deriveDigestKey derives the HMAC key for digests from the PBKDF2 output, so
// every offline guess at NEPTUN_ENCRYPTION_KEY costs the full key derivation.
func deriveDigestKey(key []byte) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(digestKeyInfo))
	return mac.Sum(nil)
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

// encryptValueFor produces a `vc3:` ciphertext bound to userID.
func encryptValueFor(key []byte, plain, userID string) (string, error) {
	if userID == "" {
		return "", errors.New("crypto: vc3 encryption requires a user id")
	}
	return seal(key, v3Prefix, plain, []byte(userID))
}

// decryptValueFor opens a `vc3:` ciphertext for userID, failing for any other
// user. Anything that is not a vc3 value is refused (legacy unbound vc2 values
// included; migration 1700000013 fails if a database still holds any).
func decryptValueFor(key []byte, value, userID string) (string, error) {
	switch {
	case strings.HasPrefix(value, v3Prefix):
		if userID == "" {
			return "", errors.New("crypto: vc3 decryption requires a user id")
		}
		return unseal(key, value[len(v3Prefix):], []byte(userID))
	default:
		return "", fmt.Errorf("crypto: value is not a %q ciphertext", v3Prefix)
	}
}

// isCiphertext reports whether value carries the ciphertext prefix.
func isCiphertext(value string) bool {
	return strings.HasPrefix(value, v3Prefix)
}

// digestValue is the inner digest, SHA256(password | part | part ...), the
// value the route returned before the per-user dk2 digest existed. It never
// leaves the server any more, but it must never change: every stored
// transit_trackers.dedupe_key is HMAC(bindDigest) of this exact value, so
// altering it (the hash, the separator, the escaping, or the key it starts
// from, which is the raw NEPTUN_ENCRYPTION_KEY and not the derived key) would
// give every existing tracker a new key, and the same tracker could be added
// twice until all keys were rewritten. That is why the raw key stays in memory
// (keyPassword) for the life of the process. Changing the scheme needs a full
// rekey migration of dedupe_key, not a code edit. A `|` or `\` inside a part is
// escaped first, so different part lists do not collide (["a|b"] used to equal
// ["a","b"]); parts without either character hash as they always did.
func digestValue(password string, parts []string) string {
	h := sha256.New()
	io.WriteString(h, password)
	for _, p := range parts {
		io.WriteString(h, "|")
		io.WriteString(h, digestEscaper.Replace(p))
	}
	return hex.EncodeToString(h.Sum(nil))
}

var digestEscaper = strings.NewReplacer(`\`, `\\`, "|", `\|`)

// bindDigest turns an inner digest into the per-user value the route returns:
// "dk2:" + hex(HMAC-SHA256(digestKey, len(userID) userID len(inner) inner)),
// with each length as 8 big-endian bytes.
func bindDigest(dkey []byte, userID, inner string) string {
	mac := hmac.New(sha256.New, dkey)
	for _, v := range []string{userID, inner} {
		var n [8]byte
		binary.BigEndian.PutUint64(n[:], uint64(len(v)))
		mac.Write(n[:])
		mac.Write([]byte(v))
	}
	return DigestPrefix + hex.EncodeToString(mac.Sum(nil))
}

// ---------------------------------------------------------------------------
// Exported helpers
//
// The /api/vectra/crypto route is the only place these primitives are used at
// runtime; the helpers below expose the same code path to tests and the
// migrations without changing the route's behaviour.
// ---------------------------------------------------------------------------

// V3Prefix marks an AES-256-GCM ciphertext bound to its owning user's id.
const V3Prefix = v3Prefix

// DecryptValueFor decrypts a `vc3:` ciphertext owned by userID, exactly as the
// route does. Anything that is not a vc3 value is rejected.
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

// Batch limits. Older clients (the deprecated web client and older app
// versions: shrink these caps once both are retired) send a whole collection in
// one decrypt (decryptAll), so the per-request caps sit far above any real account's data;
// current clients split their batches well below them.
//
// Cost: every value or part list costs one budget unit, plus one more for each
// CostUnitBytes it holds beyond the first, so a big blob cannot ride along as
// "one value". A value of up to CostUnitBytes (every field except the course
// tracker document) still costs exactly one unit.
//
// MaxValueBytes bounds one value or part. The largest legitimate value is the
// course tracker document, capped at 1,000,000 characters of ciphertext by
// migration 1700000016 (its plaintext is smaller), so 1 MiB covers both the
// encrypt and the decrypt direction.
const (
	MaxValues       = 20_000
	MaxPartLists    = 2_000
	MaxPartsPerList = 16
	MaxBatchBytes   = 16 << 20
	MaxValueBytes   = 1 << 20
	CostUnitBytes   = 4096
)

// UserLimiter holds each user's budget of values per window
// (limits.CryptoValues). It is a var so tests can substitute a fresh one.
var UserLimiter = ratelimit.New(limits.CryptoValues.Max, limits.CryptoValues.Window)

// unitCost is the budget cost of size bytes: at least one unit, one more per
// CostUnitBytes started.
func unitCost(size int) int {
	return max(1, (size+CostUnitBytes-1)/CostUnitBytes)
}

// batchCost checks body against the caps and returns its cost in budget
// units, or a non-empty reason when it is too large.
func batchCost(body request) (int, string) {
	if len(body.Values) > MaxValues {
		return 0, fmt.Sprintf("Too many values in one request (at most %d)", MaxValues)
	}
	if len(body.Parts) > MaxPartLists {
		return 0, fmt.Sprintf("Too many digests in one request (at most %d)", MaxPartLists)
	}
	size, cost := 0, 0
	for _, v := range body.Values {
		if len(v) > MaxValueBytes {
			return 0, fmt.Sprintf("Value too large (at most %d bytes)", MaxValueBytes)
		}
		size += len(v)
		cost += unitCost(len(v))
	}
	for _, p := range body.Parts {
		if len(p) > MaxPartsPerList {
			return 0, fmt.Sprintf("Too many parts in one digest (at most %d)", MaxPartsPerList)
		}
		list := 0
		for _, v := range p {
			if len(v) > MaxValueBytes {
				return 0, fmt.Sprintf("Value too large (at most %d bytes)", MaxValueBytes)
			}
			list += len(v)
		}
		size += list
		cost += unitCost(list)
	}
	if size > MaxBatchBytes {
		return 0, fmt.Sprintf("Request too large (at most %d bytes of values)", MaxBatchBytes)
	}
	return max(1, cost), ""
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
	cost, tooLarge := batchCost(body)
	if tooLarge != "" {
		return e.Error(http.StatusRequestEntityTooLarge, tooLarge, nil)
	}
	if !UserLimiter.AllowN(e.Auth.Id, cost, time.Now()) {
		return e.TooManyRequestsError("Too many crypto requests. Please wait a while and try again.", nil)
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
		// Keyed per user: the same parts give different digests in different
		// accounts, and the response is no fast oracle on the server key.
		digests := make([]string, len(body.Parts))
		for i, p := range body.Parts {
			digests[i] = bindDigest(digestKey, e.Auth.Id, digestValue(keyPassword, p))
		}
		return e.JSON(200, map[string]any{"digests": digests})
	default:
		return e.BadRequestError("Unknown op, expected encrypt, decrypt or digest", nil)
	}
}
