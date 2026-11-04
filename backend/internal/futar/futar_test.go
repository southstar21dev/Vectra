package futar

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/ratelimit"
)

// sentinelKey is the configured FUTAR_API_KEY used by the handler tests. No
// response body on any path may ever contain it.
const sentinelKey = "test-secret-key-xyz"

func TestNormalizeRejectsUnknownEndpoint(t *testing.T) {
	_, err := normalizeParams("not-an-endpoint", map[string]any{})
	require.Error(t, err)
}

func TestNormalizeArrivalsClamps(t *testing.T) {
	p, err := normalizeParams("arrivals-and-departures-for-stop", map[string]any{
		"stopId": "BKK_1", "minutesAfter": 9999, "limit": 5000,
	})
	require.NoError(t, err)
	require.Equal(t, "BKK_1", p["stopId"])
	require.Equal(t, "180", p["minutesAfter"]) // snapped to the largest bucket
	require.Equal(t, "200", p["limit"])        // clamped to [1,200]
}

func TestNormalizeTripDetails(t *testing.T) {
	p, err := normalizeParams("trip-details", map[string]any{"tripId": "T1", "includeSchedule": "true"})
	require.NoError(t, err)
	require.Equal(t, "true", p["includeSchedule"])
	require.Equal(t, "false", p["includeStatus"])
}

func TestNormalizeBatchParamsDedupesAndDefaults(t *testing.T) {
	bp, err := normalizeBatchParams(map[string]any{"stopIds": " A , B ,A, , C "})
	require.NoError(t, err)
	require.Equal(t, []string{"A", "B", "C"}, bp.stopIDs)
	require.Equal(t, 0, bp.minutesBefore)
	require.Equal(t, 90, bp.minutesAfter)

	_, err = normalizeBatchParams(map[string]any{"stopIds": " , "})
	require.Error(t, err)
}

func TestNormalizeBatchParamsCapsAt20(t *testing.T) {
	ids := make([]string, 0, 25)
	for i := 0; i < 25; i++ {
		ids = append(ids, fmt.Sprintf("S%d", i))
	}
	bp, err := normalizeBatchParams(map[string]any{"stopIds": strings.Join(ids, ",")})
	require.NoError(t, err)
	require.Len(t, bp.stopIDs, 20)
}

func TestBuildStopArrivalsBatch(t *testing.T) {
	results := []stopResult{
		{
			stopID: "S1",
			response: map[string]any{
				"status":      "OK",
				"currentTime": float64(1000),
				"data": map[string]any{
					"entry": map[string]any{
						"stopId":    "S1",
						"stopTimes": []any{map[string]any{"tripId": "T1"}},
						"alertIds":  []any{"A1"},
					},
					"references": map[string]any{
						"routes": map[string]any{"R1": map[string]any{"id": "R1"}},
						"trips":  map[string]any{"T1": map[string]any{"routeId": "R1"}},
						"alerts": map[string]any{"A1": map[string]any{"id": "A1"}},
						"stops":  map[string]any{"S1": map[string]any{"id": "S1"}},
					},
				},
			},
		},
		{
			stopID: "S2",
			response: map[string]any{
				"status":      "OK",
				"currentTime": float64(2000),
				"data": map[string]any{
					"entry": map[string]any{"stopId": "S2"},
					"references": map[string]any{
						"routes": map[string]any{"R2": map[string]any{"id": "R2"}},
						"stops":  map[string]any{"S2": map[string]any{"id": "S2"}},
					},
				},
			},
		},
		{stopID: "S3", errMsg: "boom"},
	}

	out := buildStopArrivalsBatch(results)
	require.Equal(t, "OK", out["status"])
	require.Equal(t, float64(2000), out["currentTime"])

	data := out["data"].(map[string]any)
	shared := data["shared"].(map[string]map[string]any)
	require.Len(t, shared["routes"], 2) // deduplicated across stops
	require.Len(t, shared["trips"], 1)
	require.Len(t, shared["alerts"], 1)

	arrives := data["arrives"].(map[string]any)

	s1 := arrives["S1"].(map[string]any)
	require.Equal(t, "S1", s1["stopId"])
	require.Equal(t, []any{"A1"}, s1["alertIds"])
	require.Len(t, s1["stops"].(map[string]any), 1) // stops stay per-stop

	s2 := arrives["S2"].(map[string]any)
	require.Equal(t, []any{}, s2["stopTimes"])
	require.NotContains(t, s2, "alertIds")
	require.Len(t, s2["stops"].(map[string]any), 1)

	s3 := arrives["S3"].(map[string]any)
	require.Equal(t, "boom", s3["error"])
}

