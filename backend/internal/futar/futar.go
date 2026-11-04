package futar

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pocketbase/pocketbase/core"
	"golang.org/x/sync/singleflight"

	"vectra/pb/internal/ratelimit"
)

// Port of supabase/functions/futar-proxy/index.ts. The Futár API key is
// injected server-side and never reaches the client; the endpoint whitelist
// plus param coercion are the abuse control (this route is intentionally
// public — transit arrivals are public data and the proxy's only job is
// hiding the key).
const (
	upstreamTimeout   = 35 * time.Second
	maxArrivalsLimit  = 200
	maxBatchStops     = 20
	stopArrivalsTTL   = 15 * time.Second
	stopArrivalsMax   = 300
	defaultBatchAfter = 90

	// requestsPerWindow caps proxy calls per client IP per requestWindow. The
	// route is public and every call fans out to the keyed upstream, so an
	// unbounded caller could burn the shared Futár quota. Every request costs
	// one unit, and a stop-arrivals batch additionally costs one unit per stop
	// it has to fetch upstream (cache hits are free), so a batch cannot buy up
	// to maxBatchStops upstream calls for the price of one. The cap is
	// generous because campus/CGNAT networks put many students behind one
	// address and the app polls every few seconds.
	requestsPerWindow = 300
	requestWindow     = time.Minute
)

// futarBase is a var (not a const) solely so tests can point it at an
// httptest.Server; production never reassigns it.
var futarBase = "https://futar.bkk.hu/api/query/v1/ws/otp/api/where"

// upstreamClient is shared by every proxy call so connections to Futár are
// pooled instead of a fresh transport per request.
var upstreamClient = &http.Client{Timeout: upstreamTimeout}

// limiter is the per-IP limiter for Handler. It is a var so tests can
// substitute a fresh one.
var limiter = ratelimit.New(requestsPerWindow, requestWindow)

// stopIDPattern bounds stop IDs to the BKK shape (e.g. "BKK_F01234",
// "BKK_009543") plus the separators other GTFS feeds use, so arbitrary text
// never reaches the upstream query.
var stopIDPattern = regexp.MustCompile(`^[A-Za-z0-9_:-]{1,64}$`)

func validStopID(id string) bool {
	return stopIDPattern.MatchString(id)
}

// entityIDPattern bounds route/trip IDs the same way, additionally allowing
// '.', which some GTFS feeds use inside trip ids.
var entityIDPattern = regexp.MustCompile(`^[A-Za-z0-9_.:-]{1,128}$`)

// entityIDParam reads a required route/trip id. Non-string values (numbers,
// objects, arrays) are rejected rather than stringified, so fmt.Sprint output
// such as "map[...]" never reaches the upstream query.
func entityIDParam(params map[string]any, key string) (string, error) {
	raw, present := params[key]
	if !present || raw == nil {
		return "", fmt.Errorf("%s is required", key)
	}
	s, ok := raw.(string)
	if !ok {
		return "", fmt.Errorf("%s must be a string", key)
	}
	s = strings.TrimSpace(s)
	if s == "" {
		return "", fmt.Errorf("%s is required", key)
	}
	if !entityIDPattern.MatchString(s) {
		return "", fmt.Errorf("%s is invalid", key)
	}
	return s, nil
}

// minutesBeforeBuckets / minutesAfterBuckets are the only windows forwarded
// upstream. Arbitrary values would let a caller mint unlimited distinct cache
// keys (each a fresh upstream call) for the same stop; snapping keeps the key
// space tiny. The app sends minutesBefore 0 and minutesAfter 60 or 90.
var (
	minutesBeforeBuckets = []int{0, 15, 30, 60}
	minutesAfterBuckets  = []int{30, 60, 90, 120, 180}
)

// snapMinutes coerces v like clampInt, then rounds it up to the nearest
// bucket (capped at the largest).
func snapMinutes(v any, fallback int, buckets []int) int {
	n := clampInt(v, fallback)
	for _, b := range buckets {
		if n <= b {
			return b
		}
	}
	return buckets[len(buckets)-1]
}

// ---------------------------------------------------------------------------
// param coercion (mirrors the Deno ALLOWED_ENDPOINTS normalizers)
// ---------------------------------------------------------------------------

