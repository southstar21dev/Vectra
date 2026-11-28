package migrations

import (
	"ganigeorgiev.com/pocketbase/pocketbase/core"
	m "ganigeorgiev.com/pocketbase/pocketbase/migrations"
)

// Per-record size limits on the blob fields, so a free account cannot fill the
// disk with a few thousand 1 MB rows (the row quotas in internal/schema bound
// the count, these bound the size). Migration 1700000005 raised the text blobs
// to 1,000,000 characters and migration 1700000001 gave the JSON blobs 1 MiB,
// far beyond anything the apps write.
//
// What the apps really write (the values are the encrypted vc3 ciphertext, i.e.
// base64 of nonce + AES-GCM(plaintext) + tag, about 4/3 of the plaintext plus a
// 4 character prefix; the server cannot see plaintext, so the sizes below are
// ciphertext characters):
//
//   - schedule_items.payload: one item as JSON (type, title <= 200 chars,
//     date, times, location <= 200 chars, acknowledgedConflictKeys, flags).
//     The editor caps title and location at 200 each. The acknowledged keys
//     are one per clashing class on that single day, so a few dozen at most.
//     Heavy item: about 6 KB plaintext, about 8 KB ciphertext. Limit 64 KiB
//     (65,536 characters), about 8x.
//   - transit_trackers.stops: the encrypted JSON array of tracked stops
//     (stop_id, stop_name, headsign). The app allows at most 4 stops per
//     tracker (MaxTrackerStops), about 150 bytes each. Measured on the dev
//     backend with the test account: 488 characters at most. Heavy case about
//     1 KB. Limit 16 KiB (16,384 bytes), kept roomy for older clients that
//     could attach more stops.
//   - course_tracker_data.data: the whole course tracker document of a user
//     (every course with its sessions, assessments and settings) as one
//     encrypted blob in the single row per user (unique index on user). This
//     is the one field that really is large: 280,764 characters measured for
//     the dev test account. A limit of 1,000,000 stays (about 3.5x that, and
//     the document grows with every semester); it is not lowered here. The
//     /crypto route's per-value cap (1 MiB) is derived from it.
//   - trip_plans.legs: web-only planner output (a handful of legs); measured
//     2,196 bytes at most on dev, a long multi-leg plan with geometry is a few
//     tens of KB. Limit 256 KiB (262,144 bytes). Remove with web/.
//
// PocketBase validates field limits only when a record is created or updated,
// never against existing rows, so saving the collection with lower limits is
// safe for stored data. A row that is already over the new limit stays
// readable and deletable but cannot be updated until it shrinks; no real row
// is near the limits.
func init() {
	type target struct {
		collection, field string
		max               int
	}
	targets := []target{
		{"schedule_items", "payload", 64 * 1024},
		{"transit_trackers", "stops", 16 * 1024},
		{"trip_plans", "legs", 256 * 1024},
	}

	m.Register(func(app core.App) error {
		for _, t := range targets {
			col, err := app.FindCollectionByNameOrId(t.collection)
			if err != nil {
				return err
			}
			switch f := col.Fields.GetByName(t.field).(type) {
			case *core.TextField:
				f.Max = t.max
			case *core.JSONField:
				f.MaxSize = int64(t.max)
			default:
				continue
			}
			if err := app.Save(col); err != nil {
				return err
			}
		}
		return nil
	}, func(app core.App) error {
		// No-op: a rollback keeps the lower limits, which are harmless.
		return nil
	})
}
