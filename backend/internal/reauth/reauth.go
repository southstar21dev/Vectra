// Package reauth gates sensitive account actions (email change, account
// deletion, passkey enrolment) on the caller's current password, so a leaked
// or stolen session token alone cannot perform them.
package reauth

import (
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/ratelimit"
)

// Guard holds the two budgets that bound password checks (limits.ReauthUser
// and limits.ReauthIP): each check is a password-guessing oracle for whoever
// holds the session token. Every re-auth route shares one Guard, so one bucket
// per user and one per client IP covers all of them and spreading guesses
// across endpoints buys no extra attempts. main builds the Guard once; tests
// build their own.
type Guard struct {
	user, ip *ratelimit.Limiter
}

// NewGuard returns a Guard with the given per-user and per-IP limiters.
func NewGuard(user, ip *ratelimit.Limiter) *Guard {
	return &Guard{user: user, ip: ip}
}

// Check verifies that the authenticated caller supplied their current
// password. It returns nil on success, or an API error ready to be returned
// from the handler: 401 without a session, 429 once the caller's user or IP
// bucket is spent, 400 for a wrong password. The stored record is reloaded so
// a password changed since the token was issued is the one checked.
func (g *Guard) Check(e *core.RequestEvent, currentPassword string) error {
	if e.Auth == nil || e.Auth.Id == "" {
		return e.UnauthorizedError("Authentication required", nil)
	}
	now := time.Now()
	ip := ratelimit.ClientIP(e)
	if g.Blocked(e.Auth.Id, ip, now) {
		return e.TooManyRequestsError("Too many attempts. Please wait a while and try again.", nil)
	}
	user, err := e.App.FindRecordById(e.Auth.Collection(), e.Auth.Id)
	if err != nil {
		return e.UnauthorizedError("Authentication required", err)
	}
	if currentPassword == "" || !user.ValidatePassword(currentPassword) {
		g.Fail(e.Auth.Id, ip, now)
		return e.BadRequestError("Current password is incorrect", nil)
	}
	return nil
}

// Blocked reports whether the caller's user or IP bucket is spent. Only
// failed password checks count against the buckets (see Fail), so successful
// checks, such as passkey enrolment retries, never lock a user out.
func (g *Guard) Blocked(userID, ip string, now time.Time) bool {
	return g.ip.Exhausted(ip, now) || g.user.Exhausted(userID, now)
}

// Fail records one failed password check against the user and IP buckets.
func (g *Guard) Fail(userID, ip string, now time.Time) {
	g.ip.Allow(ip, now)
	g.user.Allow(userID, now)
}

// RegisterPasswordChangeGuard puts PocketBase's own password change (PATCH
// users/{id} with oldPassword) under the same budget as Check. That endpoint
// is otherwise a second password-guessing oracle for a stolen session token.
// Requests that do not touch password fields are unaffected.
func RegisterPasswordChangeGuard(app core.App, g *Guard) {
	app.OnRecordUpdateRequest("users").BindFunc(func(e *core.RecordRequestEvent) error {
		info, err := e.RequestInfo()
		if err != nil || !touchesPassword(info.Body) {
			return e.Next()
		}
		now := time.Now()
		ip := ratelimit.ClientIP(e.RequestEvent)
		if g.Blocked(e.Record.Id, ip, now) {
			return e.TooManyRequestsError("Too many attempts. Please wait a while and try again.", nil)
		}
		if err := e.Next(); err != nil {
			g.Fail(e.Record.Id, ip, now)
			return err
		}
		return nil
	})
}

func touchesPassword(body map[string]any) bool {
	for _, k := range []string{"password", "passwordConfirm", "oldPassword"} {
		if _, ok := body[k]; ok {
			return true
		}
	}
	return false
}
