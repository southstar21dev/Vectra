package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// PWA-only: remove every native FCM push subscription row. The Android APK is
// gone, so transport='fcm' rows can never receive a push again — they only
// inflate send totals and dedup scans. Web Push rows are untouched.
func init() {
	m.Register(func(app core.App) error {
		for {
			records, err := app.FindRecordsByFilter("push_subscriptions", "transport = 'fcm'", "", 200, 0)
			if err != nil {
				return err
			}
			if len(records) == 0 {
				return nil
			}
			for _, rec := range records {
				if err := app.Delete(rec); err != nil {
					return err
				}
			}
		}
	}, func(app core.App) error {
		// No-op: deleted device tokens cannot be restored.
		return nil
	})
}