// ---------------------------------------------------------------------------
// handler-level tests (drive Handler directly with an httptest event)
// ---------------------------------------------------------------------------

// newRequestEvent builds the minimal *core.RequestEvent the handler needs:
// a JSON request and a recorder as the response writer. It deliberately does
// not take *testing.T so it is safe to call from the concurrency test's
// goroutines.
func newRequestEvent(body string) (*core.RequestEvent, *httptest.ResponseRecorder) {
	req := httptest.NewRequest(http.MethodPost, "/api/vectra/futar", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{}
	e.Request = req
	e.Response = rec
	return e, rec
}

// runHandler invokes Handler and, when it returns an error, writes it to the
// recorder exactly like the PocketBase router does (router.ErrorHandler), so
// status/body assertions exercise the real error serialization.
func runHandler(t *testing.T, body string) *httptest.ResponseRecorder {
	t.Helper()
	e, rec := newRequestEvent(body)
	if err := Handler(e); err != nil {
		router.ErrorHandler(rec, e.Request, err)
	}
	return rec
}

// useFutarBase points the upstream base at an httptest.Server for the duration
// of the test.
func useFutarBase(t *testing.T, base string) {
	t.Helper()
	prev := futarBase
	futarBase = base
	t.Cleanup(func() { futarBase = prev })
}

// resetStopArrivalsCache clears the package-global TTL cache so cache
// assertions are deterministic even under `go test -count=N` (which reruns
// tests in the same process, faster than the 15s TTL).
func resetStopArrivalsCache() {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	cache = map[string]cacheEntry{}
}

func TestHandlerMissingKey(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", "")
	rec := runHandler(t, `{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_1"}}`)
	require.Equal(t, http.StatusInternalServerError, rec.Code)
	require.NotContains(t, rec.Body.String(), sentinelKey)
}

func TestHandlerUnknownEndpoint(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	rec := runHandler(t, `{"endpoint":"evil-endpoint","params":{}}`)
	require.Equal(t, http.StatusBadRequest, rec.Code)
	require.NotContains(t, rec.Body.String(), sentinelKey)
}

// TestHandlerUpstreamFailureDoesNotLeakKey drives client.Do into failure by
// pointing futarBase at a closed server. The url.Error it returns wraps the
// keyed upstream URL, so this pins that the serialized ApiError never leaks it.
func TestHandlerUpstreamFailureDoesNotLeakKey(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	deadURL := srv.URL
	srv.Close() // connections are now refused
	useFutarBase(t, deadURL)

	rec := runHandler(t, `{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_1"}}`)
	require.Equal(t, http.StatusBadGateway, rec.Code)
	require.NotContains(t, rec.Body.String(), sentinelKey)
}

// TestHandlerBatchTotalFailureDoesNotLeakKey: every stop fails upstream, so the
// batch returns 502 with the first error and still must not leak the key.
func TestHandlerBatchTotalFailureDoesNotLeakKey(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"status":"ERROR","error":"upstream boom"}`)
	}))
	defer srv.Close()
	useFutarBase(t, srv.URL)

	rec := runHandler(t, `{"endpoint":"stop-arrivals","params":{"stopIds":"BF1,BF2"}}`)
	require.Equal(t, http.StatusBadGateway, rec.Code)
	require.Contains(t, rec.Body.String(), "upstream boom")
	require.NotContains(t, rec.Body.String(), sentinelKey)
}

// TestHandlerSingleEndpointPassThrough: a 2xx upstream body is forwarded
// verbatim, the key IS injected upstream, and it never reaches the client.
func TestHandlerSingleEndpointPassThrough(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)

	keyCh := make(chan string, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		keyCh <- r.URL.Query().Get("key")
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"status":"OK","data":{"entry":{"stopId":"BKK_1"}}}`)
	}))
	defer srv.Close()
	useFutarBase(t, srv.URL)

	rec := runHandler(t, `{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_1","minutesAfter":30}}`)
	require.Equal(t, http.StatusOK, rec.Code)
	require.JSONEq(t, `{"status":"OK","data":{"entry":{"stopId":"BKK_1"}}}`, rec.Body.String())
	require.Equal(t, sentinelKey, <-keyCh, "key must be injected server-side upstream")
	require.NotContains(t, rec.Body.String(), sentinelKey, "key must never reach the client")
}

