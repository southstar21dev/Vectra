package schema_test

import (
	"testing"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/schema"
)

func createViaHook(app *tests.TestApp, rec *core.Record) error {
	return app.OnRecordCreateRequest().Trigger(
		&core.RecordRequestEvent{RequestEvent: &core.RequestEvent{App: app}, Record: rec},
		func(e *core.RecordRequestEvent) error { return app.Save(e.Record) },
	)
}

func TestRowQuotaRefusesCreateAtTheCap(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	schema.RegisterRowQuotas(app)
	prev := schema.RowQuotas["trip_plans"]
	schema.RowQuotas["trip_plans"] = 2
	t.Cleanup(func() { schema.RowQuotas["trip_plans"] = prev })

	owner := newUser(t, app, "quota-owner@example.com")
	other := newUser(t, app, "quota-other@example.com")
	col, err := app.FindCollectionByNameOrId("trip_plans")
	require.NoError(t, err)

	newPlan := func(u *core.Record) *core.Record {
		r := core.NewRecord(col)
		r.Set("user", u.Id)
		return r
	}
	require.NoError(t, createViaHook(app, newPlan(owner)))
	require.NoError(t, createViaHook(app, newPlan(owner)))
	require.Error(t, createViaHook(app, newPlan(owner)), "third row exceeds the cap")
	require.NoError(t, createViaHook(app, newPlan(other)), "the cap is per user")
}

// TestRegisterTimestampsKeepsCreatedAtOnUpdate: a client-sent created_at on
// update is reverted to the stored value.
func TestRegisterTimestampsKeepsCreatedAtOnUpdate(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	schema.RegisterTimestamps(app)
	user := newUser(t, app, "forged-update@example.com")
	col, err := app.FindCollectionByNameOrId("schedule_events")
	require.NoError(t, err)

	rec := core.NewRecord(col)
	rec.Set("user", user.Id)
	require.NoError(t, app.OnRecordCreateRequest().Trigger(
		&core.RecordRequestEvent{Record: rec},
		func(e *core.RecordRequestEvent) error { return app.Save(e.Record) },
	))
	stored := rec.GetString("created_at")

	loaded, err := app.FindRecordById(col.Id, rec.Id)
	require.NoError(t, err)
	loaded.Set("created_at", time.Now().UTC().AddDate(10, 0, 0))
	require.NoError(t, app.OnRecordUpdateRequest().Trigger(
		&core.RecordRequestEvent{Record: loaded},
		func(e *core.RecordRequestEvent) error { return app.Save(e.Record) },
	))

	reloaded, err := app.FindRecordById(col.Id, rec.Id)
	require.NoError(t, err)
	require.Equal(t, stored, reloaded.GetString("created_at"))
}
