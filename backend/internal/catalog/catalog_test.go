package catalog

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// fakeFutar is a recorded-shape Futár: 120 routes, 600 stop areas and a
// platform that must be dropped. failRoute makes one route-details call fail.
func fakeFutar(t *testing.T, failRoute string) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var calls atomic.Int32
	var ids []string
	for i := 0; i < 120; i++ {
		ids = append(ids, fmt.Sprintf("BKK_%04d", i))
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		require.Equal(t, "secret", r.URL.Query().Get("key"))
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/stops-for-location.json"):
			var list []map[string]any
			for i := 0; i < 600; i++ {
				s := map[string]any{"id": fmt.Sprintf("BKK_CS%04d", i), "lat": 47.4 + float64(i)/10000, "lon": 19.0 + float64(i)/10000,
					"name": fmt.Sprintf("Stop %03d", (i*7)%600), "locationType": 1, "routeIds": []string{}, "ignored": "x"}
				if i == 0 {
					s["routeIds"] = ids
				}
				list = append(list, s)
			}
			list = append(list, map[string]any{"id": "BKK_P1", "lat": 1.0, "lon": 2.0, "name": "Platform", "locationType": 0})
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "OK", "data": map[string]any{"list": list}})
		case strings.HasSuffix(r.URL.Path, "/route-details.json"):
			id := r.URL.Query().Get("routeId")
			if id == failRoute {
				_ = json.NewEncoder(w).Encode(map[string]any{"status": "ERROR", "text": "boom"})
				return
			}
			entry := map[string]any{"shortName": shortFor(id), "longName": "Long " + id, "type": "BUS", "color": "009FE3",
				"variants": []map[string]any{{"headsign": "Végállomás", "stopIds": []string{"a", "b", "c", "d"}}, {"name": "Alt", "stopIds": []string{}}}}
			refs := map[string]any{
				"a": map[string]any{"name": "Árpád híd (M)"}, "b": map[string]any{"name": "Árpád híd"},
				"c": map[string]any{"name": "  Déli  pályaudvar H "}, "d": map[string]any{"name": "(x)"},
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "OK", "data": map[string]any{"entry": entry, "references": map[string]any{"stops": refs}}})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, &calls
}

func shortFor(id string) string {
	n, _ := strconv.Atoi(strings.TrimPrefix(id, "BKK_"))
	return strconv.Itoa(n + 1)
}

func testGen(base string) *Generator {
	return &Generator{Key: "secret", Base: base, Interval: time.Microsecond, RetryDelay: time.Microsecond, Logf: func(string, ...any) {}}
}

func TestBuildFromFixture(t *testing.T) {
	srv, _ := fakeFutar(t, "")
	c, err := testGen(srv.URL).Build(context.Background(), nil)
	require.NoError(t, err)
	require.Len(t, c.Routes, 120)
	require.Len(t, c.Stops, 600, "the platform is not a stop area")
	require.Equal(t, "Stop 000", c.Stops[0].Name)
	require.Equal(t, "Stop 001", c.Stops[1].Name)
	// shortName "1".."120" sorts numerically, not lexically.
	require.Equal(t, "1", c.Routes[0].ShortName)
	require.Equal(t, "10", c.Routes[9].ShortName)
	require.Equal(t, "Long BKK_0000", c.Routes[0].Description)
	require.Equal(t, "000000", c.Routes[0].TextColor)
	v := c.Variants["BKK_0000"]
	require.Equal(t, []Variant{{H: "Végállomás", S: []string{"arpad hid", "deli palyaudvar"}}, {H: "Alt", S: []string{}}}, v)
}

func TestEncodeMatchesNodeGeneratorFormat(t *testing.T) {
	c := &Catalog{
		Routes:   []Route{{ID: "BKK_1", ShortName: "1", Description: "A <b> & ő", Type: "BUS", Color: "FFFFFF", TextColor: "000000"}},
		Stops:    []Stop{{ID: "BKK_CS1", Name: "Árpád híd", Lat: 47.5, Lon: 19.04}},
		Variants: map[string][]Variant{"BKK_1": {{H: "x", S: []string{"a", "b"}}, {H: "", S: []string{}}}},
	}
	files, err := c.Encode()
	require.NoError(t, err)
	require.Equal(t, "[\n  {\n    \"id\": \"BKK_1\",\n    \"shortName\": \"1\",\n    \"description\": \"A <b> & ő\",\n    \"type\": \"BUS\",\n    \"color\": \"FFFFFF\",\n    \"textColor\": \"000000\"\n  }\n]\n", string(files[RoutesFile]))
	require.Equal(t, "[\n  {\n    \"id\": \"BKK_CS1\",\n    \"name\": \"Árpád híd\",\n    \"lat\": 47.5,\n    \"lon\": 19.04\n  }\n]\n", string(files[StopsFile]))
	require.Equal(t, "{\"BKK_1\":[{\"h\":\"x\",\"s\":[\"a\",\"b\"]},{\"h\":\"\",\"s\":[]}]}\n", string(files[VariantsFile]))
}

