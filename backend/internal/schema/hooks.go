package schema

import (
	"time"

	"github.com/pocketbase/pocketbase/core"
)

// RegisterTimestamps keeps the app's custom created_at/updated_at columns in
// sync for API-created and API-updated records (replaces the Postgres triggers).
// Both are server-owned: any client-sent created_at is overwritten on create,
// like updated_at on every write. (Imports via app.Save, e.g. the Supabase
// migrator, do not pass through these request hooks and keep their values.)
//
// Not every collection defines these fields — passkeys and passkey_challenges
// only track last_used_at/expires_at — so each write is guarded by a field
// lookup. Writing an unknown field would otherwise attach stray values to the
// record that the collection schema does not describe.
func RegisterTimestamps(app core.App) {
	app.OnRecordCreateRequest().BindFunc(func(e *core.RecordRequestEvent) error {
		now := time.Now().UTC()
		if e.Record.Collection().Fields.GetByName("created_at") != nil {
			e.Record.Set("created_at", now)
		}
		if e.Record.Collection().Fields.GetByName("updated_at") != nil {
			e.Record.Set("updated_at", now)
		}
		return e.Next()
	})
	app.OnRecordUpdateRequest().BindFunc(func(e *core.RecordRequestEvent) error {
		if e.Record.Collection().Fields.GetByName("updated_at") != nil {
			e.Record.Set("updated_at", time.Now().UTC())
		}
		return e.Next()
	})
}
