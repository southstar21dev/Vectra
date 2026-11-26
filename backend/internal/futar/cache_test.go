package futar

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func countingServer(t *testing.T, status int, body string) *atomic.Int32 {
	t.Helper()
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	useFutarBase(t, srv.URL)
	return &hits
}

func TestSingleStopArrivalsAreCached(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	resetStopArrivalsCache()
	hits := countingServer(t, http.StatusOK, `{"status":"OK","data":{}}`)
	req := `{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_CACHE1"}}`
	for i := 0; i < 3; i++ {
		require.Equal(t, http.StatusOK, runHandler(t, req).Code)
	}
	require.Equal(t, int32(1), hits.Load())
}

func TestUnknownStopIsCachedNegatively(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	resetStopArrivalsCache()
	hits := countingServer(t, http.StatusNotFound, `{"status":"ERROR","text":"no such stop"}`)
	req := `{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"BKK_NOPE"}}`
	for i := 0; i < 3; i++ {
		require.Equal(t, http.StatusBadGateway, runHandler(t, req).Code)
	}
	require.Equal(t, int32(1), hits.Load())

	// The batch path caches the same kind of answer.
	before := hits.Load()
	batch := `{"endpoint":"stop-arrivals","params":{"stopIds":"BKK_NOPE2"}}`
	for i := 0; i < 3; i++ {
		require.Equal(t, http.StatusBadGateway, runHandler(t, batch).Code)
	}
	require.Equal(t, before+1, hits.Load())
}

func TestServerErrorsAreNotCached(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	resetStopArrivalsCache()
	hits := countingServer(t, http.StatusInternalServerError, `{"status":"ERROR"}`)
	req := `{"endpoint":"trip-details","params":{"tripId":"BKK_T500"}}`
	runHandler(t, req)
	runHandler(t, req)
	require.Equal(t, int32(2), hits.Load())
}

func TestRouteAndTripDetailsAreCached(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	resetStopArrivalsCache()
	hits := countingServer(t, http.StatusOK, `{"status":"OK","data":{}}`)
	route := `{"endpoint":"route-details","params":{"routeId":"BKK_R1"}}`
	trip := `{"endpoint":"trip-details","params":{"tripId":"BKK_T1","includeSchedule":true}}`
	for i := 0; i < 3; i++ {
		require.Equal(t, http.StatusOK, runHandler(t, route).Code)
		require.Equal(t, http.StatusOK, runHandler(t, trip).Code)
	}
	require.Equal(t, int32(2), hits.Load())
	require.Equal(t, routeDetailsTTL, proxyTTL("route-details"))
	require.Equal(t, tripDetailsTTL, proxyTTL("trip-details"))
}

func TestUpstreamBudgetMax(t *testing.T) {
	require.Equal(t, 600, upstreamBudgetMax(""))
	require.Equal(t, 120, upstreamBudgetMax(" 120 "))
	require.Equal(t, 600, upstreamBudgetMax("abc"))
	require.Equal(t, 600, upstreamBudgetMax("0"))
}

func TestUsageLoggedOnlyAboveHalf(t *testing.T) {
	var lines []string
	prev := usageLogf
	usageLogf = func(f string, a ...any) { lines = append(lines, fmt.Sprintf(f, a...)) }
	t.Cleanup(func() { usageLogf = prev })

	now := time.Now()
	m := usageMeter{window: time.Minute}
	for i := 0; i < 5; i++ {
		m.record(now, true, 10)
	}
	m.record(now.Add(time.Minute), true, 10) // closes a window of 5 of 10: not above half
	require.Empty(t, lines)
	for i := 0; i < 6; i++ {
		m.record(now.Add(time.Minute), true, 10)
	}
	m.record(now.Add(2*time.Minute), true, 10) // closes a window of 7 of 10
	require.Len(t, lines, 1)
	require.Contains(t, lines[0], "7 of 10")
}

func TestEachStopKeepsItsOwnCachedAnswer(t *testing.T) {
	t.Setenv("FUTAR_API_KEY", sentinelKey)
	resetStopArrivalsCache()
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"status":"OK","data":{"stop":"`+r.URL.Query().Get("stopId")+`"}}`)
	}))
	t.Cleanup(srv.Close)
	useFutarBase(t, srv.URL)
	for _, id := range []string{"BKK_A1", "BKK_B2", "BKK_A1", "BKK_B2"} {
		rec := runHandler(t, `{"endpoint":"arrivals-and-departures-for-stop","params":{"stopId":"`+id+`"}}`)
		require.Equal(t, http.StatusOK, rec.Code)
		require.Contains(t, rec.Body.String(), `"stop":"`+id+`"`)
	}
	require.Equal(t, int32(2), hits.Load())
}

func TestVehiclesStayFresherThanTheAppPoll(t *testing.T) {
	require.Less(t, proxyTTL("vehicles-for-route"), 10*time.Second)
	require.LessOrEqual(t, proxyTTL("arrivals-and-departures-for-stop"), stopArrivalsTTL)
}
