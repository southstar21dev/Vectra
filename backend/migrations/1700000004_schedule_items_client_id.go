package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Adds the columns/indexes that the client rewrite needs but that were
// originally (incorrectly) folded into the already-applied data-collections
// migration (1700000001). PocketBase records applied migrations by file name
// only and never re-runs an edited one, so a persistent pb_data that already
// applied 1700000001 would lack them. This migration adds them separately and
// is idempotent, so it is safe on both fresh and already-migrated databases.
//
//   - schedule_items.client_id: the app generates its own UUID for every
//     schedule item and keys its offline mutation queue on it; PocketBase's
//     15-char record id cannot carry that UUID. Required + non-partial unique
//     mirrors the old Supabase primary-key semantics: every item has a
//     client_id, and an unset ("") value must not be allowed to collide.
//   - idx_course_tracker_data_user: course_tracker_data is a singleton per
//     user, and PocketBase has no upsert. A unique index on `user` makes that
//     guarantee structural, so a write race can never leave two rows for one
//     user.
func init() {
	m.Register(func(app core.App) error {
		si, err := app.FindCollectionByNameOrId("schedule_items")
		if err != nil {
			return err
		}
		if si.Fields.GetByName("client_id") == nil {
			si.Fields.Add(&core.TextField{Name: "client_id", Required: true})
		}
		if si.GetIndex("idx_schedule_items_client_id") == "" {
			si.AddIndex("idx_schedule_items_client_id", true, "client_id", "")
		}
		if err := app.Save(si); err != nil {
			return err
		}

		ct, err := app.FindCollectionByNameOrId("course_tracker_data")
		if err != nil {
			return err
		}
		if ct.GetIndex("idx_course_tracker_data_user") == "" {
			ct.AddIndex("idx_course_tracker_data_user", true, "user", "")
		}
		return app.Save(ct)
	}, func(app core.App) error {
		si, err := app.FindCollectionByNameOrId("schedule_items")
		if err == nil {
			si.RemoveIndex("idx_schedule_items_client_id")
			si.Fields.RemoveByName("client_id")
			if err := app.Save(si); err != nil {
				return err
			}
		}

		ct, err := app.FindCollectionByNameOrId("course_tracker_data")
		if err == nil {
			ct.RemoveIndex("idx_course_tracker_data_user")
			if err := app.Save(ct); err != nil {
				return err
			}
		}
		return nil
	})
}
