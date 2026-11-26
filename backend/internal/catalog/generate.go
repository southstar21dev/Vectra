// Package catalog builds the BKK transit catalog (routes.json, stops.json,
// route-variants.json) from the Futár feed, keeps the last good copy on disk,
// refreshes it once a day and serves it at /catalog/<file>.
//
// The Futár key in use is scoped: routes-for-agency, stops-for-route and
// stop-search answer "Full authentication is required". The endpoints it does
// authorize (stops-for-location, route-details) are enough to rebuild the whole
// catalog: tile Budapest and the HÉV corridors with stops-for-location circles
// (each stop lists its routeIds), then call route-details once per route.
//
// This is the Go port of the former web/scripts/build-catalog.mjs; the output
// files are byte-identical to what that script wrote.
package catalog

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"golang.org/x/text/collate"
	"golang.org/x/text/language"
	"golang.org/x/text/unicode/norm"
)

// File names, as served and as stored.
const (
	RoutesFile   = "routes.json"
	StopsFile    = "stops.json"
	VariantsFile = "route-variants.json"
)

// Files lists the three catalog files.
var Files = []string{RoutesFile, StopsFile, VariantsFile}

// DefaultBase is the Futár "where" API root.
const DefaultBase = "https://futar.bkk.hu/api/query/v1/ws/otp/api/where"

const (
	maxBody      = 16 << 20
	callTimeout  = 30 * time.Second
	minRoutes    = 100
	minStops     = 500
	minRetention = 0.95
)

// Route is one routes.json entry.
type Route struct {
	ID          string `json:"id"`
	ShortName   string `json:"shortName"`
	Description string `json:"description"`
	Type        string `json:"type"`
	Color       string `json:"color"`
	TextColor   string `json:"textColor"`
}

// Stop is one stops.json entry (a stop area).
type Stop struct {
	ID   string  `json:"id"`
	Name string  `json:"name"`
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
}

// Variant is one direction of a route: its headsign and normalized stop names.
type Variant struct {
	H string   `json:"h"`
	S []string `json:"s"`
}

// Catalog is the decoded content of the three files.
type Catalog struct {
	Routes   []Route
	Stops    []Stop
	Variants map[string][]Variant
}

type circle struct {
	lat, lon float64
	r        int
}

// circles cover Budapest and the suburban HÉV corridors (Szentendre, Gödöllő,
// Csepel, Ráckeve) where BKK routes actually run.
var circles = []circle{
	// City core
	{47.497, 19.040, 5000}, {47.470, 19.080, 5000}, {47.520, 19.070, 5000},
	{47.540, 19.050, 5000}, {47.450, 19.030, 5000}, {47.480, 18.990, 5000},
	{47.510, 19.130, 5000}, {47.470, 19.160, 5000}, {47.540, 19.150, 5000},
	{47.420, 19.120, 5000}, {47.440, 19.220, 5000}, {47.490, 19.250, 5000},
	{47.570, 19.060, 5000},
	// Buda hills / west
	{47.510, 18.930, 4500}, {47.460, 18.960, 4500},
	// HÉV: Szentendre
	{47.600, 19.050, 5000}, {47.640, 19.080, 4500}, {47.670, 19.080, 3500},
	// HÉV: Gödöllő
	{47.580, 19.250, 5000}, {47.600, 19.350, 4000},
	// HÉV: Csepel
	{47.420, 19.060, 4500},
	// HÉV: Ráckeve
	{47.350, 19.000, 5000}, {47.280, 18.980, 4500}, {47.210, 18.950, 4000},
	{47.130, 18.940, 3500}, {47.080, 18.960, 3000},
}

// Generator talks to Futár. Zero values of the tuning fields are replaced by
// defaults in Build.
type Generator struct {
	Key    string
	Base   string
	Client *http.Client
	// Gate, when set, is called before every upstream request (the shared
	// Futár budget).
	Gate func(ctx context.Context) error
	// Interval is the minimum spacing between upstream requests, shared by all
	// workers: about 300 calls a minute by default, well below the key's quota,
	// so a build never starves the live arrivals proxy.
	Interval time.Duration
	Workers  int
	// RetryDelay is the base back-off between attempts of one call.
	RetryDelay time.Duration
	Logf       func(format string, args ...any)

	paceMu sync.Mutex
	next   time.Time
}

func (g *Generator) logf(format string, args ...any) {
	if g.Logf != nil {
		g.Logf(format, args...)
	} else {
		log.Printf("catalog: "+format, args...)
	}
}

