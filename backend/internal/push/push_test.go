package push

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/SherClockHolmes/webpush-go"
	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/ratelimit"
	"vectra/pb/internal/schema"
	_ "vectra/pb/migrations"
)

// newTestUser creates and saves a regular auth user.
func newTestUser(t *testing.T, app *tests.TestApp, email string) *core.Record {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	user := core.NewRecord(users)
	user.SetEmail(email)
	user.SetPassword("password123")
	require.NoError(t, app.Save(user))
	return user
}

// requestEvent builds a RequestEvent backed by a recorder so the handler's
// JSON writes are observable.
func requestEvent(t *testing.T, app *tests.TestApp, auth *core.Record, body string) (*core.RequestEvent, *httptest.ResponseRecorder) {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/push/send", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{App: app, Auth: auth}
	e.Request = req
	e.Response = rec
	return e, rec
}

func TestHandlerForbidsNonOwnerNonSuperuser(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	caller := newTestUser(t, app, "caller@example.com")
	target := newTestUser(t, app, "target@example.com")

	body := `{"user_id":"` + target.Id + `","title":"Hi","body":"There"}`
	e, _ := requestEvent(t, app, caller, body)

	err = Handler(e)
	apiErr, ok := err.(*router.ApiError)
	require.True(t, ok, "expected an *router.ApiError, got %T", err)
	require.Equal(t, http.StatusForbidden, apiErr.Status)
}

func TestHandlerAllowsSuperuserForAnyTarget(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	t.Setenv("VAPID_PUBLIC_KEY", "pub")
	t.Setenv("VAPID_PRIVATE_KEY", "priv")

	target := newTestUser(t, app, "target-su@example.com")

	superuser, err := app.FindAuthRecordByEmail("_superusers", "test@example.com")
	require.NoError(t, err)
	require.NotNil(t, superuser)

	body := `{"user_id":"` + target.Id + `","title":"Hi","body":"There"}`
	e, rec := requestEvent(t, app, superuser, body)

	require.NoError(t, Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)

	var out map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &out))
	require.Equal(t, "No push subscriptions found for user", out["message"])
}

func TestHandlerOwnerWithNoSubscriptionsReturns200(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	t.Setenv("VAPID_PUBLIC_KEY", "pub")
	t.Setenv("VAPID_PRIVATE_KEY", "priv")

	owner := newTestUser(t, app, "owner@example.com")

	body := `{"user_id":"` + owner.Id + `","title":"Hi","body":"There"}`
	e, rec := requestEvent(t, app, owner, body)

	require.NoError(t, Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)

	var out map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &out))
	require.Equal(t, "No push subscriptions found for user", out["message"])
}

func TestHandlerRequiresCoreFields(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	owner := newTestUser(t, app, "missing-fields@example.com")
	e, _ := requestEvent(t, app, owner, `{"user_id":"`+owner.Id+`","title":"Hi"}`)

	err = Handler(e)
	apiErr, ok := err.(*router.ApiError)
	require.True(t, ok, "expected an *router.ApiError, got %T", err)
	require.Equal(t, http.StatusBadRequest, apiErr.Status)
}

func TestHandlerForbidsNilAuth(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	target := newTestUser(t, app, "nil-auth@example.com")
	body := `{"user_id":"` + target.Id + `","title":"Hi","body":"There"}`
	e, _ := requestEvent(t, app, nil, body)

	err = Handler(e)
	apiErr, ok := err.(*router.ApiError)
	require.True(t, ok, "expected an *router.ApiError, got %T", err)
	require.Equal(t, http.StatusForbidden, apiErr.Status)
}

// newSubscription saves a push_subscriptions row for userID.
func newSubscription(t *testing.T, app *tests.TestApp, userID, transport string, sub map[string]any) *core.Record {
	t.Helper()
	col, err := app.FindCollectionByNameOrId("push_subscriptions")
	require.NoError(t, err)
	rec := core.NewRecord(col)
	rec.Set("user", userID)
	rec.Set("transport", transport)
	rec.Set("subscription", sub)
	require.NoError(t, app.Save(rec))
	return rec
}

