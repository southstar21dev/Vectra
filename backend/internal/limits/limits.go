// Package limits is the one table of the in-process rate limits of the
// /api/vectra routes and the auth guards. Each owning package builds its
// limiter from these values (the limiter itself stays a package var so tests
// can swap it), so "what are the limits?" has a single answer here.
//
// Not covered: PocketBase's own limiter rules, which live in the settings
// table (seeded by migrations 9 and 12, then owned by the dashboard); see
// "Rate limits" in backend/README.md for the expected dashboard state. All
// in-process state is lost on restart or deploy.
package limits

import "time"

// Limit is a fixed-window budget: at most Max actions per key per Window.
type Limit struct {
	Max    int
	Window time.Duration
}

var (
	// ReauthUser / ReauthIP bound failed current-password checks (account
	// delete, email change, passkey enrolment), shared by all those routes.
	// Keys: user id, client IP.
	ReauthUser = Limit{Max: 5, Window: 15 * time.Minute}
	ReauthIP   = Limit{Max: 20, Window: 15 * time.Minute}

	// AuthIdentityFailures / AuthIPFailures bound failed password logins per
	// account identity and per client address. Keys: identity, IP (v6 /64).
	AuthIdentityFailures = Limit{Max: 10, Window: 15 * time.Minute}
	AuthIPFailures       = Limit{Max: 30, Window: 15 * time.Minute}

	// AuthSignups / AuthResets bound sign-ups and reset mails per client
	// address (IPv4, or IPv6 /64).
	AuthSignups = Limit{Max: 30, Window: 10 * time.Minute}
	AuthResets  = Limit{Max: 10, Window: 10 * time.Minute}

	// CryptoValues is each user's budget of values per window on /crypto.
	// Key: user id. Every value or part list costs one unit.
	CryptoValues = Limit{Max: 200_000, Window: 10 * time.Minute}

	// NeptunReplace caps /neptun/replace calls per user.
	NeptunReplace = Limit{Max: 10, Window: time.Minute}

	// PasskeyLoginStart caps public login ceremonies begun per client IP.
	PasskeyLoginStart = Limit{Max: 30, Window: time.Minute}

	// PushSend caps /push/send calls per non-superuser caller.
	PushSend = Limit{Max: 20, Window: time.Minute}

	// FutarPerIP caps Futár proxy calls per client IP; FutarUpstream is the
	// process-wide ceiling on calls to the Futár API, whoever asks.
	FutarPerIP    = Limit{Max: 300, Window: time.Minute}
	FutarUpstream = Limit{Max: 1500, Window: time.Minute}
)
