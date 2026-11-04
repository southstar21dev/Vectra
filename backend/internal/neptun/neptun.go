package neptun

import (
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/ratelimit"
)

// Port of the Supabase RPC replace_neptun_schedule(p_user_id, p_events).
//
// The client syncs its whole Neptun calendar at once; the server atomically
// replaces the user's stored set (delete + insert in one transaction) so a
// mid-sync failure can never leave the calendar empty or duplicated. Every
// event content field arrives already encrypted with the app's vc2: scheme and
// is stored verbatim — the server never decrypts or re-encrypts.

const (
	defaultColor     = "#3B82F6"
	defaultEventType = "class"

	// maxEvents caps one replace call. A real Neptun calendar is a few dozen
	// to a couple hundred rows; without a cap one request could insert an
	// unbounded number of records inside a single write transaction.
	maxEvents = 500

	// replacesPerWindow caps replace calls per user per replaceWindow. The
	// client syncs on demand and on login, so a handful per minute is ample;
	// each call rewrites the user's whole calendar.
	replacesPerWindow = 10
	replaceWindow     = time.Minute
)

// limiter is the per-user limiter for Handler. It is a var so tests can
// substitute a fresh one.
var limiter = ratelimit.New(replacesPerWindow, replaceWindow)

// verbatimFields are the client-supplied content fields copied as-is into a
// row. Their values are ciphertext (or, for color, display metadata) and must
// not be interpreted by the server. semester_start/semester_end are included
// because the client encrypts them as vc2: ciphertext too; typing them as
// dates would silently discard that ciphertext.
var verbatimFields = []string{
	"course_name", "course_code", "instructor", "location",
	"day_of_week", "start_time", "end_time",
	"semester_start", "semester_end",
}

// Replace atomically replaces userID's entire Neptun schedule: it deletes every
// existing neptun_schedule_events row for the user, then inserts one row per
// provided event, all inside a single transaction. The owner is always the
// passed userID — callers must derive it from the authenticated request, never
// from client input.
func Replace(app core.App, userID string, events []map[string]any) error {
	return app.RunInTransaction(func(txApp core.App) error {
		if err := deleteForUser(txApp, userID); err != nil {
			return err
		}
		return insertEvents(txApp, userID, events)
	})
}

// deleteForUser removes all of userID's existing schedule rows.
func deleteForUser(app core.App, userID string) error {
	records, err := app.FindRecordsByFilter(
		"neptun_schedule_events", "user = {:u}", "", 0, 0, dbx.Params{"u": userID},
	)
	if err != nil {
		return err
	}
	for _, r := range records {
		if err := app.Delete(r); err != nil {
			return err
		}
	}
	return nil
}

// insertEvents stores one row per event, mirroring the RPC's column mapping:
// content fields verbatim (including the encrypted semester bounds), and
// color/event_type COALESCE defaults.
func insertEvents(app core.App, userID string, events []map[string]any) error {
	collection, err := app.FindCollectionByNameOrId("neptun_schedule_events")
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	for _, ev := range events {
		rec := core.NewRecord(collection)
		rec.Set("user", userID)
		for _, field := range verbatimFields {
			if v, ok := ev[field]; ok && v != nil {
				rec.Set(field, v)
			}
		}
		rec.Set("color", valueOrDefault(ev, "color", defaultColor))
		rec.Set("semester", valueOrDefault(ev, "semester", ""))
		rec.Set("event_type", valueOrDefault(ev, "event_type", defaultEventType))
		// The Postgres columns default to now(); direct app.Save calls bypass
		// the request-scoped timestamp hook, so set them explicitly.
		rec.Set("created_at", now)
		rec.Set("updated_at", now)
		if err := app.Save(rec); err != nil {
			return err
		}
	}
	return nil
}

// valueOrDefault mirrors COALESCE(elem->>'key', def): the default applies only
// when the key is absent or JSON null; an explicitly empty string is preserved.
func valueOrDefault(ev map[string]any, key, def string) string {
	v, ok := ev[key]
	if !ok || v == nil {
		return def
	}
	if s, ok := v.(string); ok {
		return s
	}
	return def
}

// Handler implements POST /api/vectra/neptun/replace (JWT-gated). It reads
// {events: [...]} and replaces the caller's own calendar. The owner is always
// e.Auth.Id; any user id in the body is ignored.
func Handler(e *core.RequestEvent) error {
	if e.Auth == nil || e.Auth.Id == "" {
		return e.UnauthorizedError("Authentication required", nil)
	}
	if !limiter.Allow(e.Auth.Id, time.Now()) {
		return e.TooManyRequestsError("Too many calendar syncs. Please wait a moment and try again.", nil)
	}
	var body struct {
		Events []map[string]any `json:"events"`
	}
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if len(body.Events) > maxEvents {
		return e.BadRequestError("Too many events in one sync", nil)
	}
	if err := Replace(e.App, e.Auth.Id, body.Events); err != nil {
		return e.InternalServerError("Failed to replace neptun schedule", err)
	}
	return e.JSON(200, map[string]any{"replaced": len(body.Events)})
}
