package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Scopes schedule_items.client_id uniqueness to its owner. The global unique
// index from 1700000004 let any user probe for (or squat on) another user's
// client UUID: a create with a colliding client_id fails with a uniqueness
// error even though the other row is invisible to the caller. The client only
// ever resolves rows by `user = {:u} && client_id = {:c}`, so (user, client_id)
// is the real key — the same shape as transit_trackers' (user, dedupe_key).
//
// Idempotent: the old index is dropped and the composite one added only when
// needed.
func init() {
	const (
		oldIndex = "idx_schedule_items_client_id"
		newIndex = "idx_schedule_items_user_client_id"
	)

	m.Register(func(app core.App) error {
		si, err := app.FindCollectionByNameOrId("schedule_items")
		if err != nil {
			return err
		}
		si.RemoveIndex(oldIndex)
		if si.GetIndex(newIndex) == "" {
			si.AddIndex(newIndex, true, "user, client_id", "")
		}
		return app.Save(si)
	}, func(app core.App) error {
		si, err := app.FindCollectionByNameOrId("schedule_items")
		if err != nil {
			return nil
		}
		si.RemoveIndex(newIndex)
		if si.GetIndex(oldIndex) == "" {
			si.AddIndex(oldIndex, true, "client_id", "")
		}
		return app.Save(si)
	})
}