func TestNormalizeStopName(t *testing.T) {
	for in, want := range map[string]string{
		"Árpád híd":                          "arpad hid",
		"Déli pályaudvar M":                  "deli palyaudvar",
		"Kelenföld vasútállomás":             "kelenfold",
		"Móricz Zsigmond körtér (Gellért) H": "moricz zsigmond korter",
		"  a   b  ":                          "a b",
		"Örs vezér tere":                     "ors vezer tere",
		// JavaScript's \s and trim(), as in the old generator: \v and NBSP are spaces, U+0085 is not.
		"X\vY":            "x y",
		"A\u00a0B ":       "a b",
		"\u0085G\u0085":   "\u0085g\u0085",
		"\ufeffBOM\u3000": "bom",
	} {
		require.Equal(t, want, NormalizeStopName(in), in)
	}
}

func TestBuildCarriesOverFailedRouteAndRejectsDegraded(t *testing.T) {
	srv, _ := fakeFutar(t, "")
	prev, err := testGen(srv.URL).Build(context.Background(), nil)
	require.NoError(t, err)

	srv2, _ := fakeFutar(t, "BKK_0005")
	c, err := testGen(srv2.URL).Build(context.Background(), prev)
	require.NoError(t, err)
	require.Len(t, c.Routes, 120, "the failed route keeps its previous entry")
	require.Contains(t, c.Variants, "BKK_0005")

	// Without a previous copy the failed route is simply missing, and 119 < 120 * 0.95 is not hit,
	// but a feed that loses a lot is refused against the baseline.
	bigPrev := &Catalog{Routes: make([]Route, 200), Stops: prev.Stops, Variants: prev.Variants}
	_, err = testGen(srv.URL).Build(context.Background(), bigPrev)
	require.ErrorContains(t, err, "below the current catalog")
}

func TestBuildErrorsNeverLeakTheKey(t *testing.T) {
	g := testGen("http://127.0.0.1:1")
	g.Client = &http.Client{}
	_, err := get[locData](context.Background(), g, "stops-for-location", nil)
	require.Error(t, err)
	require.NotContains(t, err.Error(), "secret")
}

func TestBuildNeedsKey(t *testing.T) {
	_, err := (&Generator{}).Build(context.Background(), nil)
	require.Error(t, err)
}

func raw(t *testing.T) map[string][]byte {
	t.Helper()
	srv, _ := fakeFutar(t, "")
	c, err := testGen(srv.URL).Build(context.Background(), nil)
	require.NoError(t, err)
	files, err := c.Encode()
	require.NoError(t, err)
	return files
}

func TestStorePersistsAcrossRestart(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "catalog")
	s := NewStore(dir)
	require.Nil(t, s.Current())
	files := raw(t)
	built := time.Unix(1_800_000_000, 0)
	require.NoError(t, s.Save(built, files))
	require.NoError(t, s.Save(built.Add(time.Hour), files))

	entries, _ := os.ReadDir(dir)
	require.Len(t, entries, 2, "one set plus the pointer: older sets are removed")

	s2 := NewStore(dir)
	snap := s2.Current()
	require.NotNil(t, snap)
	require.Equal(t, built.Add(time.Hour).Unix(), snap.Built.Unix())
	require.Equal(t, files[RoutesFile], snap.Files[RoutesFile].Raw)
}

func TestStoreIgnoresCorruptSet(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "catalog")
	s := NewStore(dir)
	require.NoError(t, s.Save(time.Unix(1_800_000_000, 0), raw(t)))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "set-1800000000", RoutesFile), []byte("[]"), 0o644))
	require.Nil(t, NewStore(dir).Current(), "a degraded copy on disk is not served")
}

func get1(t *testing.T, s *Store, method, name string, hdr map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, "/catalog/"+name, nil)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	require.NoError(t, serve(s, rec, req, name))
	return rec
}

