package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"vectra/pb/internal/crypto"
	"vectra/pb/internal/dedupekey"
)

// Rewrites every stored transit_trackers.dedupe_key from the old global digest
// to the per-user `dk2:` digest the crypto route returns since this release,
// so existing trackers keep deduping against new adds (see internal/dedupekey).
//
// It runs inside the migration transaction and needs NEPTUN_ENCRYPTION_KEY only
// when there is a key to rewrite: then a missing key fails the migration, and
// with it `serve`, instead of leaving keys that no longer match. It must run
// with the same NEPTUN_ENCRYPTION_KEY that produced the stored keys. Nothing is
// deleted; a key that cannot be rewritten is left and logged by record id.
func init() {
	m.Register(func(app core.App) error {
		_, err := dedupekey.Upgrade(app, crypto.DigestKeyFromEnv)
		return err
	}, func(app core.App) error {
		// No-op: the old digest cannot be recovered from the new one.
		return nil
	})
}
