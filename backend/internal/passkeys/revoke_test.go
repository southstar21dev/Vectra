package passkeys

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/pocketbase/pocketbase/tools/router"
	"github.com/stretchr/testify/require"
)

func passkeyCount(t *testing.T, app core.App, userID string) int {
	t.Helper()
	recs, err := app.FindRecordsByFilter(passkeysCollection, "user = {:u}", "", 0, 0, dbx.Params{"u": userID})
	require.NoError(t, err)
	return len(recs)
}

// TestRegisterStartRequiresCurrentPassword: a session token alone cannot begin
// enrolling a passkey; a missing or wrong password is a 400 and stores no
// challenge.
func TestRegisterStartRequiresCurrentPassword(t *testing.T) {
	setWebAuthnEnv(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	user := newTestUser(t, app, "reauth-reg@test.local", "")
	for _, body := range []string{"", `{}`, `{"currentPassword":"wrong"}`} {
		e, _ := requestEvent(t, app, user, http.MethodPost, "/api/vectra/passkeys/register/start", body, nil)
		apiErr, ok := RegistrationStartHandler(e).(*router.ApiError)
		require.True(t, ok, "body %q", body)
		require.Equal(t, http.StatusBadRequest, apiErr.Status, "body %q", body)
	}
	recs, err := app.FindRecordsByFilter(challengesCollection, "", "", 0, 0)
	require.NoError(t, err)
	require.Empty(t, recs)
}

// TestPasswordResetRevokesPreHijackPasskey models the account pre-hijack: an
// attacker signs up with the victim's address and enrols a passkey; the victim
// then resets the password through PocketBase's real confirm-password-reset
// route. The attacker's passkey must no longer log in.
func TestPasswordResetRevokesPreHijackPasskey(t *testing.T) {
	setWebAuthnEnv(t)
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()
	RegisterRevokeOnCredentialChange(app)
	disableMFA(t, app)

	attackerAccount := newTestUser(t, app, "victim@test.local", "")
	attackerKey := newTestAuthenticator(t)
	login := registerAndStartLogin(t, app, attackerAccount, attackerKey)
	// Precondition: the planted passkey logs in before the reset.
	require.NoError(t, loginVerify(t, app, login.ChallengeID,
		attackerKey.assertionCredential(t, login.Challenge, webAuthnID(attackerAccount))))

	// The victim resets the password from the emailed link.
	fresh, err := app.FindRecordById("users", attackerAccount.Id)
	require.NoError(t, err)
	token, err := fresh.NewPasswordResetToken()
	require.NoError(t, err)
	r, err := apis.NewRouter(app)
	require.NoError(t, err)
	mux, err := r.BuildMux()
	require.NoError(t, err)
	req := httptest.NewRequest(http.MethodPost, "/api/collections/users/confirm-password-reset",
		strings.NewReader(`{"token":"`+token+`","password":"victim-new-pass","passwordConfirm":"victim-new-pass"}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	require.Equal(t, http.StatusNoContent, w.Code, w.Body.String())

	require.Zero(t, passkeyCount(t, app, attackerAccount.Id), "the reset must revoke every passkey")
	e, w2 := requestEvent(t, app, nil, http.MethodPost, "/api/vectra/passkeys/login/start", "", nil)
	require.NoError(t, LoginStartHandler(e))
	login = decodeStart(t, w2.Body.Bytes())
	require.Error(t, loginVerify(t, app, login.ChallengeID,
		attackerKey.assertionCredential(t, login.Challenge, webAuthnID(attackerAccount))),
		"a passkey enrolled before the reset must not log in after it")
}

// TestCredentialChangeRevokesPasskeys: a password change or an email change
// revokes the user's passkeys and pending challenges; other profile edits and
// other users' passkeys are untouched.
func TestCredentialChangeRevokesPasskeys(t *testing.T) {
	for _, tc := range []struct {
		name   string
		change func(r *core.Record)
		revoke bool
	}{
		{"password change", func(r *core.Record) { r.SetPassword("another-pass-1") }, true},
		{"email change", func(r *core.Record) { r.SetEmail("moved@test.local") }, true},
		{"profile edit", func(r *core.Record) { r.Set("full_name", "New Name") }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			app, err := tests.NewTestApp()
			require.NoError(t, err)
			defer app.Cleanup()
			RegisterRevokeOnCredentialChange(app)

			user := newTestUser(t, app, "rev@test.local", "")
			other := newTestUser(t, app, "other@test.local", "")
			storeTestPasskey(t, app, user.Id, []byte("cred-user"))
			storeTestPasskey(t, app, other.Id, []byte("cred-other"))
			_, err = storeChallenge(app, challengeTypeRegistration, user.Id, webauthnSession(user.Id, "c"))
			require.NoError(t, err)

			fresh, err := app.FindRecordById("users", user.Id)
			require.NoError(t, err)
			tc.change(fresh)
			require.NoError(t, app.Save(fresh))

			want := 1
			if tc.revoke {
				want = 0
			}
			require.Equal(t, want, passkeyCount(t, app, user.Id))
			challenges, err := app.FindRecordsByFilter(challengesCollection, "user = {:u}", "", 0, 0, dbx.Params{"u": user.Id})
			require.NoError(t, err)
			require.Len(t, challenges, want)
			require.Equal(t, 1, passkeyCount(t, app, other.Id), "another user's passkeys are never touched")
		})
	}
}
