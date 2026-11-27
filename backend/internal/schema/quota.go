package schema

import (
	"ganigeorgiev.com/pocketbase/dbx"
	"ganigeorgiev.com/pocketbase/pocketbase/core"
)

// RowQuotas caps how many rows one user may own per collection. The limits
// are far above what the app really stores, so they only stop a free account
// from filling the server disk through the records API (blob fields are capped
// per row by migration 1700000016). push_subscriptions has its own cap in the push package.
var RowQuotas = map[string]int{
	"schedule_events":        5000,
	"neptun_schedule_events": 5000,
	"schedule_items":         5000,
	"transit_trackers":       200,
	"course_tracker_data":    500,
	"trip_plans":             100, // web-only: remove with web/
}

// RegisterRowQuotas refuses record creation through the API once the caller
// already owns the collection's quota of rows. Server-side writes (for example
// the Neptun replace route) go through app.Save and are bounded by their own
// limits, so they do not pass this hook.
func RegisterRowQuotas(app core.App) {
	app.OnRecordCreateRequest().BindFunc(func(e *core.RecordRequestEvent) error {
		limit, ok := RowQuotas[e.Record.Collection().Name]
		if !ok {
			return e.Next()
		}
		owner := e.Record.GetString("user")
		if owner == "" {
			return e.Next()
		}
		var count int
		err := e.App.DB().
			Select("count(*)").
			From(e.Record.Collection().Name).
			Where(dbx.HashExp{"user": owner}).
			Row(&count)
		if err != nil {
			return e.InternalServerError("Could not check the row quota", err)
		}
		if count >= limit {
			return e.BadRequestError("Storage limit reached for this collection", nil)
		}
		return e.Next()
	})
}
