package passkeys

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/protocol/webauthncbor"
	"github.com/go-webauthn/webauthn/protocol/webauthncose"
	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/ratelimit"
	"vectra/pb/internal/reauth"
	_ "vectra/pb/migrations"
)

const (
	testRPID    = "example.org"
	testOrigin  = "https://example.org"
	testRPName  = "Vectra Test"
	migratedUID = "11111111-2222-3333-4444-555555555555"

	// startBody is a register/start body carrying newTestUser's password.
	startBody = `{"currentPassword":"password123"}`
)

// TestMain gives the package's tests roomy re-auth limiters: the real ones
// would trip on the many register/start calls made from one test IP.
func TestMain(m *testing.M) {
	reauth.UserLimiter = ratelimit.New(1000, time.Minute)
	reauth.IPLimiter = ratelimit.New(1000, time.Minute)
	os.Exit(m.Run())
}

// setWebAuthnEnv points the RP config at the test origin. Call before invoking
// any handler that builds the WebAuthn instance.
func setWebAuthnEnv(t *testing.T) {
	t.Helper()
	t.Setenv("WEBAUTHN_RP_ID", testRPID)
	t.Setenv("WEBAUTHN_RP_ORIGINS", testOrigin)
	t.Setenv("WEBAUTHN_RP_DISPLAY_NAME", testRPName)
}

func newTestUser(t *testing.T, app *tests.TestApp, email, legacyID string) *core.Record {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	u := core.NewRecord(users)
	u.Set("email", email)
	u.SetPassword("password123")
	if legacyID != "" {
		u.Set("legacy_id", legacyID)
	}
	require.NoError(t, app.Save(u))
	return u
}

func requestEvent(t *testing.T, app *tests.TestApp, auth *core.Record, method, target, body string, pathValues map[string]string) (*core.RequestEvent, *httptest.ResponseRecorder) {
	t.Helper()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	// Mirror the production router, which wraps every body in a rereadable
	// reader so BindBody + a later RequestInfo/RecordAuthResponse both work.
	req.Body = &router.RereadableReadCloser{ReadCloser: req.Body}
	for k, v := range pathValues {
		req.SetPathValue(k, v)
	}
	rec := httptest.NewRecorder()
	e := &core.RequestEvent{App: app, Auth: auth}
	e.Request = req
	e.Response = rec
	return e, rec
}

// --- test authenticator (ES256 / P-256, attestation "none") -----------------

type testAuthenticator struct {
	credentialID []byte
	privateKey   *ecdsa.PrivateKey
	signCount    uint32
	// skipUV drops the user-verified flag from assertions, modelling a bare
	// user-presence tap.
	skipUV bool
}

func (a *testAuthenticator) assertionFlags() protocol.AuthenticatorFlags {
	if a.skipUV {
		return protocol.FlagUserPresent
	}
	return protocol.FlagUserPresent | protocol.FlagUserVerified
}

func newTestAuthenticator(t *testing.T) *testAuthenticator {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	id := make([]byte, 32)
	_, err = rand.Read(id)
	require.NoError(t, err)
	return &testAuthenticator{credentialID: id, privateKey: priv}
}

func (a *testAuthenticator) cosePublicKey(t *testing.T) []byte {
	t.Helper()
	pub := a.privateKey.PublicKey
	data, err := webauthncbor.Marshal(map[int64]any{
		1:  int64(webauthncose.EllipticKey),
		3:  int64(webauthncose.AlgES256),
		-1: int64(webauthncose.P256),
		-2: pub.X.FillBytes(make([]byte, 32)),
		-3: pub.Y.FillBytes(make([]byte, 32)),
	})
	require.NoError(t, err)
	return data
}

func (a *testAuthenticator) authenticatorData(t *testing.T, flags protocol.AuthenticatorFlags, attestedData []byte) []byte {
	t.Helper()
	hash := sha256.Sum256([]byte(testRPID))
	data := make([]byte, 0, 37+len(attestedData))
	data = append(data, hash[:]...)
	data = append(data, byte(flags))
	data = binary.BigEndian.AppendUint32(data, a.signCount)
	return append(data, attestedData...)
}

