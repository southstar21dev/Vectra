package catalog

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"
)

const (
	// cacheControl lets clients and the CDN reuse a copy for an hour and keep
	// serving it for a day while they revalidate; the catalog changes daily.
	cacheControl = "public, max-age=3600, stale-while-revalidate=86400"
	// retryAfterSeconds is sent with the 503 before the first build finishes.
	retryAfterSeconds = 120
)

// Handler serves GET/HEAD /catalog/{file}. It is public on purpose: the
// catalog is public transit data. 503 with Retry-After only while no copy has
// ever been built.
func Handler(store *Store) func(e *core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		return serve(store, e.Response, e.Request, e.Request.PathValue("file"))
	}
}

func serve(store *Store, w http.ResponseWriter, r *http.Request, name string) error {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		w.WriteHeader(http.StatusMethodNotAllowed)
		return nil
	}
	known := false
	for _, f := range Files {
		if f == name {
			known = true
		}
	}
	if !known {
		http.Error(w, "not found", http.StatusNotFound)
		return nil
	}
	snap := store.Current()
	if snap == nil {
		w.Header().Set("Retry-After", strconv.Itoa(retryAfterSeconds))
		w.Header().Set("Cache-Control", "no-store")
		http.Error(w, "the transit catalog is being built, try again shortly", http.StatusServiceUnavailable)
		return nil
	}
	f := snap.Files[name]
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("Cache-Control", cacheControl)
	h.Set("Last-Modified", snap.Built.UTC().Format(http.TimeFormat))
	h.Set("X-Content-Type-Options", "nosniff")
	h.Add("Vary", "Accept-Encoding")

	gz := acceptsGzip(r.Header.Get("Accept-Encoding"))
	etag, body := f.ETag, f.Raw
	if gz {
		etag, body = f.GzipETag, f.Gzip
		h.Set("Content-Encoding", "gzip")
	}
	h.Set("ETag", etag)

	if inm := r.Header.Get("If-None-Match"); inm != "" {
		if etagMatches(inm, f) {
			// A 304 repeats the validators, not the encoding or length.
			h.Del("Content-Encoding")
			w.WriteHeader(http.StatusNotModified)
			return nil
		}
	} else if ims := r.Header.Get("If-Modified-Since"); ims != "" {
		if t, err := http.ParseTime(ims); err == nil && !snap.Built.Truncate(time.Second).After(t) {
			h.Del("Content-Encoding")
			w.WriteHeader(http.StatusNotModified)
			return nil
		}
	}
	h.Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(http.StatusOK)
	if r.Method == http.MethodGet {
		_, _ = w.Write(body)
	}
	return nil
}

// etagMatches reports whether an If-None-Match header names this file in
// either encoding (weak validators compare equal, "*" matches anything).
func etagMatches(header string, f *File) bool {
	for _, part := range strings.Split(header, ",") {
		t := strings.TrimPrefix(strings.TrimSpace(part), "W/")
		if t == "*" || t == f.ETag || t == f.GzipETag {
			return true
		}
	}
	return false
}

func acceptsGzip(header string) bool {
	for _, part := range strings.Split(header, ",") {
		name, params, _ := strings.Cut(strings.TrimSpace(part), ";")
		if !strings.EqualFold(strings.TrimSpace(name), "gzip") {
			continue
		}
		params = strings.ReplaceAll(strings.ToLower(params), " ", "")
		if q, ok := strings.CutPrefix(params, "q="); ok {
			if v, err := strconv.ParseFloat(q, 64); err == nil && v == 0 {
				return false
			}
		}
		return true
	}
	return false
}