// TestSendWebPushStaleDeletesRowAndFails covers both stale status codes.
func TestSendWebPushStaleDeletesRowAndFails(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "webpush-stale@example.com")

	for _, status := range []int{http.StatusNotFound, http.StatusGone} {
		t.Run(strconv.Itoa(status), func(t *testing.T) {
			rec := newSubscription(t, app, user.Id, "webpush", map[string]any{
				"endpoint": "https://fcm.googleapis.com/fcm/send/dead",
				"keys":     map[string]any{"p256dh": "p", "auth": "a"},
			})
			d := &delivery{
				app: app,
				webpushSendFn: func(message []byte, sub *webpush.Subscription, opts *webpush.Options) (*http.Response, error) {
					return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("")), Header: http.Header{}}, nil
				},
			}

			result := d.sendWebPush(rec)
			require.Equal(t, false, result["success"])
			require.Equal(t, true, result["removed"])
			require.Equal(t, "stale-subscription", result["reason"])
			require.Equal(t, "https://fcm.googleapis.com/fcm/send/dead", result["endpoint"])

			_, err := app.FindRecordById("push_subscriptions", rec.Id)
			require.Error(t, err, "stale row must be deleted")
		})
	}
}

// TestWebPushSetsHTTPClientTimeout guards the hanging-endpoint bug: webpush-go
// defaults to a timeout-less &http.Client{}, and Handler waits on every
// delivery goroutine, so webpushSend must install a bounded client.
func TestWebPushSetsHTTPClientTimeout(t *testing.T) {
	d := &delivery{
		vapidSubject: "mailto:test@example.com",
		webpushSendFn: func(_ []byte, _ *webpush.Subscription, opts *webpush.Options) (*http.Response, error) {
			client, ok := opts.HTTPClient.(*http.Client)
			require.True(t, ok, "web push must set a concrete *http.Client, got %T", opts.HTTPClient)
			require.Equal(t, webpushSendTimeout, client.Timeout)
			return &http.Response{StatusCode: http.StatusCreated, Body: io.NopCloser(strings.NewReader("")), Header: http.Header{}}, nil
		},
	}
	_, err := d.webpushSend(&webpush.Subscription{Endpoint: "https://fcm.googleapis.com/fcm/send/x"})
	require.NoError(t, err)
}

// TestSendWebPushSuccessKeepsRow guards the non-stale 2xx path.
func TestSendWebPushSuccessKeepsRow(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "webpush-ok@example.com")
	rec := newSubscription(t, app, user.Id, "webpush", map[string]any{
		"endpoint": "https://web.push.apple.com/live",
		"keys":     map[string]any{"p256dh": "p", "auth": "a"},
	})

	d := &delivery{
		app: app,
		webpushSendFn: func(message []byte, sub *webpush.Subscription, opts *webpush.Options) (*http.Response, error) {
			return &http.Response{StatusCode: http.StatusCreated, Body: io.NopCloser(strings.NewReader("")), Header: http.Header{}}, nil
		},
	}

	result := d.sendWebPush(rec)
	require.Equal(t, true, result["success"])
	require.Equal(t, "https://web.push.apple.com/live", result["endpoint"])

	_, err = app.FindRecordById("push_subscriptions", rec.Id)
	require.NoError(t, err, "successful row must remain")
}

func TestTopicForIsAppleSafe(t *testing.T) {
	for _, tag := range []string{"vectra-test-alert", "a", "vectra-notification", strings.Repeat("x", 200)} {
		got := topicFor(tag)
		if len(got) != 32 {
			t.Fatalf("topicFor(%q) length = %d, want 32", tag, len(got))
		}
		if _, err := base64.RawURLEncoding.DecodeString(got); err != nil {
			t.Fatalf("topicFor(%q) = %q is not base64url: %v", tag, got, err)
		}
		if topicFor(tag) != got {
			t.Fatalf("topicFor(%q) is not deterministic", tag)
		}
	}
}

func TestValidateEndpointAllowlist(t *testing.T) {
	for _, ok := range []string{
		"https://fcm.googleapis.com/fcm/send/abc",
		"https://jmt17.google.com.googleapis.com/x", // suffix match
		"https://web.push.apple.com/QGx",
		"https://wns2-par02p.notify.windows.com/w/?token=x",
		"https://updates.push.services.mozilla.com/wpush/v2/x",
		"https://FCM.googleapis.com:443/fcm/send/abc",
	} {
		require.NoError(t, ValidateEndpoint(ok), ok)
	}
	for _, bad := range []string{
		"",
		"http://fcm.googleapis.com/fcm/send/abc",    // not https
		"https://fcm.googleapis.com:8443/x",         // non-default port
		"https://127.0.0.1/x",                       // internal
		"https://localhost/x",                       // internal
		"https://googleapis.com.evil.example/x",     // suffix trick
		"https://evilgoogleapis.com/x",              // no dot boundary
		"https://user:pw@fcm.googleapis.com/x",      // userinfo
		"https://169.254.169.254/latest/meta-data",  // metadata service
		"https://push.example.com/x",                // unknown host
		"ftp://updates.push.services.mozilla.com/x", // scheme
		"https://.googleapis.com/x",                 // bare suffix
	} {
		require.Error(t, ValidateEndpoint(bad), bad)
	}
}