// asNumber mimics the Deno `Number(value)` coercion for the shapes that reach
// the JSON body: real JSON numbers decode to float64, but tests (and callers
// building maps directly) may pass Go ints, so all numeric widths are handled.
func asNumber(v any) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case float32:
		return float64(n), true
	case int:
		return float64(n), true
	case int8:
		return float64(n), true
	case int16:
		return float64(n), true
	case int32:
		return float64(n), true
	case int64:
		return float64(n), true
	case uint:
		return float64(n), true
	case uint8:
		return float64(n), true
	case uint16:
		return float64(n), true
	case uint32:
		return float64(n), true
	case uint64:
		return float64(n), true
	case string:
		if strings.TrimSpace(n) == "" {
			return 0, true // Number('') === 0
		}
		f, err := strconv.ParseFloat(strings.TrimSpace(n), 64)
		if err != nil {
			return 0, false
		}
		return f, true
	default:
		return 0, false
	}
}

// clampInt clamps to Futár's accepted [0,600] window and rounds like Deno's
// Math.round. snapMinutes builds on it for minutesBefore/minutesAfter.
func clampInt(v any, fallback int) int {
	n, ok := asNumber(v)
	if !ok {
		return fallback
	}
	i := int(math.Round(n))
	if i < 0 {
		i = 0
	}
	if i > 600 {
		i = 600
	}
	return i
}

// toLimit returns "" (param omitted, keeping Futár's upstream default) unless
// the caller supplied a usable value; otherwise it clamps to [1,200].
func toLimit(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok && s == "" {
		return ""
	}
	n, ok := asNumber(v)
	if !ok {
		return ""
	}
	i := int(math.Round(n))
	if i < 1 {
		i = 1
	}
	if i > maxArrivalsLimit {
		i = maxArrivalsLimit
	}
	return strconv.Itoa(i)
}

func flag(v any) string {
	if v == true || v == "true" {
		return "true"
	}
	return "false"
}

func str(s string) string {
	if s == "<nil>" {
		return ""
	}
	return s
}

// normalizeParams validates and coerces the whitelisted endpoints.
func normalizeParams(endpoint string, params map[string]any) (map[string]string, error) {
	switch endpoint {
	case "arrivals-and-departures-for-stop":
		stopID := strings.TrimSpace(str(fmt.Sprint(params["stopId"])))
		if stopID == "" {
			return nil, fmt.Errorf("stopId is required")
		}
		if !validStopID(stopID) {
			return nil, fmt.Errorf("stopId is invalid")
		}
		out := map[string]string{
			"stopId":        stopID,
			"minutesBefore": strconv.Itoa(snapMinutes(params["minutesBefore"], 0, minutesBeforeBuckets)),
			"minutesAfter":  strconv.Itoa(snapMinutes(params["minutesAfter"], 60, minutesAfterBuckets)),
		}
		if limit := toLimit(params["limit"]); limit != "" {
			out["limit"] = limit
		}
		return out, nil
	case "vehicles-for-route":
		routeID, err := entityIDParam(params, "routeId")
		if err != nil {
			return nil, err
		}
		return map[string]string{"routeId": routeID}, nil
	case "trip-details":
		tripID, err := entityIDParam(params, "tripId")
		if err != nil {
			return nil, err
		}
		return map[string]string{
			"tripId":          tripID,
			"includeSchedule": flag(params["includeSchedule"]),
			"includeStatus":   flag(params["includeStatus"]),
		}, nil
	case "route-details":
		routeID, err := entityIDParam(params, "routeId")
		if err != nil {
			return nil, err
		}
		return map[string]string{
			"routeId":           routeID,
			"includeReferences": "stops",
			"includePolylines":  "true",
			"includeTrips":      "false",
		}, nil
	default:
		return nil, fmt.Errorf("Endpoint not allowed: %s", endpoint)
	}
}

// ---------------------------------------------------------------------------
// batched arrivals (stop-arrivals) — server-side fan-out + short TTL cache
// ---------------------------------------------------------------------------

type cacheEntry struct {
	at       time.Time
	response map[string]any
}

var (
	cacheMu sync.Mutex
	cache   = map[string]cacheEntry{}
)

