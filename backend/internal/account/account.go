package account

import (
	"errors"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/reauth"
)

// errInvalidPassword is returned when the caller's current password does not
// match the stored hash.
var errInvalidPassword = errors.New("current password is incorrect")

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

// Handler implements POST /api/vectra/account/delete (JWT-gated): reads
// {currentPassword}, verifies it (rate-limited, see reauth.Check), then deletes
// the caller's owned records and the auth record itself in one transaction. The
// password check keeps a leaked session token from deleting the account.
func Handler(e *core.RequestEvent) error {
	var body struct {
		CurrentPassword string `json:"currentPassword"`
	}
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if err := reauth.Check(e, body.CurrentPassword); err != nil {
		return err
	}
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
	// The shared re-auth gate rate-limits the password check per user and IP;
	// ChangeEmail re-validates it as part of the change itself.
	if err := reauth.Check(e, body.CurrentPassword); err != nil {
		return err
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