// pace waits for the next request slot.
func (g *Generator) pace(ctx context.Context) error {
	g.paceMu.Lock()
	now := time.Now()
	slot := g.next
	if slot.Before(now) {
		slot = now
	}
	g.next = slot.Add(g.Interval)
	g.paceMu.Unlock()
	if d := slot.Sub(now); d > 0 {
		select {
		case <-time.After(d):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return nil
}

type envelope[T any] struct {
	Status string `json:"status"`
	Text   string `json:"text"`
	Data   T      `json:"data"`
}

// get fetches one endpoint, retrying twice. Errors never carry the request
// URL: it holds the API key.
func get[T any](ctx context.Context, g *Generator, endpoint string, params url.Values) (*envelope[T], error) {
	q := url.Values{}
	for k, v := range params {
		q[k] = v
	}
	q.Set("key", g.Key)
	u := g.Base + "/" + endpoint + ".json?" + q.Encode()
	const retries = 2
	var lastErr error
	for attempt := 0; attempt <= retries; attempt++ {
		if attempt > 0 {
			select {
			case <-time.After(g.RetryDelay * time.Duration(attempt)):
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		}
		env, err := getOnce[T](ctx, g, endpoint, u)
		if err == nil {
			return env, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		lastErr = err
	}
	return nil, lastErr
}

// getOnce streams the response JSON straight into the typed envelope, so only
// the fields the catalog needs are ever held in memory.
func getOnce[T any](ctx context.Context, g *Generator, endpoint, u string) (*envelope[T], error) {
	if g.Gate != nil {
		if err := g.Gate(ctx); err != nil {
			return nil, err
		}
	}
	if err := g.pace(ctx); err != nil {
		return nil, err
	}
	rctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("%s: bad request", endpoint)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := g.Client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("%s: request failed", endpoint)
	}
	defer resp.Body.Close()
	var env envelope[T]
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxBody)).Decode(&env); err != nil {
		return nil, fmt.Errorf("%s: unreadable response (HTTP %d)", endpoint, resp.StatusCode)
	}
	if env.Status != "OK" {
		return nil, fmt.Errorf("%s -> %s: %s", endpoint, env.Status, env.Text)
	}
	return &env, nil
}

type locStop struct {
	ID           string          `json:"id"`
	Lat          float64         `json:"lat"`
	Lon          float64         `json:"lon"`
	Name         string          `json:"name"`
	LocationType json.RawMessage `json:"locationType"`
	RouteIDs     []string        `json:"routeIds"`
}

type locData struct {
	List []locStop `json:"list"`
}

type refStop struct {
	Name string `json:"name"`
}

type variantEntry struct {
	Headsign string   `json:"headsign"`
	Name     string   `json:"name"`
	StopIDs  []string `json:"stopIds"`
}

type routeEntry struct {
	ShortName   string         `json:"shortName"`
	Description string         `json:"description"`
	LongName    string         `json:"longName"`
	Type        string         `json:"type"`
	Color       string         `json:"color"`
	TextColor   string         `json:"textColor"`
	Variants    []variantEntry `json:"variants"`
}

type routeData struct {
	Entry      routeEntry `json:"entry"`
	References struct {
		Stops map[string]refStop `json:"stops"`
	} `json:"references"`
}

type areaStop struct {
	Stop
	isArea bool
}

var (
	reParens = regexp.MustCompile(`\([^)]*\)`)
	reWords  = regexp.MustCompile(`\b(m|h|pu|vasutallomas|allomas)\b`)
	reSpace  = regexp.MustCompile(`[\s\x{00a0}\x{1680}\x{2000}-\x{200a}\x{2028}\x{2029}\x{202f}\x{205f}\x{3000}\x{feff}]+`)
)

// NormalizeStopName is the offline direction-matching form of a stop name. It
// must stay in sync with the apps' matching code and the historical output.
func NormalizeStopName(s string) string {
	s = strings.ToLower(s)
	s = norm.NFD.String(s)
	s = strings.Map(func(r rune) rune {
		if r >= 0x300 && r <= 0x36f {
			return -1
		}
		return r
	}, s)
	s = reParens.ReplaceAllString(s, "")
	s = reWords.ReplaceAllString(s, "")
	s = reSpace.ReplaceAllString(s, " ")
	return strings.TrimFunc(s, func(r rune) bool { return unicode.IsSpace(r) || r == 0xfeff })
}

type routeResult struct {
	route    Route
	variants []Variant
	err      error
}

// Build downloads a fresh catalog. prev (may be nil) is the last good one: it
// fills in whatever failed this run and is the baseline of the retention gate.
// A degraded result is returned together with an error and must never replace
// prev.
func (g *Generator) Build(ctx context.Context, prev *Catalog) (*Catalog, error) {
	if g.Key == "" {
		return nil, errors.New("FUTAR_API_KEY is not set")
	}
	if g.Base == "" {
		g.Base = DefaultBase
	}
	if g.Client == nil {
		// No redirects: the request URL carries the API key.
		g.Client = &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	if g.Interval == 0 {
		g.Interval = 200 * time.Millisecond
	}
	if g.Workers == 0 {
		g.Workers = 3
	}
	if g.RetryDelay == 0 {
		g.RetryDelay = 600 * time.Millisecond
	}

	// 1. Tile the map; every stop carries its routeIds.
	stops := map[string]*areaStop{}
	var stopOrder []string
	routeIDSet := map[string]bool{}
	failedCircles := 0
	for _, c := range circles {
		env, err := get[locData](ctx, g, "stops-for-location", url.Values{
			"lat":    {formatFloat(c.lat)},
			"lon":    {formatFloat(c.lon)},
			"radius": {strconv.Itoa(c.r)},
		})
		if err != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			failedCircles++
			g.logf("circle (%v,%v) r=%d failed: %v", c.lat, c.lon, c.r, err)
			continue
		}
		for _, s := range env.Data.List {
			if s.ID == "" {
				continue
			}
			if _, ok := stops[s.ID]; !ok {
				stops[s.ID] = &areaStop{
					Stop:   Stop{ID: s.ID, Name: s.Name, Lat: s.Lat, Lon: s.Lon},
					isArea: strings.TrimSpace(string(s.LocationType)) == "1",
				}
				stopOrder = append(stopOrder, s.ID)
			}
			for _, rid := range s.RouteIDs {
				routeIDSet[rid] = true
			}
		}
	}
	g.logf("unique stops: %d, unique route ids: %d", len(stops), len(routeIDSet))

	// 2. Route metadata and variants via route-details.
	ids := sortedKeys(routeIDSet)
	results := make([]routeResult, len(ids))
	var wg sync.WaitGroup
	jobs := make(chan int)
	for w := 0; w < g.Workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				results[i] = g.fetchRoute(ctx, ids[i])
			}
		}()
	}
	for i := range ids {
		jobs <- i
	}
	close(jobs)
	wg.Wait()
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	routes := map[string]Route{}
	variants := map[string][]Variant{}
	var failedIDs []string
	for i, id := range ids {
		r := results[i]
		if r.err != nil {
			g.logf("route-details failed: %s -> %v", id, r.err)
			failedIDs = append(failedIDs, id)
			continue
		}
		routes[id] = r.route
		variants[id] = r.variants
	}
	g.logf("routes fetched: %d, failed: %d", len(routes), len(failedIDs))

	// Carry forward the previous entries for whatever failed, so small daily
	// losses cannot compound past the retention gate below.
	if prev != nil && len(failedIDs) > 0 {
		prevByID := map[string]Route{}
		for _, r := range prev.Routes {
			prevByID[r.ID] = r
		}
		carried := 0
		for _, id := range failedIDs {
			p, ok := prevByID[id]
			if !ok {
				continue
			}
			routes[id] = p
			if v, ok := prev.Variants[id]; ok {
				variants[id] = v
			}
			carried++
		}
		g.logf("carried over %d/%d failed routes", carried, len(failedIDs))
	}
	if prev != nil && failedCircles > 0 {
		carried := 0
		for _, p := range prev.Stops {
			if _, ok := stops[p.ID]; !ok {
				stops[p.ID] = &areaStop{Stop: p, isArea: true}
				stopOrder = append(stopOrder, p.ID)
				carried++
			}
		}
		g.logf("%d circle(s) failed; carried over %d stop areas", failedCircles, carried)
	}

	// Stop areas only: what trackers store; platforms fold into their area.
	areas := make([]Stop, 0, len(stops))
	for _, id := range stopOrder {
		s := stops[id]
		if strings.HasPrefix(s.ID, "BKK_CS") || s.isArea {
			areas = append(areas, s.Stop)
		}
	}
	hu := collate.New(language.Hungarian)
	sort.SliceStable(areas, func(i, j int) bool { return hu.CompareString(areas[i].Name, areas[j].Name) < 0 })

	routeList := make([]Route, 0, len(routes))
	for _, id := range sortedKeys(routes) {
		routeList = append(routeList, routes[id])
	}
	huNum := collate.New(language.Hungarian, collate.Numeric)
	sort.SliceStable(routeList, func(i, j int) bool {
		return huNum.CompareString(routeList[i].ShortName, routeList[j].ShortName) < 0
	})

	out := &Catalog{Routes: routeList, Stops: areas, Variants: variants}
	return out, out.check(prev)
}