func readStopArrivalsCache(key string) map[string]any {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	hit, ok := cache[key]
	if !ok {
		return nil
	}
	if time.Since(hit.at) > stopArrivalsTTL {
		delete(cache, key)
		return nil
	}
	return hit.response
}

func writeStopArrivalsCache(key string, response map[string]any) {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	cache[key] = cacheEntry{at: time.Now(), response: response}
	if len(cache) <= stopArrivalsMax {
		return
	}
	// Bound memory: drop expired entries first, then the oldest ones.
	now := time.Now()
	for k, entry := range cache {
		if now.Sub(entry.at) > stopArrivalsTTL {
			delete(cache, k)
		}
	}
	if len(cache) <= stopArrivalsMax {
		return
	}
	keys := make([]string, 0, len(cache))
	for k := range cache {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return cache[keys[i]].at.Before(cache[keys[j]].at) })
	for i := 0; i < len(keys)-stopArrivalsMax; i++ {
		delete(cache, keys[i])
	}
}

type batchParams struct {
	stopIDs       []string
	minutesBefore int
	minutesAfter  int
}

func normalizeBatchParams(params map[string]any) (batchParams, error) {
	raw := str(fmt.Sprint(params["stopIds"]))
	seen := map[string]bool{}
	var stopIDs []string
	for _, part := range strings.Split(raw, ",") {
		id := strings.TrimSpace(part)
		if id == "" || !validStopID(id) || seen[id] {
			continue
		}
		seen[id] = true
		stopIDs = append(stopIDs, id)
		if len(stopIDs) >= maxBatchStops {
			break
		}
	}
	if len(stopIDs) == 0 {
		return batchParams{}, fmt.Errorf("stopIds is required")
	}
	return batchParams{
		stopIDs:       stopIDs,
		minutesBefore: snapMinutes(params["minutesBefore"], 0, minutesBeforeBuckets),
		minutesAfter:  snapMinutes(params["minutesAfter"], defaultBatchAfter, minutesAfterBuckets),
	}, nil
}

func stopArrivalsCacheKey(stopID string, minutesBefore, minutesAfter int) string {
	return fmt.Sprintf("%s|%d|%d", stopID, minutesBefore, minutesAfter)
}

// inflight collapses concurrent cache misses for the same stop/window into a
// single upstream request (e.g. many clients polling one busy interchange
// right after its cache entry expired).
var inflight singleflight.Group

type upstreamResult struct {
	response map[string]any
	errMsg   string
}

// fetchStopArrivalsUpstream returns the upstream JSON on success, or a
// client-safe error message on failure.
//
// The second return value is deliberately a string, not an error: transport
// failures from client.Do are *url.Error values whose message embeds the full
// keyed upstream URL, and the batch path serializes these messages straight
// into response bodies (the total-failure 502 and the per-stop `error` field
// of a partial-failure 200). Only fixed messages and the upstream's own
// error/text fields are ever returned here — never a Go transport error.
func fetchStopArrivalsUpstream(ctx context.Context, stopID string, minutesBefore, minutesAfter int, apiKey string) (map[string]any, string) {
	cacheKey := stopArrivalsCacheKey(stopID, minutesBefore, minutesAfter)
	if cached := readStopArrivalsCache(cacheKey); cached != nil {
		return cached, ""
	}
	// The shared fetch must not die with whichever caller happened to start
	// it, so it is detached from that caller's cancellation (upstreamClient's
	// timeout still bounds it); each caller stops waiting on its own context.
	ch := inflight.DoChan(cacheKey, func() (any, error) {
		response, errMsg := doFetchStopArrivals(context.WithoutCancel(ctx), cacheKey, stopID, minutesBefore, minutesAfter, apiKey)
		return upstreamResult{response: response, errMsg: errMsg}, nil
	})
	select {
	case res := <-ch:
		r := res.Val.(upstreamResult)
		return r.response, r.errMsg
	case <-ctx.Done():
		return nil, "Upstream request failed"
	}
}

