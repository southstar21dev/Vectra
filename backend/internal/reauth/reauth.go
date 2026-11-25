// Package reauth gates sensitive account actions (email change, account
// deletion, passkey enrolment) on the caller's current password, so a leaked
// or stolen session token alone cannot perform them.
package reauth

import (
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/limits"
	"vectra/pb/internal/ratelimit"
)

// UserLimiter / IPLimiter bound password checks (limits.ReauthUser and
// limits.ReauthIP): each one is a password-guessing oracle for whoever holds
// the session token. All re-auth routes share one bucket per user and one per
// client IP, so spreading guesses across endpoints buys no extra attempts.
// They are vars so tests can substitute fresh ones (see reauthtest.Use).
var (
	UserLimiter = ratelimit.New(limits.ReauthUser.Max, limits.ReauthUser.Window)
	IPLimiter   = ratelimit.New(limits.ReauthIP.Max, limits.ReauthIP.Window)
)

// Check verifies that the authenticated caller supplied their current
// password. It returns nil on success, or an API error ready to be returned
// from the handler: 401 without a session, 429 once the caller's user or IP
// bucket is spent, 400 for a wrong password. The stored record is reloaded so
// a password changed since the token was issued is the one checked.
func Check(e *core.RequestEvent, currentPassword string) error {
	if e.Auth == nil || e.Auth.Id == "" {
		return e.UnauthorizedError("Authentication required", nil)
	}
	now := time.Now()
	ip := ratelimit.ClientIP(e)
	if Blocked(e.Auth.Id, ip, now) {
		return e.TooManyRequestsError("Too many attempts. Please wait a while and try again.", nil)
	}
	user, err := e.App.FindRecordById(e.Auth.Collection(), e.Auth.Id)
	if err != nil {
		return e.UnauthorizedError("Authentication required", err)
	}
	if currentPassword == "" || !user.ValidatePassword(currentPassword) {
		Fail(e.Auth.Id, ip, now)
		return e.BadRequestError("Current password is incorrect", nil)
	}
	return nil
}

// Blocked reports whether the caller's user or IP bucket is spent. Only
// failed password checks count against the buckets (see Fail), so successful
// checks, such as passkey enrolment retries, never lock a user out.
func Blocked(userID, ip string, now time.Time) bool {
	return IPLimiter.Exhausted(ip, now) || UserLimiter.Exhausted(userID, now)
}

// Fail records one failed password check against the user and IP buckets.
func Fail(userID, ip string, now time.Time) {
	IPLimiter.Allow(ip, now)
	UserLimiter.Allow(userID, now)
}

// RegisterPasswordChangeGuard puts PocketBase's own password change (PATCH
// users/{id} with oldPassword) under the same budget as Check. That endpoint
// is otherwise a second password-guessing oracle for a stolen session token.
// Requests that do not touch password fields are unaffected.
func RegisterPasswordChangeGuard(app core.App) {
	app.OnRecordUpdateRequest("users").BindFunc(func(e *core.RecordRequestEvent) error {
		info, err := e.RequestInfo()
		if err != nil || !touchesPassword(info.Body) {
			return e.Next()
		}
		now := time.Now()
		ip := ratelimit.ClientIP(e.RequestEvent)
		if Blocked(e.Record.Id, ip, now) {
			return e.TooManyRequestsError("Too many attempts. Please wait a while and try again.", nil)
		}
		if err := e.Next(); err != nil {
			Fail(e.Record.Id, ip, now)
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