func (a *testAuthenticator) attestedCredentialData(t *testing.T) []byte {
	t.Helper()
	data := make([]byte, 16) // AAGUID
	data = binary.BigEndian.AppendUint16(data, uint16(len(a.credentialID)))
	data = append(data, a.credentialID...)
	return append(data, a.cosePublicKey(t)...)
}

func clientDataJSON(t *testing.T, ceremony, challenge string) []byte {
	t.Helper()
	data, err := json.Marshal(map[string]any{
		"type":        ceremony,
		"challenge":   challenge,
		"origin":      testOrigin,
		"crossOrigin": false,
	})
	require.NoError(t, err)
	return data
}

func (a *testAuthenticator) sign(t *testing.T, authData, clientData []byte) []byte {
	t.Helper()
	clientDataHash := sha256.Sum256(clientData)
	signed := append(append([]byte{}, authData...), clientDataHash[:]...)
	digest := sha256.Sum256(signed)
	sig, err := ecdsa.SignASN1(rand.Reader, a.privateKey, digest[:])
	require.NoError(t, err)
	return sig
}

// registrationCredential builds the standard PublicKeyCredential JSON for
// navigator.credentials.create().
func (a *testAuthenticator) registrationCredential(t *testing.T, challenge string) json.RawMessage {
	t.Helper()
	attested := a.attestedCredentialData(t)
	authData := a.authenticatorData(t, protocol.FlagUserPresent|protocol.FlagUserVerified|protocol.FlagAttestedCredentialData, attested)
	clientData := clientDataJSON(t, "webauthn.create", challenge)

	attestationObject, err := webauthncbor.Marshal(map[string]any{
		"fmt":      "none",
		"attStmt":  map[string]any{},
		"authData": authData,
	})
	require.NoError(t, err)

	id := base64.RawURLEncoding.EncodeToString(a.credentialID)
	body, err := json.Marshal(map[string]any{
		"id":    id,
		"rawId": id,
		"type":  "public-key",
		"response": map[string]any{
			"attestationObject": base64.RawURLEncoding.EncodeToString(attestationObject),
			"clientDataJSON":    base64.RawURLEncoding.EncodeToString(clientData),
		},
	})
	require.NoError(t, err)
	return body
}

// assertionCredential builds the standard PublicKeyCredential JSON for
// navigator.credentials.get(), including the discoverable userHandle.
func (a *testAuthenticator) assertionCredential(t *testing.T, challenge string, userHandle []byte) json.RawMessage {
	t.Helper()
	a.signCount++
	authData := a.authenticatorData(t, a.assertionFlags(), nil)
	clientData := clientDataJSON(t, "webauthn.get", challenge)
	sig := a.sign(t, authData, clientData)

	id := base64.RawURLEncoding.EncodeToString(a.credentialID)
	body, err := json.Marshal(map[string]any{
		"id":    id,
		"rawId": id,
		"type":  "public-key",
		"response": map[string]any{
			"authenticatorData": base64.RawURLEncoding.EncodeToString(authData),
			"clientDataJSON":    base64.RawURLEncoding.EncodeToString(clientData),
			"signature":         base64.RawURLEncoding.EncodeToString(sig),
			"userHandle":        base64.RawURLEncoding.EncodeToString(userHandle),
		},
	})
	require.NoError(t, err)
	return body
}

// --- unit tests -------------------------------------------------------------

func TestWebAuthnIDPrefersLegacyID(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	withLegacy := newTestUser(t, app, "legacy@test.local", migratedUID)
	// GoTrue registered the handle as []byte(uuid.String()); the handle must be
	// those exact bytes or go-webauthn rejects the assertion as a mismatch.
	require.Equal(t, []byte(migratedUID), webAuthnID(withLegacy))

	plain := newTestUser(t, app, "plain@test.local", "")
	require.Equal(t, []byte(plain.Id), webAuthnID(plain))
}

