package migrations

import (
	"ganigeorgiev.com/pocketbase/pocketbase/core"
	m "ganigeorgiev.com/pocketbase/pocketbase/migrations"
)

// The forced-update config (see internal/versiongate): one record, superuser only. Empty
// minimums and blockLegacy off mean the gate is off until the owner fills something in.
func init() {
	m.Register(func(app core.App) error {
		col := core.NewBaseCollection("app_config")
		col.Fields.Add(
			&core.TextField{Name: "minIos"},
			&core.TextField{Name: "minAndroid"},
			&core.BoolField{Name: "blockLegacy"},
		)
		col.ListRule = nil
		col.ViewRule = nil
		col.CreateRule = nil
		col.UpdateRule = nil
		col.DeleteRule = nil
		if err := app.Save(col); err != nil {
			return err
		}
		return app.Save(core.NewRecord(col))
	}, func(app core.App) error {
		col, err := app.FindCollectionByNameOrId("app_config")
		if err != nil {
			return nil
		}
		return app.Delete(col)
	})
}
