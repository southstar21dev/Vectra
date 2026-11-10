package passkeys

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"

	"vectra/pb/internal/ratelimit"
	"vectra/pb/internal/reauth"
)

const (
	passkeysCollection   = "passkeys"
	challengesCollection = "passkey_challenges"

	challengeTypeRegistration   = "registration"
	challengeTypeAuthentication = "authentication"

	// challengeTTL bounds how long a client has to complete a ceremony. It is
	// enforced server-side at consume time (webauthn's own expiry is only set
	// when Config.Timeouts.*.Enforce is enabled, which we do not rely on).
	challengeTTL = 5 * time.Minute

	defaultFriendlyName = "Passkey"
	// maxFriendlyNameRunes bounds the stored label: the passkeys field rejects
	// long values, and that would surface as a 500 after the single-use
	// registration challenge has already been consumed.
	maxFriendlyNameRunes = 64

	// challengeCleanupJobID / challengeCleanupSchedule drive the periodic
	// reclamation of expired challenge rows. Login/start is public and inserts
	// a challenge per call, and authentication challenges carry no user
	// relation (so cascade delete never reclaims them); without this the table
	// grows without bound.
	challengeCleanupJobID    = "passkey_challenges_cleanup"
	challengeCleanupSchedule = "*/10 * * * *" // every 10 minutes

	// loginStartMaxPerWindow caps how many public login ceremonies one client
	// (keyed by remote IP) may begin per loginStartWindow, so a single caller
	// cannot flood passkey_challenges.
	loginStartMaxPerWindow = 30
	loginStartWindow       = time.Minute
)

// loginStartLimiter is the process-wide fixed-window limiter guarding the
// public LoginStartHandler. It is a var so tests can substitute a fresh one.
var loginStartLimiter = ratelimit.New(loginStartMaxPerWindow, loginStartWindow)

var (
	errChallengeNotFound = errors.New("challenge not found")
	errChallengeExpired  = errors.New("challenge expired")
	errChallengeType     = errors.New("challenge type mismatch")
	errChallengeOwner    = errors.New("challenge belongs to another user")
)

// newWebAuthn builds the WebAuthn relying-party instance from the deployment
// environment. RPID/RPOrigins MUST mirror Supabase's webauthn_rp_id /
// webauthn_rp_origins (including any android:apk-key-hash: origin) or existing
// Supabase-migrated passkeys will stop verifying.
//
// User verification is required for both ceremonies: passkeys are the only
// factor on this login path, so a bare user-presence tap must not suffice.
// Every discoverable (resident-key) passkey the app registers comes from a
// platform authenticator or a PIN-protected security key, which always set UV.
func newWebAuthn() (*webauthn.WebAuthn, error) {
	return webauthn.New(&webauthn.Config{
		RPDisplayName: os.Getenv("WEBAUTHN_RP_DISPLAY_NAME"),
		RPID:          os.Getenv("WEBAUTHN_RP_ID"),
		RPOrigins:     splitOrigins(os.Getenv("WEBAUTHN_RP_ORIGINS")),
		AuthenticatorSelection: protocol.AuthenticatorSelection{
			UserVerification: protocol.VerificationRequired,
		},
	})
}

