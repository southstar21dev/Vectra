package schema

import (
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
)

// RegisterTimestamps keeps the app's custom created_at/updated_at columns in
// sync (replaces the Postgres triggers) with two layers:
//
//   - Model hooks run for every app.Save, so server-side writes (Neptun
//     replace, passkey storage, the email change route, any future route or
//     cron job) never have to remember the fields: updated_at moves on every
//     update, and a create fills created_at and updated_at when they are blank.
//     A caller that sets an explicit value on create (an import that keeps
//     the original timestamps) keeps it.
//   - Request hooks make both server-owned for API callers: a client-sent
//     created_at is overwritten on create, like updated_at on every write, and
//     created_at cannot be changed afterwards.
//
// Not every collection defines these fields — passkeys and passkey_challenges
// only track last_used_at/expires_at — so each write is guarded by a field
// lookup. Writing an unknown field would otherwise attach stray values to the
// record that the collection schema does not describe.
func RegisterTimestamps(app core.App) {
	app.OnRecordCreate().BindFunc(func(e *core.RecordEvent) error {
		now := time.Now().UTC()
		for _, name := range []string{"created_at", "updated_at"} {
			if e.Record.Collection().Fields.GetByName(name) != nil && e.Record.GetDateTime(name).IsZero() {
				e.Record.Set(name, now)
			}
		}
		return e.Next()
	})
	app.OnRecordUpdate().BindFunc(func(e *core.RecordEvent) error {
		if e.Record.Collection().Fields.GetByName("updated_at") != nil {
			e.Record.Set("updated_at", time.Now().UTC())
		}
		return e.Next()
	})
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
		// created_at is immutable: undo any client-sent value.
		if e.Record.Collection().Fields.GetByName("created_at") != nil {
			e.Record.Set("created_at", e.Record.Original().Get("created_at"))
		}
		return e.Next()
	})
}