func doFetchStopArrivals(ctx context.Context, cacheKey, stopID string, minutesBefore, minutesAfter int, apiKey string) (map[string]any, string) {
	// A concurrent flight may have filled the cache between the caller's
	// check and this flight starting.
	if cached := readStopArrivalsCache(cacheKey); cached != nil {
		return cached, ""
	}

	u, err := url.Parse(futarBase + "/arrivals-and-departures-for-stop.json")
	if err != nil {
		return nil, "Upstream request failed"
	}
	q := u.Query()
	q.Set("stopId", stopID)
	q.Set("minutesBefore", strconv.Itoa(minutesBefore))
	q.Set("minutesAfter", strconv.Itoa(minutesAfter))
	q.Set("key", apiKey)
	u.RawQuery = q.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, "Upstream request failed"
	}
	req.Header.Set("Accept", "application/json")
	resp, err := upstreamClient.Do(req)
	if err != nil {
		return nil, "Upstream request failed"
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, "Upstream request failed"
	}
	var parsed map[string]any
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil, fmt.Sprintf("Futár returned a non-JSON response (HTTP %d)", resp.StatusCode)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 || parsed["status"] != "OK" {
		return nil, upstreamError(parsed, resp.StatusCode)
	}
	writeStopArrivalsCache(cacheKey, parsed)
	return parsed, ""
}

func upstreamError(parsed map[string]any, status int) string {
	if s, ok := parsed["error"].(string); ok && s != "" {
		return s
	}
	if s, ok := parsed["text"].(string); ok && s != "" {
		return s
	}
	return fmt.Sprintf("Futár returned HTTP %d", status)
}

func asMap(v any) map[string]any {
	if m, ok := v.(map[string]any); ok {
		return m
	}
	return nil
}

func mergeReferenceDictionary(target map[string]map[string]any, key string, source any) {
	src := asMap(source)
	if src == nil {
		return
	}
	dst := target[key]
	if dst == nil {
		dst = map[string]any{}
		target[key] = dst
	}
	for k, v := range src {
		dst[k] = v
	}
}

type stopResult struct {
	stopID   string
	response map[string]any
	errMsg   string
}

// buildStopArrivalsBatch compacts the per-stop upstream responses into the
// batched wire format mirrored by expandStopArrivalsBatch in
// src/features/transit/arrivals-batch.ts. `stops` stays per-stop (a station's
// platform dots are derived from its own response), while the route/trip/alert
// reference dictionaries are deduplicated across the whole batch.
func buildStopArrivalsBatch(results []stopResult) map[string]any {
	shared := map[string]map[string]any{}
	arrives := map[string]any{}
	currentTime := 0.0

	for _, result := range results {
		if result.response == nil || result.response["status"] != "OK" {
			msg := result.errMsg
			if msg == "" {
				msg = "Futár returned an error for this stop"
			}
			arrives[result.stopID] = map[string]any{
				"stopId": result.stopID,
				"error":  msg,
			}
			continue
		}
		if ct, ok := result.response["currentTime"].(float64); ok && ct > currentTime {
			currentTime = ct
		}
		data := asMap(result.response["data"])
		entry := asMap(data["entry"])
		refs := asMap(data["references"])

		mergeReferenceDictionary(shared, "routes", refs["routes"])
		mergeReferenceDictionary(shared, "trips", refs["trips"])
		mergeReferenceDictionary(shared, "alerts", refs["alerts"])

		stopID := result.stopID
		if s, ok := entry["stopId"].(string); ok && s != "" {
			stopID = s
		}
		stopTimes := entry["stopTimes"]
		if stopTimes == nil {
			stopTimes = []any{}
		}
		stopOut := map[string]any{
			"stopId":    stopID,
			"stopTimes": stopTimes,
		}
		if alertIDs, ok := entry["alertIds"]; ok && alertIDs != nil {
			stopOut["alertIds"] = alertIDs
		}
		stops := refs["stops"]
		if stops == nil {
			stops = map[string]any{}
		}
		stopOut["stops"] = stops

		arrives[result.stopID] = stopOut
	}

	if currentTime == 0 {
		currentTime = float64(time.Now().UnixMilli())
	}

	return map[string]any{
		"currentTime": currentTime,
		"status":      "OK",
		"data": map[string]any{
			"shared":  shared,
			"arrives": arrives,
		},
	}
}

