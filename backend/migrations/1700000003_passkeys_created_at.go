package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Adds the custom created_at column to the passkeys collection. It was missing
// from the original passkeys migration (1700000002), but the passkey list
// response contract requires created_at, so it is added here rather than by
// editing the already-applied migration (PocketBase tracks applied migrations
// by file name only and would skip the edit on existing databases).
//
// This is a custom created_at DateField, not PocketBase's autodate `created`,
// to follow the app-wide created_at/updated_at convention. Passkeys are only
// created server-side (StoreCredential), so the value is set there rather than
// by the request-path timestamp hook.
func init() {
	m.Register(func(app core.App) error {
		col, err := app.FindCollectionByNameOrId("passkeys")
		if err != nil {
			return err
		}
		if col.Fields.GetByName("created_at") == nil {
			col.Fields.Add(&core.DateField{Name: "created_at"})
		}
		return app.Save(col)
	}, func(app core.App) error {
		col, err := app.FindCollectionByNameOrId("passkeys")
		if err != nil {
			return err
		}
		col.Fields.RemoveByName("created_at")
		return app.Save(col)
	})
}
