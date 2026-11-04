package migrations_test

import (
	"net/http"
	"strings"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"
)

// TestAuthorizationRulesBehavior is the behavioral counterpart to the
// rule-string assertions elsewhere: it drives PocketBase's real record API so
// the RLS-replacement rules are proven to actually isolate two users, rather
// than only being present as filter strings.
//
// It covers one owned collection family (transit_trackers + schedule_events)
// plus the users self-rules.
func TestAuthorizationRulesBehavior(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)

	newUser := func(email string) *core.Record {
		u := core.NewRecord(users)
		u.SetEmail(email)
		u.SetPassword("password123")
		require.NoError(t, app.Save(u))
		return u
	}
	userA := newUser("authz-owner-a@example.com")
	userB := newUser("authz-owner-b@example.com")

	tokenA, err := userA.NewAuthToken()
	require.NoError(t, err)
	tokenB, err := userB.NewAuthToken()
	require.NoError(t, err)

	// Seed one owned record in each family for user A.
	tt, err := app.FindCollectionByNameOrId("transit_trackers")
	require.NoError(t, err)
	trackerA := core.NewRecord(tt)
	trackerA.Set("user", userA.Id)
	trackerA.Set("dedupe_key", "a-key")
	require.NoError(t, app.Save(trackerA))

	se, err := app.FindCollectionByNameOrId("schedule_events")
	require.NoError(t, err)
	eventA := core.NewRecord(se)
	eventA.Set("user", userA.Id)
	require.NoError(t, app.Save(eventA))

	// A single app is shared across scenarios (each scenario only reads or
	// creates disjoint rows), so the known IDs can be embedded in URLs.
	factory := func(testing.TB) *tests.TestApp { return app }
	authB := map[string]string{"Authorization": tokenB}

	scenarios := []tests.ApiScenario{
		{
			Name:                  "B cannot list A's transit trackers",
			Method:                http.MethodGet,
			URL:                   "/api/collections/transit_trackers/records",
			Headers:               authB,
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"totalItems":0`},
			NotExpectedContent:    []string{trackerA.Id},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "B cannot view A's transit tracker",
			Method:                http.MethodGet,
			URL:                   "/api/collections/transit_trackers/records/" + trackerA.Id,
			Headers:               authB,
			ExpectedStatus:        http.StatusNotFound,
			ExpectedContent:       []string{`"status":404`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:           "B cannot create a transit tracker owned by A",
			Method:         http.MethodPost,
			URL:            "/api/collections/transit_trackers/records",
			Headers:        authB,
			Body:           strings.NewReader(`{"user":"` + userA.Id + `","dedupe_key":"b-forged"}`),
			ExpectedStatus: http.StatusBadRequest,
			ExpectedContent: []string{
				`"status":400`,
			},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "B can create own transit tracker",
			Method:                http.MethodPost,
			URL:                   "/api/collections/transit_trackers/records",
			Headers:               authB,
			Body:                  strings.NewReader(`{"user":"` + userB.Id + `","dedupe_key":"b-own"}`),
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"user":"` + userB.Id + `"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "B cannot update A's schedule event",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/schedule_events/records/" + eventA.Id,
			Headers:               authB,
			Body:                  strings.NewReader(`{"course_name":"hijacked"}`),
			ExpectedStatus:        http.StatusNotFound,
			ExpectedContent:       []string{`"status":404`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "B lists only own user record",
			Method:                http.MethodGet,
			URL:                   "/api/collections/users/records",
			Headers:               authB,
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"totalItems":1`, `"id":"` + userB.Id + `"`},
			NotExpectedContent:    []string{userA.Id},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "B cannot view A's user record",
			Method:                http.MethodGet,
			URL:                   "/api/collections/users/records/" + userA.Id,
			Headers:               authB,
			ExpectedStatus:        http.StatusNotFound,
			ExpectedContent:       []string{`"status":404`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "B can view own user record",
			Method:                http.MethodGet,
			URL:                   "/api/collections/users/records/" + userB.Id,
			Headers:               authB,
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"id":"` + userB.Id + `"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "A can view own seeded transit tracker",
			Method:                http.MethodGet,
			URL:                   "/api/collections/transit_trackers/records/" + trackerA.Id,
			Headers:               map[string]string{"Authorization": tokenA},
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"id":"` + trackerA.Id + `"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
	}

	for i := range scenarios {
		scenarios[i].Test(t)
	}
}