func TestServe(t *testing.T) {
	s := NewStore(t.TempDir())

	rec := get1(t, s, http.MethodGet, RoutesFile, nil)
	require.Equal(t, http.StatusServiceUnavailable, rec.Code)
	require.NotEmpty(t, rec.Header().Get("Retry-After"))

	files := raw(t)
	require.NoError(t, s.Save(time.Unix(1_800_000_000, 0), files))

	rec = get1(t, s, http.MethodGet, "nope.json", nil)
	require.Equal(t, http.StatusNotFound, rec.Code)

	rec = get1(t, s, http.MethodGet, RoutesFile, nil)
	require.Equal(t, http.StatusOK, rec.Code)
	require.Equal(t, files[RoutesFile], rec.Body.Bytes())
	require.Contains(t, rec.Header().Get("Content-Type"), "application/json")
	require.Contains(t, rec.Header().Get("Cache-Control"), "max-age")
	require.NotEmpty(t, rec.Header().Get("Last-Modified"))
	etag := rec.Header().Get("ETag")
	require.NotEmpty(t, etag)
	require.Empty(t, rec.Header().Get("Content-Encoding"))

	rec = get1(t, s, http.MethodGet, RoutesFile, map[string]string{"If-None-Match": etag})
	require.Equal(t, http.StatusNotModified, rec.Code)
	require.Empty(t, rec.Body.Bytes())

	rec = get1(t, s, http.MethodGet, RoutesFile, map[string]string{"If-None-Match": `"other"`})
	require.Equal(t, http.StatusOK, rec.Code)

	rec = get1(t, s, http.MethodGet, RoutesFile, map[string]string{"If-Modified-Since": time.Unix(1_800_000_000, 0).UTC().Format(http.TimeFormat)})
	require.Equal(t, http.StatusNotModified, rec.Code)

	rec = get1(t, s, http.MethodHead, RoutesFile, nil)
	require.Equal(t, http.StatusOK, rec.Code)
	require.Empty(t, rec.Body.Bytes())

	rec = get1(t, s, http.MethodGet, StopsFile, map[string]string{"Accept-Encoding": "br, gzip;q=0.8"})
	require.Equal(t, "gzip", rec.Header().Get("Content-Encoding"))
	zr, err := gzip.NewReader(rec.Body)
	require.NoError(t, err)
	body, err := io.ReadAll(zr)
	require.NoError(t, err)
	require.Equal(t, files[StopsFile], body)
	gzTag := rec.Header().Get("ETag")
	require.NotEqual(t, etag, gzTag)
	// The validator of either encoding keeps matching.
	rec = get1(t, s, http.MethodGet, StopsFile, map[string]string{"If-None-Match": `W/` + gzTag})
	require.Equal(t, http.StatusNotModified, rec.Code)

	rec = get1(t, s, http.MethodGet, StopsFile, map[string]string{"Accept-Encoding": "gzip;q=0"})
	require.Empty(t, rec.Header().Get("Content-Encoding"))

	rec = get1(t, s, http.MethodPost, StopsFile, nil)
	require.Equal(t, http.StatusMethodNotAllowed, rec.Code)
}

func TestRunBuildsOnBootKeepsLastGoodAndRetries(t *testing.T) {
	srv, _ := fakeFutar(t, "")
	store := NewStore(t.TempDir())
	svc := NewService(store, testGen(srv.URL))
	svc.Every, svc.RetryStart, svc.RetryMax = 50*time.Millisecond, 5*time.Millisecond, 10*time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { svc.Run(ctx); close(done) }()
	require.Eventually(t, func() bool { return store.Current() != nil }, 5*time.Second, 5*time.Millisecond)
	first := store.Current()

	// Break the upstream: the last good copy stays and later builds keep failing quietly.
	srv.Close()
	time.Sleep(150 * time.Millisecond)
	require.Same(t, first, store.Current())

	cancel()
	<-done
}

func TestRunWaitsWhenCopyIsFresh(t *testing.T) {
	srv, calls := fakeFutar(t, "")
	store := NewStore(t.TempDir())
	require.NoError(t, store.Save(time.Now(), raw(t)))
	before := calls.Load()
	svc := NewService(store, testGen(srv.URL))
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	svc.Run(ctx)
	require.Equal(t, before, calls.Load(), "a fresh copy is not rebuilt on boot")
}