// splitOrigins parses a comma-separated origins list, trimming whitespace and
// dropping empty entries so a trailing comma or an unset variable yields a
// clear config error from webauthn.New instead of a silent [""] origin.
func splitOrigins(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// truncateFriendlyName cuts name to maxFriendlyNameRunes runes, never inside a
// multi-byte character.
func truncateFriendlyName(name string) string {
	r := []rune(name)
	if len(r) <= maxFriendlyNameRunes {
		return name
	}
	return strings.TrimSpace(string(r[:maxFriendlyNameRunes]))
}

// webAuthnUser adapts a PocketBase users record to webauthn.User.
type webAuthnUser struct {
	record *core.Record
	creds  []webauthn.Credential
}

func (u *webAuthnUser) WebAuthnID() []byte { return webAuthnID(u.record) }

func (u *webAuthnUser) WebAuthnName() string {
	if name := u.record.GetString("full_name"); name != "" {
		return name
	}
	return u.record.GetString("email")
}

func (u *webAuthnUser) WebAuthnDisplayName() string {
	if name := u.record.GetString("full_name"); name != "" {
		return name
	}
	if email := u.record.GetString("email"); email != "" {
		return email
	}
	return u.record.Id
}

func (u *webAuthnUser) WebAuthnCredentials() []webauthn.Credential { return u.creds }

// webAuthnID returns the user handle for a record. GoTrue (Supabase) set an
// authenticator's user handle to []byte(uuid.String()) at registration, so
// migrated credentials carry the original Supabase UUID *string* (36 bytes) as
// their handle. We must return exactly those bytes: go-webauthn compares the
// assertion's userHandle against WebAuthnID() and rejects the login when they
// differ. For accounts created on PocketBase (no legacy_id) the stable record
// id is used instead.
func webAuthnID(r *core.Record) []byte {
	if legacy := r.GetString("legacy_id"); legacy != "" {
		return []byte(legacy)
	}
	return []byte(r.Id)
}

// loadWebAuthnUser loads a users record and all of its credentials as a
// webauthn.User.
func loadWebAuthnUser(app core.App, userID string) (*webAuthnUser, error) {
	rec, err := app.FindRecordById("users", userID)
	if err != nil {
		return nil, err
	}
	creds, err := loadCredentials(app, userID)
	if err != nil {
		return nil, err
	}
	return &webAuthnUser{record: rec, creds: creds}, nil
}

func loadCredentials(app core.App, userID string) ([]webauthn.Credential, error) {
	recs, err := app.FindRecordsByFilter(passkeysCollection, "user = {:u}", "", 0, 0, dbx.Params{"u": userID})
	if err != nil {
		return nil, err
	}
	out := make([]webauthn.Credential, 0, len(recs))
	for _, r := range recs {
		cred, err := ToWebAuthnCredential(r)
		if err != nil {
			return nil, err
		}
		out = append(out, *cred)
	}
	return out, nil
}

// resolveOwnerRecord resolves the account that owns the credential presented in
// a discoverable login by looking up the passkeys record by credential ID (the
// authoritative link). The userHandle is not used to find the account:
// go-webauthn's validateLogin requires the credential to be in the resolved
// user's own credential list (and the handle to equal that user's
// WebAuthnID(), which already covers Supabase-migrated legacy_id handles), so
// a credential without a passkeys row can never log in no matter which user a
// handle-based lookup returned.
func resolveOwnerRecord(app core.App, rawID []byte) (*core.Record, error) {
	credID := base64.RawURLEncoding.EncodeToString(rawID)
	credRecs, err := app.FindRecordsByFilter(passkeysCollection, "credential_id = {:cid}", "", 1, 0, dbx.Params{"cid": credID})
	if err == nil && len(credRecs) > 0 {
		if userID := credRecs[0].GetString("user"); userID != "" {
			if user, err := app.FindRecordById("users", userID); err == nil {
				return user, nil
			}
		}
	}
	return nil, errors.New("no user found for the presented credential")
}

// storeChallenge persists a ceremony's SessionData so the matching verify step
// can retrieve it. For registration the owner is known (userID); for
// discoverable login it is not, so userID is empty.
func storeChallenge(app core.App, challengeType, userID string, session *webauthn.SessionData) (*core.Record, error) {
	col, err := app.FindCollectionByNameOrId(challengesCollection)
	if err != nil {
		return nil, err
	}
	rec := core.NewRecord(col)
	rec.Set("challenge_type", challengeType)
	if userID != "" {
		rec.Set("user", userID)
	}
	rec.Set("session_data", session)
	rec.Set("expires_at", time.Now().Add(challengeTTL))
	if err := app.Save(rec); err != nil {
		return nil, err
	}
	return rec, nil
}

// consumeChallenge loads a challenge, deletes it (single-use, so a captured
// response cannot be replayed), then validates its type, owner and expiry. The
// delete happens before validation so even a failed verify burns the
// challenge.
//
// The delete is a raw statement whose affected-row count decides who consumed
// the challenge: two concurrent verifies can both load the row, but only one
// DELETE removes it, and the loser gets errChallengeNotFound instead of
// replaying the same challenge.
//
// userID is the caller that must own the challenge (registration); pass "" for
// discoverable login, whose challenges have no user.
func consumeChallenge(app core.App, id, challengeType, userID string) (*webauthn.SessionData, error) {
	rec, err := app.FindRecordById(challengesCollection, id)
	if err != nil {
		return nil, errChallengeNotFound
	}
	res, err := app.NonconcurrentDB().
		NewQuery("DELETE FROM {{" + challengesCollection + "}} WHERE [[id]] = {:id}").
		Bind(dbx.Params{"id": rec.Id}).
		Execute()
	if err != nil {
		return nil, err
	}
	if n, err := res.RowsAffected(); err != nil || n != 1 {
		return nil, errChallengeNotFound
	}
	if rec.GetString("challenge_type") != challengeType {
		return nil, errChallengeType
	}
	if rec.GetString("user") != userID {
		return nil, errChallengeOwner
	}
	if !rec.GetDateTime("expires_at").Time().After(time.Now()) {
		return nil, errChallengeExpired
	}
	raw, err := types.ParseJSONRaw(rec.Get("session_data"))
	if err != nil {
		return nil, err
	}
	var session webauthn.SessionData
	if err := json.Unmarshal(raw, &session); err != nil {
		return nil, err
	}
	return &session, nil
}

// cleanupExpiredChallenges deletes every challenge whose expires_at is before
// now, covering both registration and authentication ceremonies. It returns
// the number of rows removed.
func cleanupExpiredChallenges(app core.App) (int, error) {
	const batch = 500
	// Format like PocketBase stores DateFields (UTC, DefaultDateLayout) so the
	// filter compares text correctly; binding a raw time.Time uses a different
	// layout and mis-orders every same-day row.
	now := time.Now().UTC().Format(types.DefaultDateLayout)
	deleted := 0
	for {
		recs, err := app.FindRecordsByFilter(
			challengesCollection,
			"expires_at < {:now}",
			"",
			batch,
			0,
			dbx.Params{"now": now},
		)
		if err != nil {
			return deleted, err
		}
		if len(recs) == 0 {
			break
		}
		for _, rec := range recs {
			if err := app.Delete(rec); err != nil {
				return deleted, err
			}
			deleted++
		}
		if len(recs) < batch {
			break
		}
	}
	return deleted, nil
}

// RegisterChallengeCleanup schedules periodic deletion of expired passkey
// challenges on the app's cron. Call it once during boot.
func RegisterChallengeCleanup(app core.App) {
	app.Cron().MustAdd(challengeCleanupJobID, challengeCleanupSchedule, func() {
		n, err := cleanupExpiredChallenges(app)
		if err != nil {
			app.Logger().Error("Failed to clean up expired passkey challenges", "error", err)
			return
		}
		if n > 0 {
			app.Logger().Info("Cleaned up expired passkey challenges", "count", n)
		}
	})
}

// RegisterRevokeOnCredentialChange deletes a user's passkeys and pending
// registration challenges whenever the account's tokenKey rotates, which
// PocketBase does on a password change, a password-reset confirm and an email
// change. Without it a passkey enrolled by whoever held the account before
// (for example an attacker who signed up with the victim's unverified
// address) would keep logging in after the rightful owner reset the password.
// It mirrors PocketBase's own rule of deleting a record's OTPs on a tokenKey
// change, and runs in the same transaction as the update. Call it once during
// boot.
func RegisterRevokeOnCredentialChange(app core.App) {
	app.OnRecordUpdateExecute("users").BindFunc(func(e *core.RecordEvent) error {
		originalApp := e.App
		return e.App.RunInTransaction(func(txApp core.App) error {
			e.App = txApp
			defer func() { e.App = originalApp }()
			if err := e.Next(); err != nil {
				return err
			}
			if e.Record.Original().IsNew() || e.Record.Original().TokenKey() == e.Record.TokenKey() {
				return nil
			}
			return revokeUserPasskeys(txApp, e.Record.Id)
		})
	})
}

// revokeUserPasskeys deletes every passkey and passkey challenge owned by
// userID.
func revokeUserPasskeys(app core.App, userID string) error {
	for _, name := range []string{passkeysCollection, challengesCollection} {
		recs, err := app.FindRecordsByFilter(name, "user = {:u}", "", 0, 0, dbx.Params{"u": userID})
		if err != nil {
			return err
		}
		for _, r := range recs {
			if err := app.Delete(r); err != nil {
				return err
			}
		}
	}
	return nil
}

// verifyRequest is the body shared by both verify steps. credential is the
// standard WebAuthn PublicKeyCredential JSON produced by the client ceremony.
type verifyRequest struct {
	ChallengeID  string          `json:"challenge_id"`
	Credential   json.RawMessage `json:"credential"`
	FriendlyName string          `json:"friendly_name"`
}

// passkeyJSON renders a passkeys record in the shape the client expects.
func passkeyJSON(r *core.Record) map[string]any {
	out := map[string]any{
		"id":            r.Id,
		"friendly_name": r.GetString("friendly_name"),
		"created_at":    r.GetDateTime("created_at").Time(),
	}
	if last := r.GetDateTime("last_used_at"); !last.IsZero() {
		out["last_used_at"] = last.Time()
	}
	return out
}

// RegistrationStartHandler implements POST /api/vectra/passkeys/register/start.
// Requires auth and the body {currentPassword}: a passkey is a login factor
// that outlives the session, so a stolen session token alone must not be able
// to enrol one (see reauth.Check, which also rate-limits the check). Returns
// {challenge_id, options} where options is the
// PublicKeyCredentialCreationOptions the client feeds to
// navigator.credentials.create({publicKey: options}) or the native plugin.
func RegistrationStartHandler(e *core.RequestEvent) error {
	if e.Auth == nil {
		return e.UnauthorizedError("Authentication required", nil)
	}
	var body struct {
		CurrentPassword string `json:"currentPassword"`
	}
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if err := reauth.Check(e, body.CurrentPassword); err != nil {
		return err
	}
	w, err := newWebAuthn()
	if err != nil {
		return e.InternalServerError("WebAuthn is not configured", err)
	}
	user, err := loadWebAuthnUser(e.App, e.Auth.Id)
	if err != nil {
		return e.InternalServerError("Failed to load user", err)
	}

	creation, session, err := w.BeginRegistration(user,
		webauthn.WithResidentKeyRequirement(protocol.ResidentKeyRequirementRequired),
		webauthn.WithExclusions(webauthn.Credentials(user.WebAuthnCredentials()).CredentialDescriptors()),
	)
	if err != nil {
		return e.BadRequestError("Failed to begin registration", err)
	}

	rec, err := storeChallenge(e.App, challengeTypeRegistration, e.Auth.Id, session)
	if err != nil {
		return e.InternalServerError("Failed to store challenge", err)
	}

	return e.JSON(http.StatusOK, map[string]any{
		"challenge_id": rec.Id,
		"options":      creation.Response,
	})
}

// RegistrationVerifyHandler implements POST /api/vectra/passkeys/register/verify.
// Requires auth. Consumes the challenge, verifies the attestation and stores
// the credential.
func RegistrationVerifyHandler(e *core.RequestEvent) error {
	if e.Auth == nil {
		return e.UnauthorizedError("Authentication required", nil)
	}
	var body verifyRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if body.ChallengeID == "" || len(body.Credential) == 0 {
		return e.BadRequestError("challenge_id and credential are required", nil)
	}

	// The challenge must have been issued to this same caller: a registration
	// challenge started under another session cannot be completed here.
	session, err := consumeChallenge(e.App, body.ChallengeID, challengeTypeRegistration, e.Auth.Id)
	if err != nil {
		return e.BadRequestError("Invalid or expired challenge", err)
	}
	w, err := newWebAuthn()
	if err != nil {
		return e.InternalServerError("WebAuthn is not configured", err)
	}
	user, err := loadWebAuthnUser(e.App, e.Auth.Id)
	if err != nil {
		return e.InternalServerError("Failed to load user", err)
	}
	parsed, err := protocol.ParseCredentialCreationResponseBytes(body.Credential)
	if err != nil {
		return e.BadRequestError("Invalid credential", err)
	}
	cred, err := w.CreateCredential(user, *session, parsed)
	if err != nil {
		return e.BadRequestError("Registration verification failed", err)
	}

	friendlyName := truncateFriendlyName(strings.TrimSpace(body.FriendlyName))
	if friendlyName == "" {
		friendlyName = defaultFriendlyName
	}
	rec, err := StoreCredential(e.App, e.Auth.Id, cred, friendlyName)
	if err != nil {
		return e.InternalServerError("Failed to store credential", err)
	}

	return e.JSON(http.StatusOK, passkeyJSON(rec))
}

// LoginStartHandler implements POST /api/vectra/passkeys/login/start. Public:
// the ceremony runs before a session exists. Returns {challenge_id, options}.
func LoginStartHandler(e *core.RequestEvent) error {
	// Public endpoint that writes a challenge row per call; cap the rate per
	// client so a single caller cannot flood passkey_challenges.
	if !loginStartLimiter.Allow(ratelimit.ClientIP(e), time.Now()) {
		return e.TooManyRequestsError("Too many passkey login attempts. Please wait a moment and try again.", nil)
	}
	w, err := newWebAuthn()
	if err != nil {
		return e.InternalServerError("WebAuthn is not configured", err)
	}
	assertion, session, err := w.BeginDiscoverableLogin()
	if err != nil {
		return e.BadRequestError("Failed to begin login", err)
	}
	rec, err := storeChallenge(e.App, challengeTypeAuthentication, "", session)
	if err != nil {
		return e.InternalServerError("Failed to store challenge", err)
	}
	return e.JSON(http.StatusOK, map[string]any{
		"challenge_id": rec.Id,
		"options":      assertion.Response,
	})
}

// LoginVerifyHandler implements POST /api/vectra/passkeys/login/verify. Public.
// Resolves the owner via the discoverable handler, validates the assertion,
// updates sign_count/last_used_at and mints a normal PocketBase auth token.
func LoginVerifyHandler(e *core.RequestEvent) error {
	var body verifyRequest
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if body.ChallengeID == "" || len(body.Credential) == 0 {
		return e.BadRequestError("challenge_id and credential are required", nil)
	}

	session, err := consumeChallenge(e.App, body.ChallengeID, challengeTypeAuthentication, "")
	if err != nil {
		return e.BadRequestError("Invalid or expired challenge", err)
	}
	w, err := newWebAuthn()
	if err != nil {
		return e.InternalServerError("WebAuthn is not configured", err)
	}
	parsed, err := protocol.ParseCredentialRequestResponseBytes(body.Credential)
	if err != nil {
		return e.BadRequestError("Invalid credential", err)
	}

	handler := func(rawID, _ []byte) (webauthn.User, error) {
		owner, err := resolveOwnerRecord(e.App, rawID)
		if err != nil {
			return nil, err
		}
		return loadWebAuthnUser(e.App, owner.Id)
	}

	validatedUser, cred, err := w.ValidatePasskeyLogin(handler, *session, parsed)
	if err != nil {
		return e.BadRequestError("Authentication failed", err)
	}
	owner, ok := validatedUser.(*webAuthnUser)
	if !ok || owner.record == nil {
		return e.InternalServerError("Failed to resolve authenticated user", nil)
	}
	// go-webauthn only flags a non-increasing signature counter; it does not
	// fail the ceremony. A counter that went backwards (or stalled) on a
	// credential that has counted before means a second copy of the key may
	// exist, so refuse the login rather than mint a session. Authenticators
	// that never count (synced passkeys report 0 forever) never trip this.
	if cred.Authenticator.CloneWarning && cred.Authenticator.SignCount != 0 {
		e.App.Logger().Warn("Rejected passkey login: signature counter did not increase", "user", owner.record.Id)
		return e.BadRequestError("Authentication failed", nil)
	}

	if err := updateCredentialUsage(e.App, cred); err != nil {
		// Non-fatal: the assertion is already valid and the user is authenticated.
		e.App.Logger().Warn("Failed to update passkey usage", "error", err)
	}

	return apis.RecordAuthResponse(e, owner.record, "passkey", nil)
}

// updateCredentialUsage bumps the stored sign counter and last_used_at for the
// credential that just authenticated.
func updateCredentialUsage(app core.App, cred *webauthn.Credential) error {
	credID := base64.RawURLEncoding.EncodeToString(cred.ID)
	recs, err := app.FindRecordsByFilter(passkeysCollection, "credential_id = {:cid}", "", 1, 0, dbx.Params{"cid": credID})
	if err != nil {
		return err
	}
	if len(recs) == 0 {
		return errors.New("credential not found")
	}
	rec := recs[0]
	rec.Set("sign_count", cred.Authenticator.SignCount)
	rec.Set("last_used_at", time.Now())
	return app.Save(rec)
}

// ListHandler implements GET /api/vectra/passkeys. Requires auth.
func ListHandler(e *core.RequestEvent) error {
	if e.Auth == nil {
		return e.UnauthorizedError("Authentication required", nil)
	}
	recs, err := e.App.FindRecordsByFilter(passkeysCollection, "user = {:u}", "-created_at", 0, 0, dbx.Params{"u": e.Auth.Id})
	if err != nil {
		return e.InternalServerError("Failed to list passkeys", err)
	}
	out := make([]map[string]any, 0, len(recs))
	for _, r := range recs {
		out = append(out, passkeyJSON(r))
	}
	return e.JSON(http.StatusOK, out)
}

// DeleteHandler implements DELETE /api/vectra/passkeys/{id}. Requires auth and
// only deletes a passkey owned by the caller.
func DeleteHandler(e *core.RequestEvent) error {
	if e.Auth == nil {
		return e.UnauthorizedError("Authentication required", nil)
	}
	id := e.Request.PathValue("id")
	if id == "" {
		return e.BadRequestError("Missing passkey id", nil)
	}
	rec, err := e.App.FindRecordById(passkeysCollection, id)
	if err != nil {
		return e.NotFoundError("Passkey not found", err)
	}
	if rec.GetString("user") != e.Auth.Id {
		return e.ForbiddenError("Not your passkey", nil)
	}
	if err := e.App.Delete(rec); err != nil {
		return e.InternalServerError("Failed to delete passkey", err)
	}
	return e.NoContent(http.StatusNoContent)
}