// TestWriteRuleLockdown covers migration 1700000007 behaviorally: an owner may
// not reassign their record to another user, may not PATCH any passkey field,
// and may not write legacy_id/created_at on their user record.
func TestWriteRuleLockdown(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	newUser := func(email string) *core.Record {
		u := core.NewRecord(users)
		u.SetEmail(email)
		u.SetPassword("password123")
		require.NoError(t, app.Save(u))
		return u
	}
	userA := newUser("lockdown-a@example.com")
	userB := newUser("lockdown-b@example.com")
	tokenA, err := userA.NewAuthToken()
	require.NoError(t, err)
	authA := map[string]string{"Authorization": tokenA}

	se, err := app.FindCollectionByNameOrId("schedule_events")
	require.NoError(t, err)
	eventA := core.NewRecord(se)
	eventA.Set("user", userA.Id)
	eventA.Set("course_name", "mine")
	require.NoError(t, app.Save(eventA))

	pkCol, err := app.FindCollectionByNameOrId("passkeys")
	require.NoError(t, err)
	passkeyA := core.NewRecord(pkCol)
	passkeyA.Set("user", userA.Id)
	passkeyA.Set("credential_id", "cred-a")
	passkeyA.Set("public_key", "pk-a")
	passkeyA.Set("friendly_name", "Phone")
	require.NoError(t, app.Save(passkeyA))

	factory := func(testing.TB) *tests.TestApp { return app }

	scenarios := []tests.ApiScenario{
		{
			Name:                  "A cannot reassign own schedule event to B",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/schedule_events/records/" + eventA.Id,
			Headers:               authA,
			Body:                  strings.NewReader(`{"user":"` + userB.Id + `"}`),
			ExpectedStatus:        http.StatusNotFound,
			ExpectedContent:       []string{`"status":404`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "A can still update own schedule event (user unchanged)",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/schedule_events/records/" + eventA.Id,
			Headers:               authA,
			Body:                  strings.NewReader(`{"user":"` + userA.Id + `","course_name":"renamed"}`),
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"course_name":"renamed"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "A cannot PATCH own passkey public_key",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/passkeys/records/" + passkeyA.Id,
			Headers:               authA,
			Body:                  strings.NewReader(`{"public_key":"attacker-key","sign_count":0}`),
			ExpectedStatus:        http.StatusForbidden,
			ExpectedContent:       []string{`"status":403`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "A cannot move own passkey to B",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/passkeys/records/" + passkeyA.Id,
			Headers:               authA,
			Body:                  strings.NewReader(`{"user":"` + userB.Id + `"}`),
			ExpectedStatus:        http.StatusForbidden,
			ExpectedContent:       []string{`"status":403`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "A cannot set own legacy_id",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/users/records/" + userA.Id,
			Headers:               authA,
			Body:                  strings.NewReader(`{"legacy_id":"11111111-2222-3333-4444-555555555555"}`),
			ExpectedStatus:        http.StatusNotFound,
			ExpectedContent:       []string{`"status":404`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "A cannot rewrite own created_at",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/users/records/" + userA.Id,
			Headers:               authA,
			Body:                  strings.NewReader(`{"created_at":"2000-01-01 00:00:00.000Z"}`),
			ExpectedStatus:        http.StatusNotFound,
			ExpectedContent:       []string{`"status":404`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "A can still update own profile fields",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/users/records/" + userA.Id,
			Headers:               authA,
			Body:                  strings.NewReader(`{"full_name":"Alice"}`),
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"full_name":"Alice"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "signup cannot set legacy_id",
			Method:                http.MethodPost,
			URL:                   "/api/collections/users/records",
			Body:                  strings.NewReader(`{"email":"forged@example.com","password":"password123","passwordConfirm":"password123","legacy_id":"11111111-2222-3333-4444-555555555555"}`),
			ExpectedStatus:        http.StatusBadRequest,
			ExpectedContent:       []string{`"status":400`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "plain signup still works",
			Method:                http.MethodPost,
			URL:                   "/api/collections/users/records",
			Body:                  strings.NewReader(`{"email":"plain-signup@example.com","password":"password123","passwordConfirm":"password123"}`),
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"collectionName":"users"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
	}

	for i := range scenarios {
		scenarios[i].Test(t)
	}

	// The rejected PATCHes left the passkey and the event's owner untouched.
	pk, err := app.FindRecordById("passkeys", passkeyA.Id)
	require.NoError(t, err)
	require.Equal(t, "pk-a", pk.GetString("public_key"))
	require.Equal(t, userA.Id, pk.GetString("user"))
	ev, err := app.FindRecordById("schedule_events", eventA.Id)
	require.NoError(t, err)
	require.Equal(t, userA.Id, ev.GetString("user"))
}

func TestTrustedProxyConfigured(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	require.Equal(t, []string{"X-Forwarded-For"}, app.Settings().TrustedProxy.Headers)
	require.False(t, app.Settings().TrustedProxy.UseLeftmostIP)
}
