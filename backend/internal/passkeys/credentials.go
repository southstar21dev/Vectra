package passkeys

import (
	"encoding/base64"
	"encoding/json"
	"time"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"
)

// ToWebAuthnCredential reconstructs a webauthn.Credential from a stored
// passkeys record. credential_id, public_key and aaguid are decoded from
// base64url (RawURLEncoding); transports is read as a JSON string array.
func ToWebAuthnCredential(r *core.Record) (*webauthn.Credential, error) {
	id, err := base64.RawURLEncoding.DecodeString(r.GetString("credential_id"))
	if err != nil {
		return nil, err
	}
	publicKey, err := base64.RawURLEncoding.DecodeString(r.GetString("public_key"))
	if err != nil {
		return nil, err
	}

	var aaguid []byte
	if raw := r.GetString("aaguid"); raw != "" {
		if aaguid, err = base64.RawURLEncoding.DecodeString(raw); err != nil {
			return nil, err
		}
	}

	var transports []string
	rawTransports, err := types.ParseJSONRaw(r.Get("transports"))
	if err != nil {
		return nil, err
	}
	if len(rawTransports) > 0 && string(rawTransports) != "null" {
		if err := json.Unmarshal(rawTransports, &transports); err != nil {
			return nil, err
		}
	}

	transport := make([]protocol.AuthenticatorTransport, len(transports))
	for i, t := range transports {
		transport[i] = protocol.AuthenticatorTransport(t)
	}

	// attestation_type semantics drifted between library versions: legacy GoTrue
	// v0.16 rows stored the attestation *format* (e.g. "packed", "none") in this
	// column, whereas webauthn v0.18 stores the attestation *type*. The value is
	// passed through verbatim; AttestationFormat is intentionally left empty on
	// reconstruction. This only matters if MDS/attestation re-validation is ever
	// enabled — it has no impact on login validation.
	return &webauthn.Credential{
		ID:              id,
		PublicKey:       publicKey,
		AttestationType: r.GetString("attestation_type"),
		Transport:       transport,
		Flags: webauthn.CredentialFlags{
			BackupEligible: r.GetBool("backup_eligible"),
			BackupState:    r.GetBool("backed_up"),
		},
		Authenticator: webauthn.Authenticator{
			AAGUID:    aaguid,
			SignCount: uint32(r.GetInt("sign_count")),
		},
	}, nil
}

// StoreCredential persists a registered webauthn.Credential as a passkeys
// record owned by userID. credential_id, public_key and aaguid are stored as
// base64url (RawURLEncoding) of the raw bytes; transports is stored as a JSON
// string array.
func StoreCredential(app core.App, userID string, cred *webauthn.Credential, friendlyName string) (*core.Record, error) {
	collection, err := app.FindCollectionByNameOrId("passkeys")
	if err != nil {
		return nil, err
	}

	transports := make([]string, len(cred.Transport))
	for i, t := range cred.Transport {
		transports[i] = string(t)
	}

	rec := core.NewRecord(collection)
	rec.Set("user", userID)
	rec.Set("credential_id", base64.RawURLEncoding.EncodeToString(cred.ID))
	rec.Set("public_key", base64.RawURLEncoding.EncodeToString(cred.PublicKey))
	rec.Set("attestation_type", cred.AttestationType) // see the semantics note in ToWebAuthnCredential
	rec.Set("aaguid", base64.RawURLEncoding.EncodeToString(cred.Authenticator.AAGUID))
	rec.Set("sign_count", cred.Authenticator.SignCount)
	rec.Set("transports", transports)
	rec.Set("backup_eligible", cred.Flags.BackupEligible)
	rec.Set("backed_up", cred.Flags.BackupState)
	rec.Set("friendly_name", friendlyName)
	// Passkeys are only created server-side, so the request-path timestamp hook
	// does not run for them; set the custom created_at explicitly.
	rec.Set("created_at", time.Now().UTC())

	if err := app.Save(rec); err != nil {
		return nil, err
	}

	return rec, nil
}
