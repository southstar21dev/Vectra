package migrations

import (
	"fmt"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	m "ganigeorgiev.com/pocketbase/pocketbase/migrations"
)

// This migration used to rewrite every stored transit_trackers.dedupe_key from
// the old global digest to the per-user `dk2:` digest. Databases from before
// 2026-11-10 can no longer be restored into this server: the rewrite code is
// gone, so the migration only checks that every key already starts with `dk2:`
// and fails (and with it `serve`) otherwise. A fresh database and any database
// migrated since 2026-11-10 pass.
//
// To bring an older backup forward, run it once with a release from before
// this change (git history has internal/dedupekey), then use the result.
func init() {
	m.Register(func(app core.App) error {
		var n int
		err := app.DB().NewQuery(
			"SELECT count(*) FROM {{transit_trackers}} WHERE substr([[dedupe_key]], 1, 4) != 'dk2:'",
		).Row(&n)
		if err != nil {
			return fmt.Errorf("migration 1700000014: scan transit_trackers: %w", err)
		}
		if n > 0 {
			return fmt.Errorf("migration 1700000014: %d transit_trackers row(s) still have an old-format dedupe_key; "+
				"restoring backups from before 2026-11-10 is no longer supported, upgrade it with an older release first", n)
		}
		return nil
	}, func(app core.App) error {
		return nil
	})
}
