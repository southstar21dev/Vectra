// Package reauth gates sensitive account actions (email change, account
// deletion, passkey enrolment) on the caller's current password, so a leaked
// or stolen session token alone cannot perform them.
package reauth

import (
	"time"

	"github.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/ratelimit"
)

const (
	// Window / PerUserMax / PerIPMax bound password checks: each one is a
	// password-guessing oracle for whoever holds the session token. All
	// re-auth routes share one bucket per user and one per client IP, so
	// spreading guesses across endpoints buys no extra attempts.
	Window     = 15 * time.Minute
	PerUserMax = 5
	PerIPMax   = 20
)

// UserLimiter / IPLimiter are vars so tests can substitute fresh ones.
var (
	UserLimiter = ratelimit.New(PerUserMax, Window)
	IPLimiter   = ratelimit.New(PerIPMax, Window)
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
	if !IPLimiter.Allow(ratelimit.ClientIP(e), now) || !UserLimiter.Allow(e.Auth.Id, now) {
		return e.TooManyRequestsError("Too many attempts. Please wait a while and try again.", nil)
	}
	user, err := e.App.FindRecordById(e.Auth.Collection(), e.Auth.Id)
	if err != nil {
		return e.UnauthorizedError("Authentication required", err)
	}
	if currentPassword == "" || !user.ValidatePassword(currentPassword) {
		return e.BadRequestError("Current password is incorrect", nil)
	}
	return nil
}
