package passkeys

import (
	"testing"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	_ "vectra/pb/migrations"
)

func TestCredentialRoundTrip(t *testing.T) {
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	users, _ := app.FindCollectionByNameOrId("users")
	u := core.NewRecord(users)
	u.Set("email", "p@test.local")
	u.SetPassword("password123")
	require.NoError(t, app.Save(u))

	orig := &webauthn.Credential{
		ID:              []byte{1, 2, 3, 250, 251},
		PublicKey:       []byte{9, 8, 7},
		AttestationType: "none",
		Transport:       []protocol.AuthenticatorTransport{"internal"},
		Flags:           webauthn.CredentialFlags{BackupEligible: true, BackupState: true},
		Authenticator:   webauthn.Authenticator{SignCount: 5, AAGUID: []byte{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
	}
	rec, err := StoreCredential(app, u.Id, orig, "Test")
	require.NoError(t, err)

	got, err := ToWebAuthnCredential(rec)
	require.NoError(t, err)
	require.Equal(t, orig.ID, got.ID)
	require.Equal(t, orig.PublicKey, got.PublicKey)
	require.Equal(t, uint32(5), got.Authenticator.SignCount)
	require.True(t, got.Flags.BackupEligible)
	require.Equal(t, orig.Authenticator.AAGUID, got.Authenticator.AAGUID)
	require.Equal(t, orig.AttestationType, got.AttestationType)
	require.True(t, got.Flags.BackupState)
	require.Equal(t, orig.Transport, got.Transport)
}
