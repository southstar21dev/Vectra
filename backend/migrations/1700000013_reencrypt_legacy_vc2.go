package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"vectra/pb/internal/crypto"
	"vectra/pb/internal/legacyvc2"
)

// Re-encrypt every stored legacy `vc2:` value as `vc3:` bound to its owner, so
// the crypto route can stop opening unbound vc2 ciphertext for any caller (see
// internal/legacyvc2 for the fields covered).
//
// It runs inside the migration transaction. It needs NEPTUN_ENCRYPTION_KEY only
// when a vc2 value exists: then a missing key fails the migration, and with it
// `serve`, instead of leaving vc2 data the route can no longer read. A value
// that does not decrypt is left as it is and logged by record id and field.
//
// If it ever ran with the wrong key (every value then fails to decrypt and is
// left in place), fix the key and delete this migration's row from
// `_migrations` (or `migrate down 1` while it is the newest) to re-arm it: the
// next `serve` re-runs it, and values already converted are not touched again.
func init() {
	m.Register(func(app core.App) error {
		_, err := legacyvc2.Upgrade(app, crypto.EncryptionKeyFromEnv)
		return err
	}, func(app core.App) error {
		// No-op: turning vc3 back into unbound vc2 would reintroduce the defect.
		return nil
	})
}