func TestStoreAndConsumeChallenge(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "challenge@test.local", "")
	session := webauthnSession(user.Id, "abc")

	rec, err := storeChallenge(app, challengeTypeRegistration, user.Id, session)
	require.NoError(t, err)
	require.NotEmpty(t, rec.Id)

	got, err := consumeChallenge(app, rec.Id, challengeTypeRegistration, user.Id)
	require.NoError(t, err)
	require.Equal(t, "abc", got.Challenge)
	require.Equal(t, []byte(user.Id), got.UserID)

	// Single use: the record is gone.
	_, err = consumeChallenge(app, rec.Id, challengeTypeRegistration, user.Id)
	require.ErrorIs(t, err, errChallengeNotFound)
}

func TestConsumeChallengeExpired(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	col, err := app.FindCollectionByNameOrId(challengesCollection)
	require.NoError(t, err)
	rec := core.NewRecord(col)
	rec.Set("challenge_type", challengeTypeRegistration)
	rec.Set("session_data", webauthnSession("", "expired"))
	rec.Set("expires_at", time.Now().Add(-time.Minute))
	require.NoError(t, app.Save(rec))

	_, err = consumeChallenge(app, rec.Id, challengeTypeRegistration, "")
	require.ErrorIs(t, err, errChallengeExpired)
}

func TestConsumeChallengeWrongType(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	rec, err := storeChallenge(app, challengeTypeRegistration, "", webauthnSession("", "c"))
	require.NoError(t, err)

	_, err = consumeChallenge(app, rec.Id, challengeTypeAuthentication, "")
	require.ErrorIs(t, err, errChallengeType)
}

func TestCleanupExpiredChallenges(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	col, err := app.FindCollectionByNameOrId(challengesCollection)
	require.NoError(t, err)

	save := func(chType string, expires time.Time) *core.Record {
		rec := core.NewRecord(col)
		rec.Set("challenge_type", chType)
		rec.Set("session_data", webauthnSession("", "cleanup"))
		rec.Set("expires_at", expires)
		require.NoError(t, app.Save(rec))
		return rec
	}

	expiredReg := save(challengeTypeRegistration, time.Now().Add(-time.Minute))
	expiredAuth := save(challengeTypeAuthentication, time.Now().Add(-2*time.Minute))
	live := save(challengeTypeAuthentication, time.Now().Add(time.Minute))

	n, err := cleanupExpiredChallenges(app)
	require.NoError(t, err)
	require.Equal(t, 2, n, "both expired registration and authentication challenges must be removed")

	for _, id := range []string{expiredReg.Id, expiredAuth.Id} {
		_, findErr := app.FindRecordById(challengesCollection, id)
		require.Error(t, findErr, "expired challenge %s must be deleted", id)
	}
	_, findErr := app.FindRecordById(challengesCollection, live.Id)
	require.NoError(t, findErr, "unexpired challenge must survive")
}

func TestRegisterChallengeCleanupSchedulesJob(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	before := app.Cron().Total()
	RegisterChallengeCleanup(app)
	require.Equal(t, before+1, app.Cron().Total())

	var ids []string
	for _, j := range app.Cron().Jobs() {
		ids = append(ids, j.Id())
	}
	require.Contains(t, ids, challengeCleanupJobID)
}

func TestLoginStartRateLimited(t *testing.T) {
	setWebAuthnEnv(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	old := loginStartLimiter
	loginStartLimiter = ratelimit.New(2, time.Minute)
	defer func() { loginStartLimiter = old }()

	for i := 0; i < 2; i++ {
		e, w := requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/start", "", nil)
		require.NoError(t, LoginStartHandler(e))
		require.Equal(t, http.StatusOK, w.Code)
	}

	e, _ := requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/start", "", nil)
	err = LoginStartHandler(e)
	apiErr, ok := err.(*router.ApiError)
	require.True(t, ok, "expected *router.ApiError, got %T", err)
	require.Equal(t, http.StatusTooManyRequests, apiErr.Status)
}

func TestResolveOwnerByCredentialID(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "owner@test.local", "")
	credID := []byte{9, 9, 9, 9}
	storeTestPasskey(t, app, user.Id, credID)

	got, err := resolveOwnerRecord(app, credID)
	require.NoError(t, err)
	require.Equal(t, user.Id, got.Id)
}