// TestSendWebPushRefusesDisallowedEndpoint: a row whose endpoint is outside the
// allowlist is never dialled and is deleted.
func TestSendWebPushRefusesDisallowedEndpoint(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "webpush-ssrf@example.com")
	rec := newSubscription(t, app, user.Id, "webpush", map[string]any{
		"endpoint": "https://169.254.169.254/latest/meta-data",
		"keys":     map[string]any{"p256dh": "p", "auth": "a"},
	})
	d := &delivery{
		app: app,
		webpushSendFn: func([]byte, *webpush.Subscription, *webpush.Options) (*http.Response, error) {
			t.Fatal("a disallowed endpoint must never be sent to")
			return nil, nil
		},
	}
	result := d.sendWebPush(rec)
	require.Equal(t, false, result["success"])
	require.Equal(t, "invalid-subscription", result["reason"])
	_, err = app.FindRecordById("push_subscriptions", rec.Id)
	require.Error(t, err, "invalid row must be deleted")
}

// TestSendWebPushHidesDetailFromUsers: the upstream reply body is only
// returned to superusers.
func TestSendWebPushHidesDetailFromUsers(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "webpush-detail@example.com")
	for _, expose := range []bool{false, true} {
		rec := newSubscription(t, app, user.Id, "webpush", map[string]any{
			"endpoint": "https://fcm.googleapis.com/fcm/send/detail",
			"keys":     map[string]any{"p256dh": "p", "auth": "a"},
		})
		d := &delivery{
			app:          app,
			exposeDetail: expose,
			webpushSendFn: func([]byte, *webpush.Subscription, *webpush.Options) (*http.Response, error) {
				return &http.Response{StatusCode: http.StatusForbidden, Body: io.NopCloser(strings.NewReader("internal reason")), Header: http.Header{}}, nil
			},
		}
		result := d.sendWebPush(rec)
		require.Equal(t, http.StatusForbidden, result["statusCode"])
		if expose {
			require.Equal(t, "internal reason", result["detail"])
		} else {
			require.NotContains(t, result, "detail")
		}
	}
}

// TestHandlerDropsInvalidRowsAndKeepsNewestDuplicate covers the pre-send
// cleanup: unparseable / endpoint-less / disallowed rows are deleted, and of
// two rows with one endpoint the most recently updated survives.
func TestHandlerDropsInvalidRowsAndKeepsNewestDuplicate(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	t.Setenv("VAPID_PUBLIC_KEY", "pub")
	t.Setenv("VAPID_PRIVATE_KEY", "priv")

	owner := newTestUser(t, app, "dedup@example.com")
	keys := map[string]any{"p256dh": "p", "auth": "a"}
	save := func(sub any, updated string) *core.Record {
		col, err := app.FindCollectionByNameOrId("push_subscriptions")
		require.NoError(t, err)
		rec := core.NewRecord(col)
		rec.Set("user", owner.Id)
		rec.Set("transport", "webpush")
		rec.Set("subscription", sub)
		rec.Set("updated_at", updated)
		require.NoError(t, app.Save(rec))
		return rec
	}
	older := save(map[string]any{"endpoint": "https://fcm.googleapis.com/fcm/send/same", "keys": keys}, "2024-01-01 00:00:00.000Z")
	newer := save(map[string]any{"endpoint": "https://fcm.googleapis.com/fcm/send/same", "keys": keys}, "2025-01-01 00:00:00.000Z")
	noEndpoint := save(map[string]any{"keys": keys}, "2025-01-01 00:00:00.000Z")
	disallowed := save(map[string]any{"endpoint": "https://10.0.0.1/x", "keys": keys}, "2025-01-01 00:00:00.000Z")

	e, rec := requestEvent(t, app, owner, `{"user_id":"`+owner.Id+`","title":"Hi","body":"There"}`)
	require.NoError(t, Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)

	var out map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &out))
	require.EqualValues(t, 1, out["total"], "only the surviving endpoint is attempted")

	for _, gone := range []*core.Record{older, noEndpoint, disallowed} {
		_, err := app.FindRecordById("push_subscriptions", gone.Id)
		require.Error(t, err, "row %s must be deleted", gone.Id)
	}
	_, err = app.FindRecordById("push_subscriptions", newer.Id)
	require.NoError(t, err, "the newest duplicate must be kept")
}