// TestHandlerBatchCacheConcurrent exercises the batch path and its cache from
// many goroutines at once (run under -race), then proves the cache is actually
// used: an identical follow-up batch makes zero new upstream calls.
func TestHandlerBatchCacheConcurrent(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	resetStopArrivalsCache()

	var hits atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		stopID := r.URL.Query().Get("stopId")
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w,
			`{"status":"OK","currentTime":1234,"data":{"entry":{"stopId":%q,"stopTimes":[]},"references":{"routes":{},"trips":{},"alerts":{},"stops":{}}}}`,
			stopID,
		)
	}))
	defer srv.Close()
	useFutarBase(t, srv.URL)

	const workers = 24
	var wg sync.WaitGroup
	errs := make([]error, workers)
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			e, rec := newRequestEvent(`{"endpoint":"stop-arrivals","params":{"stopIds":"CS1,CS2"}}`)
			if err := Handler(e); err != nil {
				errs[i] = err
				return
			}
			if rec.Code != http.StatusOK {
				errs[i] = fmt.Errorf("unexpected status %d", rec.Code)
			}
		}(i)
	}
	wg.Wait()
	for i, err := range errs {
		require.NoErrorf(t, err, "worker %d", i)
	}
	require.Positive(t, hits.Load(), "upstream should have been called")

	before := hits.Load()
	e, rec := newRequestEvent(`{"endpoint":"stop-arrivals","params":{"stopIds":"CS1,CS2"}}`)
	require.NoError(t, Handler(e))
	require.Equal(t, http.StatusOK, rec.Code)
	require.Equal(t, before, hits.Load(), "identical batch within TTL must be served from cache")
}

// TestHandlerBatchTransportFailureDoesNotLeakKey drives the batch path into a
// real client.Do/transport failure. Go's *url.Error message embeds the full
// keyed upstream URL, and the batch path serializes its per-stop error message
// straight into the body — so both the total-failure 502 body and the
// per-stop `error` field of a partial-failure 200 body must never contain the
// key. (The earlier batch test only triggered an upstream application error,
// which is why it missed this.)
func TestHandlerBatchTransportFailureDoesNotLeakKey(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)

	t.Run("partial failure returns 200 and does not leak", func(t *testing.T) {
		resetStopArrivalsCache()
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Query().Get("stopId") == "TFAIL" {
				// Abort the connection with no response: client.Do returns a
				// transport error embedding the keyed URL.
				panic(http.ErrAbortHandler)
			}
			stopID := r.URL.Query().Get("stopId")
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w,
				`{"status":"OK","currentTime":1234,"data":{"entry":{"stopId":%q,"stopTimes":[]},"references":{"routes":{},"trips":{},"alerts":{},"stops":{}}}}`,
				stopID,
			)
		}))
		defer srv.Close()
		useFutarBase(t, srv.URL)

		rec := runHandler(t, `{"endpoint":"stop-arrivals","params":{"stopIds":"TOK,TFAIL"}}`)
		require.Equal(t, http.StatusOK, rec.Code)
		body := rec.Body.String()
		require.Contains(t, body, "TOK") // the healthy stop is still served
		require.NotContains(t, body, sentinelKey)
		require.Contains(t, body, "Upstream request failed") // safe per-stop message
	})

	t.Run("total failure returns 502 and does not leak", func(t *testing.T) {
		resetStopArrivalsCache()
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
		deadURL := srv.URL
		srv.Close() // connections are now refused
		useFutarBase(t, deadURL)

		rec := runHandler(t, `{"endpoint":"stop-arrivals","params":{"stopIds":"TDEAD1,TDEAD2"}}`)
		require.Equal(t, http.StatusBadGateway, rec.Code)
		require.NotContains(t, rec.Body.String(), sentinelKey)
		require.Contains(t, rec.Body.String(), "Upstream request failed")
	})
}

