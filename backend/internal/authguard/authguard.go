// Package authguard adds the account-abuse limits PocketBase's built-in rate
// limiter lacks. That limiter keys on the full IPv6 address, so a single
// subscriber (who owns a whole /64) can rotate source addresses and get a
// fresh bucket per request. These hooks key on ratelimit.ClientIP (IPv6 /64)
// and, for password logins, also on the identity being guessed. It also
// switches off the PocketBase email flows the app never uses, which would
// otherwise let any account mail arbitrary addresses through Vectra's SMTP.
package authguard

import (
	"strings"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/limits"
	"vectra/pb/internal/ratelimit"
)

// Failed password logins are bounded per account identity (whatever addresses
// they come from) and per client address (IPv4, or IPv6 /64), sign-ups and
// reset mails per client address. The values live in internal/limits.
//
// Limiters are the four budgets the guards spend. main builds them with
// NewLimiters; tests build their own.
type Limiters struct {
	IdentityFailures, IPFailures, Signups, Resets *ratelimit.Limiter
}

// NewLimiters builds the limiters from the values in internal/limits.
func NewLimiters() Limiters {
	return Limiters{
		IdentityFailures: limits.AuthIdentityFailures.New(),
		IPFailures:       limits.AuthIPFailures.New(),
		Signups:          limits.AuthSignups.New(),
		Resets:           limits.AuthResets.New(),
	}
}

// Register binds every guard.
func Register(app core.App, l Limiters) {
	registerLoginFailureLimit(app, l)
	registerSignupLimit(app, l)
	registerResetLimit(app, l)
	registerDisabledEmailFlows(app)
}

// registerLoginFailureLimit refuses auth-with-password (users and the
// superuser dashboard login alike) once the identity or the client address has
// used up its failure budget. Only failures count, so a legitimate login is
// never spent against the budget; a blocked request skips the bcrypt check
// entirely.
func registerLoginFailureLimit(app core.App, l Limiters) {
	app.OnRecordAuthWithPasswordRequest().BindFunc(func(e *core.RecordAuthWithPasswordRequestEvent) error {
		now := time.Now()
		ip := ratelimit.ClientIP(e.RequestEvent)
		identity := e.Collection.Name + ":" + strings.ToLower(strings.TrimSpace(e.Identity))
		if l.IPFailures.Exhausted(ip, now) || l.IdentityFailures.Exhausted(identity, now) {
			return e.TooManyRequestsError("Too many failed attempts. Please wait a while and try again.", nil)
		}
		err := e.Next()
		if err != nil {
			l.IPFailures.Allow(ip, now)
			l.IdentityFailures.Allow(identity, now)
		}
		return err
	})
}

func registerSignupLimit(app core.App, l Limiters) {
	app.OnRecordCreateRequest("users").BindFunc(func(e *core.RecordRequestEvent) error {
		if e.RequestEvent != nil && !l.Signups.Allow(ratelimit.ClientIP(e.RequestEvent), time.Now()) {
			return e.TooManyRequestsError("Too many sign-ups. Please wait a while and try again.", nil)
		}
		return e.Next()
	})
}

func registerResetLimit(app core.App, l Limiters) {
	app.OnRecordRequestPasswordResetRequest("users").BindFunc(func(e *core.RecordRequestPasswordResetRequestEvent) error {
		if !l.Resets.Allow(ratelimit.ClientIP(e.RequestEvent), time.Now()) {
			return e.TooManyRequestsError("Too many requests. Please wait a while and try again.", nil)
		}
		return e.Next()
	})
}

// registerDisabledEmailFlows turns off PocketBase's email-change and
// verification mails. The app changes email through /api/vectra/account/email
// and never asks for verification, so these endpoints only served abuse:
// mailing arbitrary addresses from Vectra's sending domain, and telling anyone
// whether an address has an account.
func registerDisabledEmailFlows(app core.App) {
	app.OnRecordRequestEmailChangeRequest().BindFunc(func(e *core.RecordRequestEmailChangeRequestEvent) error {
		return e.NotFoundError("", nil)
	})
	app.OnRecordRequestVerificationRequest().BindFunc(func(e *core.RecordRequestVerificationRequestEvent) error {
		return e.NotFoundError("", nil)
	})
}