// TestResolveOwnerIgnoresUserHandle pins that a credential without a passkeys
// row resolves to nobody, even when its userHandle names a migrated user's
// legacy_id: go-webauthn would reject such a login anyway (the credential is
// not in that user's list), so the old legacy_id fallback was dead code.
func TestResolveOwnerIgnoresUserHandle(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	newTestUser(t, app, "migrated@test.local", migratedUID)

	_, err = resolveOwnerRecord(app, []byte{0, 1, 2})
	require.Error(t, err)
}

func TestListHandler(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "list@test.local", "")
	other := newTestUser(t, app, "other@test.local", "")
	rec := storeTestPasskey(t, app, user.Id, []byte{1, 1, 1})
	storeTestPasskey(t, app, other.Id, []byte{2, 2, 2})

	e, w := requestEvent(t, app, user, http.MethodGet, "/api/vectra/passkeys", "", nil)
	require.NoError(t, ListHandler(e))
	require.Equal(t, http.StatusOK, w.Code)

	var out []map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &out))
	require.Len(t, out, 1)
	require.Equal(t, rec.Id, out[0]["id"])
	require.Equal(t, "Test", out[0]["friendly_name"])
	require.NotEmpty(t, out[0]["created_at"])
}

func TestDeleteHandler(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "del@test.local", "")
	other := newTestUser(t, app, "other-del@test.local", "")
	mine := storeTestPasskey(t, app, user.Id, []byte{3, 3, 3})
	theirs := storeTestPasskey(t, app, other.Id, []byte{4, 4, 4})

	// Cannot delete someone else's passkey.
	e, _ := requestEvent(t, app, user, http.MethodDelete, "/api/vectra/passkeys/"+theirs.Id, "", map[string]string{"id": theirs.Id})
	err = DeleteHandler(e)
	require.Error(t, err)
	_, findErr := app.FindRecordById(passkeysCollection, theirs.Id)
	require.NoError(t, findErr)

	// Owner can delete.
	e, w := requestEvent(t, app, user, http.MethodDelete, "/api/vectra/passkeys/"+mine.Id, "", map[string]string{"id": mine.Id})
	require.NoError(t, DeleteHandler(e))
	require.Equal(t, http.StatusNoContent, w.Code)
	_, findErr = app.FindRecordById(passkeysCollection, mine.Id)
	require.Error(t, findErr)
}

// --- full ceremony ----------------------------------------------------------

