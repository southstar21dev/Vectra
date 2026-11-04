package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"vectra/pb/internal/schema"
)

func init() {
	m.Register(func(app core.App) error {
		// PocketBase ships a default "users" auth collection, so reuse it when
		// present (test app / existing installs) and only create it on a fresh
		// database; a bare NewAuthCollection would violate the unique-name rule.
		users, err := app.FindCollectionByNameOrId("users")
		if err != nil {
			users = core.NewAuthCollection("users")
		}
		users.Fields.Add(
			&core.TextField{Name: "full_name"},
			&core.URLField{Name: "avatar_url"},
			&core.JSONField{Name: "appearance", MaxSize: 65536},
			&core.TextField{Name: "neptun_ics_url"},
			&core.DateField{Name: "neptun_last_sync"},
			&core.JSONField{Name: "preferences", MaxSize: 65536},
			&core.TextField{Name: "legacy_id", Max: 64}, // original Supabase UUID
			&core.DateField{Name: "created_at"},
			&core.DateField{Name: "updated_at"},
		)
		// Partial unique index: PocketBase stores an unset optional TextField as
		// "" (not NULL), so a plain UNIQUE index would reject every user created
		// without a legacy_id. Uniqueness only matters for real (non-empty)
		// legacy Supabase UUIDs.
		users.AddIndex("idx_users_legacy_id", true, "legacy_id", "legacy_id != ''")
		schema.SelfRules(users)
		return app.Save(users)
	}, func(app core.App) error {
		// No-op: the "users" collection is created and owned by PocketBase's
		// core system migration (1640988000_init.go). This migration only
		// augments it (adds fields, rules and an index), so rollback must not
		// delete a core-owned collection — it would not be recreated.
		return nil
	})
}
