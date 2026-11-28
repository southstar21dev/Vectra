package migrations

import (
	"fmt"
	"sort"
	"strings"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	m "ganigeorgiev.com/pocketbase/pocketbase/migrations"
)

// This migration used to re-encrypt every stored legacy `vc2:` value as `vc3:`
// bound to its owner. Databases from before 2026-11-10 can no longer be
// restored into this server: the upgrade code is gone, so the migration only
// checks that no `vc2:` value exists and fails (and with it `serve`) when one
// does. A fresh database and any database migrated since 2026-11-10 pass.
//
// To bring an older backup forward, run it once with a release from before
// this change (git history has internal/legacyvc2), then use the result.
//
// The scan covers every text, JSON and editor field of every non-system base
// or auth collection, plus the users collection. substr() compares
// case-sensitively, so only a lowercase `vc2:` counts; JSON fields hold the
// value as a JSON string, so a leading quote is allowed too.
func init() {
	m.Register(func(app core.App) error {
		found, err := findVC2(app)
		if err != nil {
			return err
		}
		if len(found) > 0 {
			return fmt.Errorf("migration 1700000013: this database still holds legacy vc2: values (%s); "+
				"restoring backups from before 2026-11-10 is no longer supported, upgrade it with an older release first",
				strings.Join(found, ", "))
		}
		return nil
	}, func(app core.App) error {
		return nil
	})
}

// findVC2 lists "collection.field (n)" for every field that holds a value
// starting with `vc2:`.
func findVC2(app core.App) ([]string, error) {
	collections, err := app.FindAllCollections(core.CollectionTypeBase, core.CollectionTypeAuth)
	if err != nil {
		return nil, err
	}
	var found []string
	for _, col := range collections {
		if col.System && col.Name != "users" {
			continue // PocketBase's own collections never hold app ciphertext
		}
		for _, field := range col.Fields {
			switch field.(type) {
			case *core.TextField, *core.EditorField, *core.JSONField:
			default:
				continue
			}
			name := field.GetName()
			var n int
			err := app.DB().NewQuery(
				"SELECT count(*) FROM {{" + col.Name + "}} WHERE substr([[" + name + "]], 1, 4) = 'vc2:'" +
					" OR substr([[" + name + "]], 1, 5) = '\"vc2:'",
			).Row(&n)
			if err != nil {
				return nil, fmt.Errorf("migration 1700000013: scan %s.%s: %w", col.Name, name, err)
			}
			if n > 0 {
				found = append(found, fmt.Sprintf("%s.%s (%d)", col.Name, name, n))
			}
		}
	}
	sort.Strings(found)
	return found, nil
}