func TestRegistrationAndLoginCeremony(t *testing.T) {
	for _, tc := range []struct {
		name     string
		legacyID string
	}{
		{"new user", ""},
		{"migrated supabase user", migratedUID},
	} {
		t.Run(tc.name, func(t *testing.T) {
			setWebAuthnEnv(t)
			app, err := tests.NewTestApp()
			require.NoError(t, err)
			defer app.Cleanup()

			user := newTestUser(t, app, "ceremony@test.local", tc.legacyID)
			disableMFA(t, app)
			auth := newTestAuthenticator(t)
			handle := webAuthnID(user)

			// register/start
			e, w := requestEvent(t, app, user, http.MethodPost, "/api/vectra/passkeys/register/start", startBody, nil)
			require.NoError(t, RegistrationStartHandler(e))
			require.Equal(t, http.StatusOK, w.Code)
			start := decodeStart(t, w.Body.Bytes())

			// register/verify
			regBody := mustJSON(t, map[string]any{
				"challenge_id":  start.ChallengeID,
				"credential":    auth.registrationCredential(t, start.Challenge),
				"friendly_name": "Test Key",
			})
			e, w = requestEvent(t, app, user, http.MethodPost, "/api/vectra/passkeys/register/verify", string(regBody), nil)
			require.NoError(t, RegistrationVerifyHandler(e), w.Body.String())
			require.Equal(t, http.StatusOK, w.Code)

			// login/start (public: no auth)
			e, w = requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/start", "", nil)
			require.NoError(t, LoginStartHandler(e))
			require.Equal(t, http.StatusOK, w.Code)
			login := decodeStart(t, w.Body.Bytes())

			// login/verify
			loginBody := mustJSON(t, map[string]any{
				"challenge_id": login.ChallengeID,
				"credential":   auth.assertionCredential(t, login.Challenge, handle),
			})
			e, w = requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/verify", string(loginBody), nil)
			require.NoError(t, LoginVerifyHandler(e), w.Body.String())
			require.Equal(t, http.StatusOK, w.Code)

			var authResp struct {
				Token  string `json:"token"`
				Record struct {
					Id string `json:"id"`
				} `json:"record"`
			}
			require.NoError(t, json.Unmarshal(w.Body.Bytes(), &authResp))
			require.NotEmpty(t, authResp.Token)
			require.Equal(t, user.Id, authResp.Record.Id)

			// sign_count and last_used_at updated
			recs, err := app.FindRecordsByFilter(passkeysCollection, "user = {:u}", "", 0, 0, dbx.Params{"u": user.Id})
			require.NoError(t, err)
			require.Len(t, recs, 1)
			require.Equal(t, 1, recs[0].GetInt("sign_count"))
			require.False(t, recs[0].GetDateTime("last_used_at").IsZero())
			require.False(t, recs[0].GetDateTime("created_at").IsZero(), "StoreCredential must set created_at")
		})
	}
}

// registerAndStartLogin runs register/start + register/verify for user and
// begins a discoverable login, returning the login challenge.
func registerAndStartLogin(t *testing.T, app *tests.TestApp, user *core.Record, auth *testAuthenticator) startResponse {
	t.Helper()
	e, w := requestEvent(t, app, user, http.MethodPost, "/api/vectra/passkeys/register/start", startBody, nil)
	require.NoError(t, RegistrationStartHandler(e))
	start := decodeStart(t, w.Body.Bytes())

	regBody := mustJSON(t, map[string]any{
		"challenge_id": start.ChallengeID,
		"credential":   auth.registrationCredential(t, start.Challenge),
	})
	e, w = requestEvent(t, app, user, http.MethodPost, "/api/vectra/passkeys/register/verify", string(regBody), nil)
	require.NoError(t, RegistrationVerifyHandler(e), w.Body.String())

	e, w = requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/start", "", nil)
	require.NoError(t, LoginStartHandler(e))
	return decodeStart(t, w.Body.Bytes())
}

func loginVerify(t *testing.T, app *tests.TestApp, challengeID string, credential json.RawMessage) error {
	t.Helper()
	body := mustJSON(t, map[string]any{"challenge_id": challengeID, "credential": credential})
	e, _ := requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/verify", string(body), nil)
	return LoginVerifyHandler(e)
}

