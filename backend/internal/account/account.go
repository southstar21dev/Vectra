package account

import (
	"errors"
	"strings"
	"time"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/ratelimit"
)

// errInvalidPassword is returned when the caller's current password does not
// match the stored hash.
var errInvalidPassword = errors.New("current password is incorrect")

const (
	// emailChangeWindow / the per-key caps below bound EmailHandler, which
	// checks the current password and so is otherwise a password-guessing
	// oracle for a stolen session token. Each user and each client IP gets its
	// own bucket.
	emailChangeWindow     = 15 * time.Minute
	emailChangePerUserMax = 5
	emailChangePerIPMax   = 20
)

// emailUserLimiter / emailIPLimiter are vars so tests can substitute fresh ones.
var (
	emailUserLimiter = ratelimit.New(emailChangePerUserMax, emailChangeWindow)
	emailIPLimiter   = ratelimit.New(emailChangePerIPMax, emailChangeWindow)
)

var ownedCollections = []string{
	"schedule_events", "neptun_schedule_events", "schedule_items",
	"transit_trackers", "course_tracker_data", "trip_plans",
	"push_subscriptions", "passkeys", "passkey_challenges",
}

// deleteAccount removes all of userID's owned records across the cascade
// collections, then the auth record itself. It must be called with an app
// scoped to a transaction so every delete commits or rolls back together.
func deleteAccount(app core.App, userID string) error {
	for _, name := range ownedCollections {
		if _, err := app.FindCollectionByNameOrId(name); err != nil {
			continue // collection may not exist in a partial schema
		}
		records, err := app.FindRecordsByFilter(name, "user = {:u}", "", 0, 0, dbx.Params{"u": userID})
		if err != nil {
			return err
		}
		for _, r := range records {
			if err := app.Delete(r); err != nil {
				return err
			}
		}
	}
	user, err := app.FindRecordById("users", userID)
	if err != nil {
		return err
	}
	return app.Delete(user)
}

// Handler implements POST /api/vectra/account/delete (JWT-gated): delete the
// caller's owned records, then the auth record itself, in one transaction.
func Handler(e *core.RequestEvent) error {
	userID := e.Auth.Id
	err := e.App.RunInTransaction(func(txApp core.App) error {
		return deleteAccount(txApp, userID)
	})
	if err != nil {
		return e.InternalServerError("Account deletion failed", err)
	}
	return e.JSON(200, map[string]any{"deleted": true})
}

// ChangeEmail verifies the caller's current password, then switches the auth
// record's email instantly. This deliberately bypasses PocketBase's
// request/confirm-email-change flow (which needs SMTP): the app's previous
// behavior switched the address immediately without a confirmation link, and
// this route preserves that.
//
// Nobody has proven control of the new address, so it is stored unverified
// (password login does not require verification). Re-submitting the current
// address is a no-op and keeps the existing verified state.
func ChangeEmail(app core.App, userID, currentPassword, newEmail string) error {
	user, err := app.FindRecordById("users", userID)
	if err != nil {
		return err
	}
	if !user.ValidatePassword(currentPassword) {
		return errInvalidPassword
	}
	if strings.EqualFold(strings.TrimSpace(newEmail), user.Email()) {
		return nil
	}
	user.SetEmail(newEmail)
	user.SetVerified(false)
	return app.Save(user)
}

// EmailHandler implements POST /api/vectra/account/email (JWT-gated): reads
// {currentPassword, newEmail}, verifies the password, and switches the email
// for the authenticated user only (the id is never taken from the body).
func EmailHandler(e *core.RequestEvent) error {
	var body struct {
		CurrentPassword string `json:"currentPassword"`
		NewEmail        string `json:"newEmail"`
	}
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if e.Auth == nil || e.Auth.Id == "" {
		return e.UnauthorizedError("Authentication required", nil)
	}
	now := time.Now()
	if !emailIPLimiter.Allow(ratelimit.ClientIP(e), now) || !emailUserLimiter.Allow(e.Auth.Id, now) {
		return e.TooManyRequestsError("Too many email change attempts. Please wait a while and try again.", nil)
	}
	err := ChangeEmail(e.App, e.Auth.Id, body.CurrentPassword, body.NewEmail)
	if errors.Is(err, errInvalidPassword) {
		return e.BadRequestError("Current password is incorrect", nil)
	}
	if err != nil {
		return e.BadRequestError("Could not update email", err)
	}
	return e.JSON(200, map[string]any{"ok": true})
}