func TestStopIDValidation(t *testing.T) {
	for _, id := range []string{"BKK_F01234", "BKK_009543", "BKK_1", "MAV:005510", "stop-1"} {
		require.True(t, validStopID(id), id)
	}
	for _, id := range []string{"", "BKK 1", "BKK_1&key=x", "../x", "BKK_1\n", strings.Repeat("A", 65)} {
		require.False(t, validStopID(id), "%q must be rejected", id)
	}

	_, err := normalizeParams("arrivals-and-departures-for-stop", map[string]any{"stopId": "BKK_1&minutesAfter=999"})
	require.Error(t, err)

	// Invalid batch members are dropped; valid ones survive.
	bp, err := normalizeBatchParams(map[string]any{"stopIds": "BKK_F01234,bad id,BKK_2"})
	require.NoError(t, err)
	require.Equal(t, []string{"BKK_F01234", "BKK_2"}, bp.stopIDs)

	_, err = normalizeBatchParams(map[string]any{"stopIds": "bad id,<script>"})
	require.Error(t, err, "a batch with no valid stop id is rejected")
}

func TestHandlerInvalidStopIDRejected(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	rec := runHandler(t, `{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_1 OR 1=1"}}`)
	require.Equal(t, http.StatusBadRequest, rec.Code)
}

func TestHandlerRateLimited(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	prev := limiter
	limiter = ratelimit.New(1, time.Minute)
	t.Cleanup(func() { limiter = prev })

	rec := runHandler(t, `{"endpoint":"evil-endpoint","params":{}}`)
	require.Equal(t, http.StatusBadRequest, rec.Code, "first call reaches validation")
	rec = runHandler(t, `{"endpoint":"evil-endpoint","params":{}}`)
	require.Equal(t, http.StatusTooManyRequests, rec.Code)
}

// TestMinutesSnapToBuckets: the values the app sends pass through unchanged,
// anything else rounds up to a fixed bucket so cache keys cannot be varied.
func TestMinutesSnapToBuckets(t *testing.T) {
	p, err := normalizeParams("arrivals-and-departures-for-stop", map[string]any{
		"stopId": "BKK_1", "minutesBefore": 0, "minutesAfter": 90, "limit": 200,
	})
	require.NoError(t, err)
	require.Equal(t, "0", p["minutesBefore"])
	require.Equal(t, "90", p["minutesAfter"])
	require.Equal(t, "200", p["limit"])

	p, err = normalizeParams("arrivals-and-departures-for-stop", map[string]any{"stopId": "BKK_1"})
	require.NoError(t, err)
	require.Equal(t, "0", p["minutesBefore"])
	require.Equal(t, "60", p["minutesAfter"], "default stays 60")

	for in, want := range map[any]int{0: 30, 60: 60, 61: 90, 89.6: 90, 91: 120, 500: 180, "abc": 90} {
		bp, err := normalizeBatchParams(map[string]any{"stopIds": "A", "minutesAfter": in})
		require.NoError(t, err)
		require.Equal(t, want, bp.minutesAfter, "minutesAfter %v", in)
	}
	for in, want := range map[any]int{0: 0, 1: 15, 31: 60, 999: 60} {
		bp, err := normalizeBatchParams(map[string]any{"stopIds": "A", "minutesBefore": in})
		require.NoError(t, err)
		require.Equal(t, want, bp.minutesBefore, "minutesBefore %v", in)
	}
}

