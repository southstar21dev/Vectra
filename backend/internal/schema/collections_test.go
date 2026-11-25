package schema_test

import (
	"testing"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/schema"
	_ "vectra/pb/migrations"
)

// TestOwnerCollections: the derived set holds the known data collections, and
// every one cascades on user delete. Account deletion relies on the cascade, so
// a new collection added without CascadeDelete fails here instead of leaving
// orphans behind.
func TestOwnerCollections(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	owned, err := schema.OwnerCollections(app)
	require.NoError(t, err)

	names := map[string]bool{}
	for _, c := range owned {
		names[c.Name] = true
		rel, ok := c.Fields.GetByName("user").(*core.RelationField)
		require.True(t, ok, c.Name)
		require.True(t, rel.CascadeDelete, "%s: user relation must cascade on delete", c.Name)
	}
	for _, want := range []string{
		"schedule_events", "neptun_schedule_events", "schedule_items",
		"transit_trackers", "course_tracker_data", "trip_plans",
		"push_subscriptions", "passkeys", "passkey_challenges",
	} {
		require.True(t, names[want], "%s should be an owner collection", want)
	}
	require.False(t, names["users"])
}