func (g *Generator) fetchRoute(ctx context.Context, id string) routeResult {
	env, err := get[routeData](ctx, g, "route-details", url.Values{
		"routeId":           {id},
		"includeReferences": {"stops"},
		"includePolylines":  {"false"},
		"includeTrips":      {"false"},
	})
	if err != nil {
		return routeResult{err: err}
	}
	e := env.Data.Entry
	res := routeResult{route: Route{
		ID:          id,
		ShortName:   firstNonEmpty(e.ShortName, id),
		Description: firstNonEmpty(e.Description, e.LongName),
		Type:        firstNonEmpty(e.Type, "UNKNOWN"),
		Color:       firstNonEmpty(e.Color, "FFFFFF"),
		TextColor:   firstNonEmpty(e.TextColor, "000000"),
	}}
	res.variants = make([]Variant, 0, len(e.Variants))
	for _, v := range e.Variants {
		names := make([]string, 0, len(v.StopIDs))
		seen := map[string]bool{}
		for _, sid := range v.StopIDs {
			n := NormalizeStopName(env.Data.References.Stops[sid].Name)
			if n != "" && !seen[n] {
				seen[n] = true
				names = append(names, n)
			}
		}
		res.variants = append(res.variants, Variant{H: firstNonEmpty(v.Headsign, v.Name), S: names})
	}
	return res
}