// TestSubscriptionValidationHook drives the real records API: a subscription
// with a disallowed endpoint is rejected on create and update.
func TestSubscriptionValidationHook(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	owner := newTestUser(t, app, "hook@example.com")
	token, err := owner.NewAuthToken()
	require.NoError(t, err)
	existing := newSubscription(t, app, owner.Id, "webpush", map[string]any{
		"endpoint": "https://fcm.googleapis.com/fcm/send/ok",
		"keys":     map[string]any{"p256dh": "p", "auth": "a"},
	})

	RegisterSubscriptionValidation(app)
	factory := func(testing.TB) *tests.TestApp { return app }
	auth := map[string]string{"Authorization": token}
	sub := func(endpoint string) string {
		return `{"user":"` + owner.Id + `","transport":"webpush","subscription":{"endpoint":"` + endpoint + `","keys":{"p256dh":"p","auth":"a"}}}`
	}

	scenarios := []tests.ApiScenario{
		{
			Name:                  "create with internal endpoint is rejected",
			Method:                http.MethodPost,
			URL:                   "/api/collections/push_subscriptions/records",
			Headers:               auth,
			Body:                  strings.NewReader(sub("https://127.0.0.1:8090/api/x")),
			ExpectedStatus:        http.StatusBadRequest,
			ExpectedContent:       []string{`"status":400`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "create with push-service endpoint succeeds",
			Method:                http.MethodPost,
			URL:                   "/api/collections/push_subscriptions/records",
			Headers:               auth,
			Body:                  strings.NewReader(sub("https://web.push.apple.com/abc")),
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"user":"` + owner.Id + `"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
		{
			Name:                  "update to internal endpoint is rejected",
			Method:                http.MethodPatch,
			URL:                   "/api/collections/push_subscriptions/records/" + existing.Id,
			Headers:               auth,
			Body:                  strings.NewReader(`{"subscription":{"endpoint":"http://localhost/x"}}`),
			ExpectedStatus:        http.StatusBadRequest,
			ExpectedContent:       []string{`"status":400`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
		},
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}

// TestSendAllBoundsConcurrency: sendAll never has more than sendConcurrency
// deliveries in flight, and still returns every result in order.
func TestSendAllBoundsConcurrency(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "pool@example.com")
	var records []*core.Record
	for i := 0; i < 3*sendConcurrency; i++ {
		records = append(records, newSubscription(t, app, user.Id, "webpush", map[string]any{
			"endpoint": "https://web.push.apple.com/pool-" + strconv.Itoa(i),
			"keys":     map[string]any{"p256dh": "p", "auth": "a"},
		}))
	}

	var inFlight, peak atomic.Int64
	d := &delivery{
		app: app,
		webpushSendFn: func(message []byte, sub *webpush.Subscription, opts *webpush.Options) (*http.Response, error) {
			n := inFlight.Add(1)
			for {
				p := peak.Load()
				if n <= p || peak.CompareAndSwap(p, n) {
					break
				}
			}
			time.Sleep(5 * time.Millisecond)
			inFlight.Add(-1)
			return &http.Response{StatusCode: http.StatusCreated, Body: io.NopCloser(strings.NewReader("")), Header: http.Header{}}, nil
		},
	}

	results := d.sendAll(records)
	require.Len(t, results, len(records))
	for i, r := range results {
		v := r.Value.(map[string]any)
		require.Equal(t, true, v["success"])
		require.Equal(t, "https://web.push.apple.com/pool-"+strconv.Itoa(i), v["endpoint"])
	}
	require.LessOrEqual(t, peak.Load(), int64(sendConcurrency))
}

func TestHandlerRateLimitedPerUser(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	t.Setenv("VAPID_PUBLIC_KEY", "pub")
	t.Setenv("VAPID_PRIVATE_KEY", "priv")
	prev := sendLimiter
	sendLimiter = ratelimit.New(1, time.Minute)
	t.Cleanup(func() { sendLimiter = prev })

	owner := newTestUser(t, app, "rl@example.com")
	body := `{"user_id":"` + owner.Id + `","title":"Hi","body":"There"}`

	e, rec := requestEvent(t, app, owner, body)
	require.NoError(t, Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)

	e, _ = requestEvent(t, app, owner, body)
	apiErr, ok := Handler(e).(*router.ApiError)
	require.True(t, ok)
	require.Equal(t, http.StatusTooManyRequests, apiErr.Status)

	// Superuser service callers are exempt.
	superuser, err := app.FindAuthRecordByEmail("_superusers", "test@example.com")
	require.NoError(t, err)
	for i := 0; i < 3; i++ {
		e, rec = requestEvent(t, app, superuser, body)
		require.NoError(t, Handler(e))
		require.Equal(t, http.StatusOK, rec.Code)
	}
}

// TestSubscriptionCountCapped drives the records API: a create past
// maxSubscriptionsPerUser distinct endpoints succeeds and evicts the oldest
// row, while a row repeating an existing endpoint does not count toward the
// cap.
func TestSubscriptionCountCapped(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	owner := newTestUser(t, app, "cap@example.com")
	token, err := owner.NewAuthToken()
	require.NoError(t, err)
	col, err := app.FindCollectionByNameOrId("push_subscriptions")
	require.NoError(t, err)
	base := time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC)
	var seeded []*core.Record
	for i := 0; i < maxSubscriptionsPerUser; i++ {
		rec := core.NewRecord(col)
		rec.Set("user", owner.Id)
		rec.Set("transport", "webpush")
		rec.Set("subscription", map[string]any{
			"endpoint": "https://fcm.googleapis.com/fcm/send/cap-" + strconv.Itoa(i),
			"keys":     map[string]any{"p256dh": "p", "auth": "a"},
		})
		rec.Set("created_at", base.Add(time.Duration(i)*time.Hour))
		require.NoError(t, app.Save(rec))
		seeded = append(seeded, rec)
	}

	// Production stamps created_at on API creates; the eviction order
	// depends on it.
	schema.RegisterTimestamps(app)
	RegisterSubscriptionValidation(app)
	factory := func(testing.TB) *tests.TestApp { return app }
	auth := map[string]string{"Authorization": token}
	sub := func(endpoint string) string {
		return `{"user":"` + owner.Id + `","transport":"webpush","subscription":{"endpoint":"` + endpoint + `","keys":{"p256dh":"p","auth":"a"}}}`
	}
	exists := func(rec *core.Record) bool {
		_, err := app.FindRecordById("push_subscriptions", rec.Id)
		return err == nil
	}
	count := func() int {
		n, err := app.CountRecords("push_subscriptions", dbx.HashExp{"user": owner.Id})
		require.NoError(t, err)
		return int(n)
	}

	scenarios := []tests.ApiScenario{
		{
			Name:                  "a repeated endpoint does not count against the cap",
			Method:                http.MethodPost,
			URL:                   "/api/collections/push_subscriptions/records",
			Headers:               auth,
			Body:                  strings.NewReader(sub("https://fcm.googleapis.com/fcm/send/cap-0")),
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"user":"` + owner.Id + `"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
			AfterTestFunc: func(t testing.TB, _ *tests.TestApp, _ *http.Response) {
				require.Equal(t, maxSubscriptionsPerUser+1, count(), "nothing is evicted")
				for _, rec := range seeded {
					require.True(t, exists(rec))
				}
			},
		},
		{
			Name:                  "a new endpoint past the cap evicts the oldest device",
			Method:                http.MethodPost,
			URL:                   "/api/collections/push_subscriptions/records",
			Headers:               auth,
			Body:                  strings.NewReader(sub("https://web.push.apple.com/new-device")),
			ExpectedStatus:        http.StatusOK,
			ExpectedContent:       []string{`"user":"` + owner.Id + `"`},
			TestAppFactory:        factory,
			DisableTestAppCleanup: true,
			AfterTestFunc: func(t testing.TB, _ *tests.TestApp, _ *http.Response) {
				// cap-0 was just refreshed by the repeat, so the oldest
				// device is now cap-1; its row goes and every other device
				// (including cap-0's older duplicate row) survives.
				require.False(t, exists(seeded[1]), "the oldest device is evicted")
				require.True(t, exists(seeded[0]))
				for _, rec := range seeded[2:] {
					require.True(t, exists(rec))
				}
				require.Equal(t, maxSubscriptionsPerUser+1, count())
			},
		},
	}
	for i := range scenarios {
		scenarios[i].Test(t)
	}
}
