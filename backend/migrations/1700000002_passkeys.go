package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
	"github.com/pocketbase/pocketbase/tools/types"
)

func init() {
	m.Register(func(app core.App) error {
		users, err := app.FindCollectionByNameOrId("users")
		if err != nil {
			return err
		}

		pk := core.NewBaseCollection("passkeys")
		pk.Fields.Add(
			&core.RelationField{Name: "user", Required: true, MaxSelect: 1, CollectionId: users.Id, CascadeDelete: true},
			&core.TextField{Name: "credential_id", Required: true}, // base64url of the raw credential ID
			&core.TextField{Name: "public_key", Required: true},    // base64url COSE key bytes
			&core.TextField{Name: "attestation_type"},
			&core.TextField{Name: "aaguid"},
			&core.NumberField{Name: "sign_count", OnlyInt: true},
			&core.JSONField{Name: "transports", MaxSize: 4096},
			&core.BoolField{Name: "backup_eligible"},
			&core.BoolField{Name: "backed_up"},
			&core.TextField{Name: "friendly_name"},
			&core.DateField{Name: "last_used_at"},
		)
		pk.AddIndex("idx_passkeys_credential_id", true, "credential_id", "")
		pk.ListRule = types.Pointer("user = @request.auth.id")
		pk.ViewRule = types.Pointer("user = @request.auth.id")
		pk.CreateRule = nil // created only server-side
		pk.UpdateRule = types.Pointer("user = @request.auth.id")
		pk.DeleteRule = types.Pointer("user = @request.auth.id")

		ch := core.NewBaseCollection("passkey_challenges")
		ch.Fields.Add(
			&core.TextField{Name: "challenge_type", Required: true}, // registration | authentication
			&core.RelationField{Name: "user", MaxSelect: 1, CollectionId: users.Id, CascadeDelete: true},
			&core.JSONField{Name: "session_data", Required: true, MaxSize: 65536},
			&core.DateField{Name: "expires_at", Required: true},
		)
		ch.ListRule = nil // server-only
		ch.ViewRule = nil
		ch.CreateRule = nil
		ch.UpdateRule = nil
		ch.DeleteRule = nil

		if err := app.Save(pk); err != nil {
			return err
		}
		return app.Save(ch)
	}, func(app core.App) error {
		for _, name := range []string{"passkeys", "passkey_challenges"} {
			c, err := app.FindCollectionByNameOrId(name)
			if err == nil {
				_ = app.Delete(c)
			}
		}
		return nil
	})
}