// check is the sanity gate: a transient feed failure must never produce a
// truncated catalog that then ships to the apps.
func (c *Catalog) check(prev *Catalog) error {
	if len(c.Routes) < minRoutes {
		return fmt.Errorf("only %d routes resolved (expected 600+): refusing to replace the catalog", len(c.Routes))
	}
	if len(c.Stops) < minStops {
		return fmt.Errorf("only %d stop areas resolved (expected 2000+): refusing to replace the catalog", len(c.Stops))
	}
	if prev == nil {
		return nil
	}
	if len(prev.Routes) > 0 && float64(len(c.Routes)) < float64(len(prev.Routes))*minRetention {
		return fmt.Errorf("routes (%d) are more than 5%% below the current catalog (%d)", len(c.Routes), len(prev.Routes))
	}
	if len(prev.Stops) > 0 && float64(len(c.Stops)) < float64(len(prev.Stops))*minRetention {
		return fmt.Errorf("stop areas (%d) are more than 5%% below the current catalog (%d)", len(c.Stops), len(prev.Stops))
	}
	if len(prev.Variants) > 0 && float64(len(c.Variants)) < float64(len(prev.Variants))*minRetention {
		return fmt.Errorf("route variants (%d) are more than 5%% below the current catalog (%d)", len(c.Variants), len(prev.Variants))
	}
	return nil
}

// Encode renders the three files exactly as the old Node generator wrote them:
// routes and stops indented by two spaces, variants compact, each with a
// trailing newline.
func (c *Catalog) Encode() (map[string][]byte, error) {
	routes, stops, variants := c.Routes, c.Stops, c.Variants
	if routes == nil {
		routes = []Route{}
	}
	if stops == nil {
		stops = []Stop{}
	}
	if variants == nil {
		variants = map[string][]Variant{}
	}
	out := map[string][]byte{}
	for name, v := range map[string]any{RoutesFile: routes, StopsFile: stops, VariantsFile: variants} {
		var buf bytes.Buffer
		enc := json.NewEncoder(&buf)
		enc.SetEscapeHTML(false)
		if name != VariantsFile {
			enc.SetIndent("", "  ")
		}
		if err := enc.Encode(v); err != nil {
			return nil, err
		}
		out[name] = buf.Bytes()
	}
	return out, nil
}

// Decode parses the three files back into a Catalog.
func Decode(files map[string][]byte) (*Catalog, error) {
	var c Catalog
	if err := json.Unmarshal(files[RoutesFile], &c.Routes); err != nil {
		return nil, fmt.Errorf("%s: %w", RoutesFile, err)
	}
	if err := json.Unmarshal(files[StopsFile], &c.Stops); err != nil {
		return nil, fmt.Errorf("%s: %w", StopsFile, err)
	}
	if err := json.Unmarshal(files[VariantsFile], &c.Variants); err != nil {
		return nil, fmt.Errorf("%s: %w", VariantsFile, err)
	}
	return &c, nil
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func formatFloat(f float64) string { return strconv.FormatFloat(f, 'f', -1, 64) }