// TestRegistrationChallengeBoundToCaller: a registration challenge issued to
// one user cannot be completed by another user's session.
func TestRegistrationChallengeBoundToCaller(t *testing.T) {
	setWebAuthnEnv(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	alice := newTestUser(t, app, "alice-reg@test.local", "")
	mallory := newTestUser(t, app, "mallory-reg@test.local", "")
	auth := newTestAuthenticator(t)

	e, w := requestEvent(t, app, alice, http.MethodPost, "/api/vectra/passkeys/register/start", startBody, nil)
	require.NoError(t, RegistrationStartHandler(e))
	start := decodeStart(t, w.Body.Bytes())

	regBody := mustJSON(t, map[string]any{
		"challenge_id": start.ChallengeID,
		"credential":   auth.registrationCredential(t, start.Challenge),
	})
	e, _ = requestEvent(t, app, mallory, http.MethodPost, "/api/vectra/passkeys/register/verify", string(regBody), nil)
	require.Error(t, RegistrationVerifyHandler(e))

	recs, err := app.FindRecordsByFilter(passkeysCollection, "", "", 0, 0)
	require.NoError(t, err)
	require.Empty(t, recs, "no credential may be stored for either user")
}

// TestLoginRequiresUserVerification: an assertion without the UV flag is
// rejected now that both ceremonies demand user verification.
func TestLoginRequiresUserVerification(t *testing.T) {
	setWebAuthnEnv(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "uv@test.local", "")
	disableMFA(t, app)
	auth := newTestAuthenticator(t)
	login := registerAndStartLogin(t, app, user, auth)

	auth.skipUV = true
	require.Error(t, loginVerify(t, app, login.ChallengeID, auth.assertionCredential(t, login.Challenge, webAuthnID(user))))
}

// TestLoginRejectsCloneWarning: a signature counter that does not advance past
// the stored (non-zero) value fails the login instead of minting a session.
func TestLoginRejectsCloneWarning(t *testing.T) {
	setWebAuthnEnv(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "clone@test.local", "")
	disableMFA(t, app)
	auth := newTestAuthenticator(t)
	login := registerAndStartLogin(t, app, user, auth)

	// First login advances the stored counter to 1.
	require.NoError(t, loginVerify(t, app, login.ChallengeID, auth.assertionCredential(t, login.Challenge, webAuthnID(user))))

	// A clone replays counter 1 (assertionCredential increments, so rewind).
	e, w := requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/start", "", nil)
	require.NoError(t, LoginStartHandler(e))
	login = decodeStart(t, w.Body.Bytes())
	auth.signCount = 0
	err = loginVerify(t, app, login.ChallengeID, auth.assertionCredential(t, login.Challenge, webAuthnID(user)))
	apiErr, ok := err.(*router.ApiError)
	require.True(t, ok, "expected *router.ApiError, got %T", err)
	require.Equal(t, http.StatusBadRequest, apiErr.Status)
}

func TestRegisterStartRequiresAuth(t *testing.T) {
	setWebAuthnEnv(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	e, _ := requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/register/start", "", nil)
	require.Error(t, RegistrationStartHandler(e))
}

// --- helpers ----------------------------------------------------------------

type startResponse struct {
	ChallengeID string `json:"challenge_id"`
	Challenge   string `json:"challenge"`
}

func decodeStart(t *testing.T, body []byte) startResponse {
	t.Helper()
	var raw struct {
		ChallengeID string `json:"challenge_id"`
		Options     struct {
			Challenge string `json:"challenge"`
		} `json:"options"`
	}
	require.NoError(t, json.Unmarshal(body, &raw))
	require.NotEmpty(t, raw.ChallengeID)
	require.NotEmpty(t, raw.Options.Challenge)
	return startResponse{ChallengeID: raw.ChallengeID, Challenge: raw.Options.Challenge}
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	require.NoError(t, err)
	return b
}

// disableMFA clears the MFA requirement inherited from PocketBase's shared test
// data snapshot; production's fresh users collection defaults to MFA disabled.
func disableMFA(t *testing.T, app *tests.TestApp) {
	t.Helper()
	users, err := app.FindCollectionByNameOrId("users")
	require.NoError(t, err)
	if !users.MFA.Enabled {
		return
	}
	users.MFA.Enabled = false
	require.NoError(t, app.Save(users))
}

func webauthnSession(userID, challenge string) *webauthn.SessionData {
	s := &webauthn.SessionData{Challenge: challenge}
	if userID != "" {
		s.UserID = []byte(userID)
	}
	return s
}

func storeTestPasskey(t *testing.T, app *tests.TestApp, userID string, credID []byte) *core.Record {
	t.Helper()
	col, err := app.FindCollectionByNameOrId(passkeysCollection)
	require.NoError(t, err)
	rec := core.NewRecord(col)
	rec.Set("user", userID)
	rec.Set("credential_id", base64.RawURLEncoding.EncodeToString(credID))
	rec.Set("public_key", base64.RawURLEncoding.EncodeToString([]byte{1, 2, 3}))
	rec.Set("friendly_name", "Test")
	rec.Set("created_at", time.Now().UTC())
	require.NoError(t, app.Save(rec))
	return rec
}
