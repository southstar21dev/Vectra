package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"vectra/pb/internal/schema"
)

// Re-applies the tightened API rules to collections created by earlier
// migrations (PocketBase never re-runs an applied migration, so editing
// schema.OwnerRules/SelfRules alone would only affect fresh databases):
//
//   - owner collections: the update rule now also pins the `user` relation,
//     so an owner cannot reassign a record to another account;
//   - users: legacy_id (the migrated WebAuthn user handle) and created_at can
//     no longer be written by clients;
//   - passkeys: no client updates at all. The update rule let an owner PATCH
//     user/public_key/credential_id/sign_count, i.e. graft an attacker-held
//     key onto their row or move a row to another account. The app never
//     edits passkeys through the records API (rename/delete go through
//     /api/vectra/passkeys), so the rule becomes superuser-only.
func init() {
	ownerCollections := []string{
		"schedule_events", "neptun_schedule_events", "schedule_items",
		"transit_trackers", "course_tracker_data", "trip_plans", "push_subscriptions",
	}

	m.Register(func(app core.App) error {
		for _, name := range ownerCollections {
			col, err := app.FindCollectionByNameOrId(name)
			if err != nil {
				return err
			}
			schema.OwnerRules(col)
			if err := app.Save(col); err != nil {
				return err
			}
		}

		users, err := app.FindCollectionByNameOrId("users")
		if err != nil {
			return err
		}
		schema.SelfRules(users)
		if err := app.Save(users); err != nil {
			return err
		}

		pk, err := app.FindCollectionByNameOrId("passkeys")
		if err != nil {
			return err
		}
		pk.UpdateRule = nil
		return app.Save(pk)
	}, func(app core.App) error {
		// No-op: rolling back would reopen the ownership/passkey holes.
		return nil
	})
}