func handleBatch(e *core.RequestEvent, params map[string]any, apiKey string) error {
	bp, err := normalizeBatchParams(params)
	if err != nil {
		return e.BadRequestError(err.Error(), nil)
	}

	// Charge one limiter unit per stop that will hit the upstream (the
	// request itself already paid one in Handler).
	misses := 0
	for _, stopID := range bp.stopIDs {
		if readStopArrivalsCache(stopArrivalsCacheKey(stopID, bp.minutesBefore, bp.minutesAfter)) == nil {
			misses++
		}
	}
	if !limiter.AllowN(ratelimit.ClientIP(e), misses, time.Now()) {
		return e.TooManyRequestsError("Too many transit requests. Please wait a moment and try again.", nil)
	}

	results := make([]stopResult, len(bp.stopIDs))
	var wg sync.WaitGroup
	for i, stopID := range bp.stopIDs {
		wg.Add(1)
		go func(i int, stopID string) {
			defer wg.Done()
			response, errMsg := fetchStopArrivalsUpstream(e.Request.Context(), stopID, bp.minutesBefore, bp.minutesAfter, apiKey)
			if errMsg != "" {
				results[i] = stopResult{stopID: stopID, errMsg: errMsg}
				return
			}
			results[i] = stopResult{stopID: stopID, response: response}
		}(i, stopID)
	}
	wg.Wait()

	succeeded := 0
	for _, r := range results {
		if r.response != nil {
			succeeded++
		}
	}
	// Total failure keeps the single-stop endpoint's error semantics so the
	// client's per-stop fallback can take over instead of painting an empty feed.
	if succeeded == 0 {
		msg := "Futár batch request failed"
		if len(results) > 0 && results[0].errMsg != "" {
			msg = results[0].errMsg
		}
		return e.JSON(http.StatusBadGateway, map[string]any{"error": msg})
	}

	return e.JSON(http.StatusOK, buildStopArrivalsBatch(results))
}

// ---------------------------------------------------------------------------
// handler
// ---------------------------------------------------------------------------

type body struct {
	Endpoint string         `json:"endpoint"`
	Params   map[string]any `json:"params"`
}

// Handler implements POST /api/vectra/futar (public). It proxies whitelisted
// BKK Futár endpoints, injecting the server-side FUTAR_API_KEY.
func Handler(e *core.RequestEvent) error {
	apiKey := os.Getenv("FUTAR_API_KEY")
	if apiKey == "" {
		return e.InternalServerError("FUTAR_API_KEY is not configured", nil)
	}
	if !limiter.Allow(ratelimit.ClientIP(e), time.Now()) {
		return e.TooManyRequestsError("Too many transit requests. Please wait a moment and try again.", nil)
	}
	var b body
	if err := e.BindBody(&b); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if b.Endpoint == "stop-arrivals" {
		return handleBatch(e, b.Params, apiKey)
	}
	params, err := normalizeParams(b.Endpoint, b.Params)
	if err != nil {
		return e.BadRequestError(err.Error(), nil)
	}

	u, err := url.Parse(futarBase + "/" + b.Endpoint + ".json")
	if err != nil {
		return e.InternalServerError("Invalid upstream URL", err)
	}
	q := u.Query()
	for k, v := range params {
		q.Set(k, v)
	}
	q.Set("key", apiKey)
	u.RawQuery = q.Encode()

	// The upstream call is tied to the incoming request, so a client that
	// disconnects stops the Futár fetch too.
	req, err := http.NewRequestWithContext(e.Request.Context(), http.MethodGet, u.String(), nil)
	if err != nil {
		return e.InternalServerError("Failed to build upstream request", nil)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := upstreamClient.Do(req)
	if err != nil {
		// The *url.Error embeds the keyed upstream URL, so it is never attached
		// (the batch path likewise returns only a fixed message).
		return e.Error(http.StatusBadGateway, "Upstream request failed", nil)
	}
	defer resp.Body.Close()

	// JSON pass-through, exactly like the Deno function: upstream 2xx -> 200,
	// anything else -> 502, body forwarded verbatim.
	status := http.StatusOK
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		status = http.StatusBadGateway
	}
	return e.Stream(status, "application/json", resp.Body)
}