func TestRouteAndTripIDValidation(t *testing.T) {
	for _, ep := range []string{"vehicles-for-route", "route-details"} {
		p, err := normalizeParams(ep, map[string]any{"routeId": " BKK_3010 "})
		require.NoError(t, err)
		require.Equal(t, "BKK_3010", p["routeId"])
		for _, bad := range []any{nil, "", 3010, map[string]any{"x": 1}, []any{"BKK_1"}, "BKK_1&key=x", "a b"} {
			_, err := normalizeParams(ep, map[string]any{"routeId": bad})
			require.Error(t, err, "%s routeId %#v must be rejected", ep, bad)
		}
	}
	p, err := normalizeParams("trip-details", map[string]any{"tripId": "volan_123-4.5"})
	require.NoError(t, err)
	require.Equal(t, "volan_123-4.5", p["tripId"])
	_, err = normalizeParams("trip-details", map[string]any{"tripId": 42})
	require.Error(t, err)
	_, err = normalizeParams("trip-details", map[string]any{})
	require.Error(t, err)
}

// TestHandlerBatchChargesPerStop: a batch costs one unit per uncached stop on
// top of the request's own unit; cached stops are free.
func TestHandlerBatchChargesPerStop(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	resetStopArrivalsCache()
	prev := limiter
	limiter = ratelimit.New(4, time.Minute)
	t.Cleanup(func() { limiter = prev })

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w,
			`{"status":"OK","currentTime":1,"data":{"entry":{"stopId":%q,"stopTimes":[]},"references":{}}}`,
			r.URL.Query().Get("stopId"),
		)
	}))
	defer srv.Close()
	useFutarBase(t, srv.URL)

	// 1 (request) + 4 (stops) exceeds the budget of 4.
	rec := runHandler(t, `{"endpoint":"stop-arrivals","params":{"stopIds":"P1,P2,P3,P4"}}`)
	require.Equal(t, http.StatusTooManyRequests, rec.Code)

	limiter = ratelimit.New(4, time.Minute)
	// 1 + 3 fits and warms the cache.
	rec = runHandler(t, `{"endpoint":"stop-arrivals","params":{"stopIds":"P1,P2,P3"}}`)
	require.Equal(t, http.StatusOK, rec.Code)

	limiter = ratelimit.New(1, time.Minute)
	// Fully cached: only the request unit is charged.
	rec = runHandler(t, `{"endpoint":"stop-arrivals","params":{"stopIds":"P1,P2,P3"}}`)
	require.Equal(t, http.StatusOK, rec.Code)
}

// TestFetchStopArrivalsCollapsesConcurrentMisses: concurrent misses for the
// same stop/window share one upstream request.
func TestFetchStopArrivalsCollapsesConcurrentMisses(t *testing.T) {
	resetStopArrivalsCache()
	var hits atomic.Int64
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		<-release
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"status":"OK","data":{"entry":{"stopId":"SF1"}}}`)
	}))
	defer srv.Close()
	useFutarBase(t, srv.URL)

	const callers = 10
	var wg sync.WaitGroup
	var ok atomic.Int64
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if resp, msg := fetchStopArrivalsUpstream(t.Context(), "SF1", 0, 90, sentinelKey); resp != nil && msg == "" {
				ok.Add(1)
			}
		}()
	}
	time.Sleep(100 * time.Millisecond) // let every caller join the flight
	close(release)
	wg.Wait()
	require.EqualValues(t, callers, ok.Load())
	require.EqualValues(t, 1, hits.Load(), "concurrent misses must share one upstream call")
}
