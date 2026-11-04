package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"

	"vectra/pb/internal/schema"
)

func init() {
	m.Register(func(app core.App) error {
		users, err := app.FindCollectionByNameOrId("users")
		if err != nil {
			return err
		}
		userRel := func() *core.RelationField {
			return &core.RelationField{Name: "user", Required: true, MaxSelect: 1, CollectionId: users.Id, CascadeDelete: true}
		}
		base := func(name string, extra ...core.Field) *core.Collection {
			c := core.NewBaseCollection(name)
			c.Fields.Add(userRel())
			c.Fields.Add(extra...)
			c.Fields.Add(
				&core.DateField{Name: "created_at"},
				&core.DateField{Name: "updated_at"},
			)
			schema.OwnerRules(c)
			return c
		}

		cols := []*core.Collection{
			base("schedule_events",
				&core.TextField{Name: "course_name"}, &core.TextField{Name: "course_code"},
				&core.TextField{Name: "instructor"}, &core.TextField{Name: "location"},
				&core.NumberField{Name: "day_of_week", OnlyInt: true},
				&core.TextField{Name: "start_time"}, &core.TextField{Name: "end_time"},
				&core.TextField{Name: "color"}, &core.TextField{Name: "semester"},
				&core.DateField{Name: "semester_start"}, &core.DateField{Name: "semester_end"},
				&core.TextField{Name: "event_type"},
				&core.SelectField{Name: "attendance_type", MaxSelect: 1, Values: []string{"lecture", "seminar", "lab", "unknown"}},
				&core.JSONField{Name: "csv_metadata", MaxSize: 65536},
			),
			base("neptun_schedule_events",
				&core.TextField{Name: "course_name"}, &core.TextField{Name: "course_code"},
				&core.TextField{Name: "instructor"}, &core.TextField{Name: "location"},
				&core.TextField{Name: "day_of_week"}, &core.TextField{Name: "start_time"},
				&core.TextField{Name: "end_time"}, &core.TextField{Name: "color"},
				&core.TextField{Name: "semester"}, &core.TextField{Name: "semester_start"},
				&core.TextField{Name: "semester_end"}, &core.TextField{Name: "event_type"},
			),
			base("schedule_items", &core.TextField{Name: "payload", Required: true}),
			base("course_tracker_data", &core.TextField{Name: "data", Required: true}),
			base("trip_plans",
				&core.TextField{Name: "name"}, &core.JSONField{Name: "legs", MaxSize: 1048576},
				&core.NumberField{Name: "sort_order", OnlyInt: true}, &core.TextField{Name: "direction"},
			),
			base("push_subscriptions",
				&core.JSONField{Name: "subscription", Required: true, MaxSize: 65536},
				&core.TextField{Name: "device_name"}, &core.TextField{Name: "transport"},
			),
		}

		tt := base("transit_trackers",
			&core.TextField{Name: "stop_id"}, &core.TextField{Name: "stop_name"},
			&core.TextField{Name: "route_id"}, &core.TextField{Name: "route_short_name"},
			&core.TextField{Name: "route_long_name"}, &core.TextField{Name: "route_type"},
			&core.TextField{Name: "route_color"}, &core.TextField{Name: "route_text_color"},
			&core.TextField{Name: "headsign"}, &core.BoolField{Name: "is_favorite"},
			// Required matches the old `dedupe_key TEXT NOT NULL`: PocketBase
			// stores an unset optional TextField as "" and the unique index
			// below is non-partial, so without Required a second tracker with
			// no dedupe key would collide on (user, "").
			&core.TextField{Name: "dedupe_key", Required: true}, &core.JSONField{Name: "stops", MaxSize: 1048576},
			&core.NumberField{Name: "sort_order", OnlyInt: true},
		)
		// Deterministic per-user uniqueness (mirrors the old unique index).
		tt.AddIndex("idx_transit_dedupe", true, "user, dedupe_key", "")
		cols = append(cols, tt)

		for _, c := range cols {
			if err := app.Save(c); err != nil {
				return err
			}
		}
		return nil
	}, func(app core.App) error {
		for _, name := range []string{"schedule_events", "neptun_schedule_events", "schedule_items",
			"transit_trackers", "course_tracker_data", "trip_plans", "push_subscriptions"} {
			c, err := app.FindCollectionByNameOrId(name)
			if err == nil {
				_ = app.Delete(c)
			}
		}
		return nil
	})
}
