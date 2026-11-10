// Package ratelimit holds the tiny in-process fixed-window limiter shared by
// the public / abuse-prone vectra routes (passkey login start, the Futár proxy,
// the email change endpoint, Neptun replace, push send).
package ratelimit

import (
	"net"
	"sync"
	"time"

	"github.com/pocketbase/pocketbase/core"
)

// maxClients bounds each limiter's in-memory map. Once it is reached, expired
// entries are swept at most once per window (amortized O(1) per call), and if
// the map is still full an arbitrary entry is evicted to admit the new key, so
// memory stays bounded and no call pays an O(n) scan under the mutex on every
// request while the map sits above the cap.
const maxClients = 10_000

// Limiter is a per-key fixed-window counter. It is deliberately in-process
// (like PocketBase's own rate limiter) and lock-guarded for concurrent handler
// use.
type Limiter struct {
	mu        sync.Mutex
	max       int
	window    time.Duration
	cap       int
	lastSweep time.Time
	clients   map[string]windowCounter
}

type windowCounter struct {
	count int
	reset time.Time
}

// New returns a limiter allowing max actions per key per window.
func New(max int, window time.Duration) *Limiter {
	return &Limiter{max: max, window: window, cap: maxClients, clients: map[string]windowCounter{}}
}

// Allow reports whether key may perform another action at now, consuming one
// slot when it can.
func (l *Limiter) Allow(key string, now time.Time) bool {
	return l.AllowN(key, 1, now)
}

// AllowN reports whether key may perform n more actions at now. It is
// all-or-nothing: either all n slots are consumed or none are. n <= 0 always
// succeeds without consuming anything.
func (l *Limiter) AllowN(key string, n int, now time.Time) bool {
	if n <= 0 {
		return true
	}
	if n > l.max {
		return false
	}

	l.mu.Lock()
	defer l.mu.Unlock()

	w, ok := l.clients[key]
	if !ok || !now.Before(w.reset) {
		if !ok {
			l.makeRoom(now)
		}
		l.clients[key] = windowCounter{count: n, reset: now.Add(l.window)}
		return true
	}
	if w.count+n > l.max {
		return false
	}
	w.count += n
	l.clients[key] = w
	return true
}

// makeRoom keeps the map under cap before a new key is inserted. Callers hold
// l.mu.
func (l *Limiter) makeRoom(now time.Time) {
	if len(l.clients) < l.cap {
		return
	}
	// Full sweeps are rate-limited to once per window: entries only expire a
	// window after they were created, so sweeping more often finds little.
	if now.Sub(l.lastSweep) >= l.window {
		l.lastSweep = now
		for k, v := range l.clients {
			if !now.Before(v.reset) {
				delete(l.clients, k)
			}
		}
	}
	// Still full (a flood of distinct live keys): evict one arbitrary entry.
	// That key merely gets a fresh window, which fails open for it rather than
	// locking every new client out.
	for k := range l.clients {
		if len(l.clients) < l.cap {
			break
		}
		delete(l.clients, k)
	}
}

// ClientIP returns the caller's IP for limiter keys. It prefers e.RealIP(),
// which honours the TrustedProxy settings (set from TRUSTED_PROXY, see
// internal/proxytrust) so each client behind the reverse proxy gets its own
// bucket, and falls back to the
// socket address when the event carries no app (direct handler use in tests).
// IPv6 clients are keyed by their /64 (see ClientKey).
func ClientIP(e *core.RequestEvent) string {
	if e.App != nil {
		return ClientKey(e.RealIP())
	}
	return ClientKey(e.RemoteIP())
}

// ClientKey normalises an IP into a limiter key. IPv4 (including IPv4-mapped
// IPv6) addresses are used as-is; native IPv6 addresses are truncated to their
// /64, because a single subscriber is routinely handed a whole /64 and could
// otherwise rotate through 2^64 addresses to get unlimited buckets. Anything
// that does not parse is returned unchanged.
func ClientKey(ip string) string {
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return ip
	}
	if v4 := parsed.To4(); v4 != nil {
		return v4.String()
	}
	return parsed.Mask(net.CIDRMask(64, 128)).String() + "/64"
}
