package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Encrypted-at-rest blobs (vc2:) are larger than the plaintext they wrap, and can
// exceed the TextField default Max of 5000 characters (PocketBase applies 5000
// when Max is 0 — see core/field_text.go). Raise the limit on the fields that
// store whole encrypted JSON blobs, so importing/writing large trackers and
// schedule items succeeds.
func init() {
	const blobMax = 1_000_000 // ~1 MB of characters; ample for any single blob

	m.Register(func(app core.App) error {
		targets := []struct{ collection, field string }{
			{"course_tracker_data", "data"},
			{"schedule_items", "payload"},
		}
		for _, t := range targets {
			col, err := app.FindCollectionByNameOrId(t.collection)
			if err != nil {
				return err
			}
			f, ok := col.Fields.GetByName(t.field).(*core.TextField)
			if !ok || f.Max >= blobMax {
				continue
			}
			f.Max = blobMax
			if err := app.Save(col); err != nil {
				return err
			}
		}
		return nil
	}, func(app core.App) error {
		// No-op: leave the enlarged limits in place on rollback.
		return nil
	})
}
