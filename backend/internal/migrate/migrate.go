// Package migrate implements the one-shot `pb migrate-supabase` importer: it
// reads a Supabase Postgres database (auth.users, public.profiles,
// auth.webauthn_credentials and the per-user data tables) and writes the
// equivalent PocketBase records, preserving every field verbatim — including
// the bcrypt password hashes and the `vc2:` ciphertext blobs.
//
// The Postgres row fetching is hidden behind the small [source] interface so
// the mapping/remap/idempotency logic can be unit-tested against an in-memory
// stub with no live Postgres (see migrate_test.go).
package migrate

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib" // registers the "pgx" database/sql driver
	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
)

// dataTables are the per-user collections imported after users/passkeys, in
// dependency order. The names double as the source schema-qualified table names
// (all under the `public` schema).
var dataTables = []string{
	"schedule_events",
	"neptun_schedule_events",
	"schedule_items",
	"transit_trackers",
	"course_tracker_data",
	"trip_plans",
	"push_subscriptions",
}

// Options controls a migration run.
type Options struct {
	// DryRun connects to Postgres and reports what would be imported without
	// writing anything to PocketBase.
	DryRun bool

	// Refresh overwrites already-imported users, passkeys and the naturally
	// keyed data rows (schedule_items, course_tracker_data) from the source.
	// Off by default: after cutover those rows are live, and a re-run must not
	// roll back a changed email/password, a newer passkey sign counter, or
	// data edited in the app. Without it a re-run is insert-only.
	Refresh bool
}

// TableReport is the per-table outcome of a run. The skip reasons are kept
// separate so an operator (and Task 4's count parity) can tell expected
// idempotent re-runs apart from rows that were deliberately not imported:
//
//   - SkippedExisting:  already present (a re-run) — expected.
//   - SkippedNoEmail:   an auth.users row with no email; PocketBase's auth
//     email is required, so the user (and, transitively, its data) is not
//     imported. This is data not represented, not idempotency.
//   - SkippedUnknownUser: a row whose user_id has no imported user (e.g. the
//     email-less users above) — the parent is missing, not idempotency.
type TableReport struct {
	Table              string
	Imported           int
	SkippedExisting    int
	SkippedNoEmail     int
	SkippedUnknownUser int
}

// Report is the full outcome of a run.
type Report struct {
	Tables []TableReport
}

// String renders a human-readable summary for the CLI.
func (r Report) String() string {
	var b strings.Builder
	b.WriteString("migrate-supabase report\n")
	for _, t := range r.Tables {
		fmt.Fprintf(
			&b,
			"  %-24s imported=%-6d skipped_existing=%-6d skipped_no_email=%-6d skipped_unknown_user=%-6d\n",
			t.Table, t.Imported, t.SkippedExisting, t.SkippedNoEmail, t.SkippedUnknownUser,
		)
	}
	return b.String()
}

// ---------------------------------------------------------------------------
// Row structs returned by the source. They are the Postgres-side shape; the
// mapping to PocketBase happens in the import* functions below.
// ---------------------------------------------------------------------------

// UserRow mirrors a row of auth.users (joined conceptually with profiles).
type UserRow struct {
	ID                string // auth.users.id (UUID)
	Email             string
	EncryptedPassword string // bcrypt $2a$... hash, verbatim
	EmailConfirmed    bool   // email_confirmed_at IS NOT NULL
	RawUserMetaData   map[string]any
	CreatedAt         time.Time
	UpdatedAt         time.Time
}

// ProfileRow mirrors a row of public.profiles.
type ProfileRow struct {
	ID             string
	FullName       string
	AvatarURL      string
	Appearance     any // jsonb, verbatim
	NeptunICSURL   string
	NeptunLastSync *time.Time
	Preferences    any // jsonb, verbatim
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

// PasskeyRow mirrors a row of auth.webauthn_credentials.
type PasskeyRow struct {
	UserID          string
	CredentialID    []byte
	PublicKey       []byte
	AttestationType string
	AAGUID          []byte
	SignCount       int64
	Transports      []string
	BackupEligible  bool
	BackedUp        bool
	FriendlyName    string
	CreatedAt       time.Time
	UpdatedAt       time.Time
	LastUsedAt      *time.Time
}

// DataRow is a generic per-user data-table row: the original primary key, the
// owning Supabase user UUID, and every remaining column keyed by name.
type DataRow struct {
	ID     string
	UserID string
	Fields map[string]any
}

// source is the seam that keeps the importer unit-testable without Postgres.
type source interface {
	users(ctx context.Context) ([]UserRow, error)
	profiles(ctx context.Context) (map[string]ProfileRow, error)
	passkeys(ctx context.Context) ([]PasskeyRow, error)
	table(ctx context.Context, name string) ([]DataRow, error)
	close() error
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

// Run opens the Supabase Postgres database described by dsn and imports it into
// app. When opts.DryRun is set it reports the expected counts without writing.
func Run(app core.App, dsn string, opts Options) (Report, error) {
	if strings.TrimSpace(dsn) == "" {
		return Report{}, errors.New("migrate-supabase: SUPABASE_DSN (or --dsn) is required")
	}

	src, err := openPostgres(dsn)
	if err != nil {
		return Report{}, err
	}
	defer src.close()

	return importAll(app, src, opts)
}

// importAll is the testable core: given an app and a source it performs the
// whole import and returns the report.
func importAll(app core.App, src source, opts Options) (Report, error) {
	ctx := context.Background()
	rep := Report{}

	profiles, err := src.profiles(ctx)
	if err != nil {
		return rep, fmt.Errorf("read profiles: %w", err)
	}
	users, err := src.users(ctx)
	if err != nil {
		return rep, fmt.Errorf("read users: %w", err)
	}

	userMap := map[string]string{} // Supabase UUID -> PocketBase record id

	ur, err := importUsers(app, users, profiles, userMap, opts)
	rep.Tables = append(rep.Tables, ur)
	if err != nil {
		return rep, fmt.Errorf("import users: %w", err)
	}
	app.Logger().Info("migrate-supabase: users done",
		"imported", ur.Imported,
		"skipped_existing", ur.SkippedExisting,
		"skipped_no_email", ur.SkippedNoEmail,
		"skipped_unknown_user", ur.SkippedUnknownUser,
	)

	pks, err := src.passkeys(ctx)
	if err != nil {
		return rep, fmt.Errorf("read passkeys: %w", err)
	}
	pr, err := importPasskeys(app, pks, userMap, opts)
	rep.Tables = append(rep.Tables, pr)
	if err != nil {
		return rep, fmt.Errorf("import passkeys: %w", err)
	}
	app.Logger().Info("migrate-supabase: passkeys done",
		"imported", pr.Imported,
		"skipped_existing", pr.SkippedExisting,
		"skipped_no_email", pr.SkippedNoEmail,
		"skipped_unknown_user", pr.SkippedUnknownUser,
	)

	for _, name := range dataTables {
		rows, err := src.table(ctx, name)
		if err != nil {
			return rep, fmt.Errorf("read %s: %w", name, err)
		}
		tr, err := importTable(app, name, rows, userMap, opts)
		rep.Tables = append(rep.Tables, tr)
		if err != nil {
			return rep, fmt.Errorf("import %s: %w", name, err)
		}
		app.Logger().Info("migrate-supabase: table done",
			"table", name,
			"imported", tr.Imported,
			"skipped_existing", tr.SkippedExisting,
			"skipped_no_email", tr.SkippedNoEmail,
			"skipped_unknown_user", tr.SkippedUnknownUser,
		)
	}

	return rep, nil
}

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

func importUsers(app core.App, users []UserRow, profiles map[string]ProfileRow, userMap map[string]string, opts Options) (TableReport, error) {
	tr := TableReport{Table: "users"}
	col, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		return tr, err
	}

	err = app.RunInTransaction(func(tx core.App) error {
		for _, u := range users {
			if u.ID == "" || u.Email == "" {
				// PocketBase auth records require an email; phone-only/OAuth
				// Supabase accounts without one cannot be represented. Counted
				// separately so the drop is visible (and Task 4 can reconcile
				// counts); their data rows later show up as unknown-user skips.
				app.Logger().Warn("migrate-supabase: skipping user without email", "legacy_id", u.ID)
				tr.SkippedNoEmail++
				continue
			}

			existing, err := findRecord(tx, "users", "legacy_id = {:id}", dbx.Params{"id": u.ID})
			if err != nil {
				return fmt.Errorf("lookup user %s: %w", u.ID, err)
			}
			if existing != nil {
				userMap[u.ID] = existing.Id
				tr.SkippedExisting++
				if opts.DryRun || !opts.Refresh {
					continue
				}
				applyUser(existing, u, profiles[u.ID])
				if err := tx.Save(existing); err != nil {
					return fmt.Errorf("update user %s: %w", u.ID, err)
				}
				continue
			}

			if opts.DryRun {
				// No PocketBase id exists yet; use the UUID as a counting-only
				// placeholder. Nothing is written.
				userMap[u.ID] = u.ID
				tr.Imported++
				continue
			}

			rec := core.NewRecord(col)
			applyUser(rec, u, profiles[u.ID])
			if err := tx.Save(rec); err != nil {
				return fmt.Errorf("create user %s: %w", u.ID, err)
			}
			userMap[u.ID] = rec.Id
			tr.Imported++
		}
		return nil
	})
	return tr, err
}

// applyUser writes the Supabase user + profile onto a PocketBase users record.
func applyUser(rec *core.Record, u UserRow, p ProfileRow) {
	rec.SetEmail(u.Email)
	rec.SetVerified(u.EmailConfirmed)
	rec.Set("legacy_id", u.ID)
	if !u.CreatedAt.IsZero() {
		rec.Set("created_at", u.CreatedAt)
	}
	if !u.UpdatedAt.IsZero() {
		rec.Set("updated_at", u.UpdatedAt)
	}

	// raw_user_meta_data carries the original full_name/avatar_url; profiles is
	// authoritative when present.
	fullName := stringFromMap(u.RawUserMetaData, "full_name")
	avatarURL := stringFromMap(u.RawUserMetaData, "avatar_url")
	if p.FullName != "" {
		fullName = p.FullName
	}
	if p.AvatarURL != "" {
		avatarURL = p.AvatarURL
	}
	if fullName != "" {
		rec.Set("full_name", fullName)
	}
	if avatarURL != "" {
		rec.Set("avatar_url", avatarURL)
	}
	if p.Appearance != nil {
		rec.Set("appearance", p.Appearance)
	}
	if p.NeptunICSURL != "" {
		rec.Set("neptun_ics_url", p.NeptunICSURL)
	}
	if p.NeptunLastSync != nil {
		rec.Set("neptun_last_sync", *p.NeptunLastSync)
	}
	if p.Preferences != nil {
		rec.Set("preferences", p.Preferences)
	}

	// Transplant the bcrypt hash verbatim. record.Set("password", hash) would
	// re-hash the already-hashed value, so set the raw field value directly: a
	// *PasswordFieldValue{Hash: ...} is accepted by both the field validator and
	// DriverValue, and stores the hash unchanged.
	if u.EncryptedPassword != "" {
		rec.SetRaw("password", &core.PasswordFieldValue{Hash: u.EncryptedPassword})
	}
}

// ---------------------------------------------------------------------------
// Passkeys
// ---------------------------------------------------------------------------

func importPasskeys(app core.App, rows []PasskeyRow, userMap map[string]string, opts Options) (TableReport, error) {
	tr := TableReport{Table: "passkeys"}
	col, err := app.FindCollectionByNameOrId("passkeys")
	if err != nil {
		return tr, err
	}

	err = app.RunInTransaction(func(tx core.App) error {
		for _, p := range rows {
			userID, ok := userMap[p.UserID]
			if !ok {
				app.Logger().Warn("migrate-supabase: skipping passkey for unknown user", "user_id", p.UserID)
				tr.SkippedUnknownUser++
				continue
			}
			credID := base64.RawURLEncoding.EncodeToString(p.CredentialID)

			existing, err := findRecord(tx, "passkeys", "credential_id = {:c}", dbx.Params{"c": credID})
			if err != nil {
				return fmt.Errorf("lookup passkey %s: %w", p.UserID, err)
			}
			if existing != nil {
				tr.SkippedExisting++
				if opts.DryRun || !opts.Refresh {
					continue
				}
				applyPasskey(existing, p, userID)
				if err := tx.Save(existing); err != nil {
					return fmt.Errorf("update passkey %s: %w", p.UserID, err)
				}
				continue
			}

			if opts.DryRun {
				tr.Imported++
				continue
			}

			rec := core.NewRecord(col)
			applyPasskey(rec, p, userID)
			if err := tx.Save(rec); err != nil {
				return fmt.Errorf("create passkey %s: %w", p.UserID, err)
			}
			tr.Imported++
		}
		return nil
	})
	return tr, err
}

// applyPasskey writes a Supabase webauthn credential onto a PocketBase passkeys
// record. Binary fields are base64url (RawURLEncoding) to match
// internal/passkeys.
func applyPasskey(rec *core.Record, p PasskeyRow, userID string) {
	rec.Set("user", userID)
	rec.Set("credential_id", base64.RawURLEncoding.EncodeToString(p.CredentialID))
	rec.Set("public_key", base64.RawURLEncoding.EncodeToString(p.PublicKey))
	rec.Set("attestation_type", p.AttestationType)
	if len(p.AAGUID) > 0 {
		rec.Set("aaguid", base64.RawURLEncoding.EncodeToString(p.AAGUID))
	}
	rec.Set("sign_count", p.SignCount)
	rec.Set("transports", p.Transports)
	rec.Set("backup_eligible", p.BackupEligible)
	rec.Set("backed_up", p.BackedUp)
	if p.FriendlyName != "" {
		rec.Set("friendly_name", p.FriendlyName)
	}
	if !p.CreatedAt.IsZero() {
		rec.Set("created_at", p.CreatedAt)
	}
	if p.LastUsedAt != nil {
		rec.Set("last_used_at", *p.LastUsedAt)
	}
}

// ---------------------------------------------------------------------------
// Data tables
// ---------------------------------------------------------------------------

func importTable(app core.App, name string, rows []DataRow, userMap map[string]string, opts Options) (TableReport, error) {
	tr := TableReport{Table: name}
	col, err := app.FindCollectionByNameOrId(name)
	if err != nil {
		return tr, err
	}

	err = app.RunInTransaction(func(tx core.App) error {
		for _, row := range rows {
			// PWA-only: native FCM subscriptions have no device to deliver to.
			if name == "push_subscriptions" {
				if t, ok := row.Fields["transport"].(string); ok && t == "fcm" {
					continue
				}
			}
			userID, ok := userMap[row.UserID]
			if !ok {
				app.Logger().Warn("migrate-supabase: skipping row for unknown user",
					"table", name, "id", row.ID, "user_id", row.UserID)
				tr.SkippedUnknownUser++
				continue
			}

			existing, err := findDataRow(tx, name, row, userID)
			if err != nil {
				return fmt.Errorf("lookup %s row %s: %w", name, row.ID, err)
			}
			if existing != nil {
				tr.SkippedExisting++
				if opts.DryRun || !opts.Refresh {
					continue
				}
				// Only the naturally-keyed tables are updated in place; the
				// keyless ones are skipped so a re-run never duplicates them.
				if isUpdatable(name) {
					applyDataRow(existing, name, row, userID)
					if err := tx.Save(existing); err != nil {
						return fmt.Errorf("update %s row %s: %w", name, row.ID, err)
					}
				}
				continue
			}

			if opts.DryRun {
				tr.Imported++
				continue
			}

			rec := core.NewRecord(col)
			applyDataRow(rec, name, row, userID)
			if err := tx.Save(rec); err != nil {
				return fmt.Errorf("create %s row %s: %w", name, row.ID, err)
			}
			tr.Imported++
		}
		return nil
	})
	return tr, err
}

// findDataRow locates an already-imported row for idempotency. schedule_items
// is keyed on (user, client_id), course_tracker_data on user (its singleton key) and
// transit_trackers on the natural (user, dedupe_key) unique key; every other
// table uses a deterministic PocketBase id derived from the source UUID.
func findDataRow(app core.App, name string, row DataRow, userID string) (*core.Record, error) {
	switch name {
	case "schedule_items":
		return findRecord(app, name, "user = {:u} && client_id = {:v}", dbx.Params{"u": userID, "v": row.ID})
	case "course_tracker_data":
		return findRecord(app, name, "user = {:v}", dbx.Params{"v": userID})
	case "transit_trackers":
		if k, ok := row.Fields["dedupe_key"].(string); ok && k != "" {
			return findRecord(app, name, "user = {:u} && dedupe_key = {:k}", dbx.Params{"u": userID, "k": k})
		}
	}
	return findRecordByDerivedID(app, name, row)
}

// applyDataRow writes a source row onto a PocketBase record, preserving every
// mapped field verbatim and remapping user_id -> the `user` relation.
func applyDataRow(rec *core.Record, name string, row DataRow, userID string) {
	rec.Set("user", userID)

	switch name {
	case "schedule_items":
		// The original UUID is the client's own id; the PocketBase record id is
		// minted by PocketBase (a 15-char id can't hold a UUID).
		rec.Set("client_id", row.ID)
	default:
		// Keyless tables get a deterministic id so a re-run finds (and skips)
		// the same row instead of inserting a duplicate.
		if row.ID != "" && rec.Id == "" {
			rec.Set("id", derivedID(name, row.ID))
		}
	}

	for field, value := range row.Fields {
		if field == "id" || field == "user_id" || value == nil {
			continue
		}
		if rec.Collection().Fields.GetByName(field) == nil {
			// The source has a column the target schema does not describe.
			continue
		}
		rec.Set(field, value)
	}
}

// isUpdatable reports whether an already-imported row should be refreshed from
// source rather than skipped.
func isUpdatable(name string) bool {
	return name == "schedule_items" || name == "course_tracker_data"
}

// derivedID returns a deterministic, valid PocketBase record id (15 lowercase
// alphanumerics) for a source row, so keyless tables are idempotent.
func derivedID(table, sourceID string) string {
	sum := sha256.Sum256([]byte(table + "\x00" + sourceID))
	return hex.EncodeToString(sum[:])[:15]
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

func findRecord(app core.App, collection, filter string, params dbx.Params) (*core.Record, error) {
	rec, err := app.FindFirstRecordByFilter(collection, filter, params)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return rec, nil
}

func findRecordByDerivedID(app core.App, table string, row DataRow) (*core.Record, error) {
	if row.ID == "" {
		return nil, nil
	}
	rec, err := app.FindRecordById(table, derivedID(table, row.ID))
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return rec, nil
}

func stringFromMap(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	if s, ok := m[key].(string); ok {
		return s
	}
	return ""
}

// ---------------------------------------------------------------------------
// Postgres source
// ---------------------------------------------------------------------------

type postgresSource struct {
	db *sql.DB
}

func openPostgres(dsn string) (*postgresSource, error) {
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil, fmt.Errorf("open postgres: %w", err)
	}
	db.SetMaxOpenConns(4)
	if err := db.PingContext(context.Background()); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("connect postgres: %w", err)
	}
	return &postgresSource{db: db}, nil
}

func (s *postgresSource) close() error { return s.db.Close() }

func (s *postgresSource) users(ctx context.Context) ([]UserRow, error) {
	const q = `SELECT id::text, email, encrypted_password,
		email_confirmed_at IS NOT NULL, raw_user_meta_data, created_at, updated_at
		FROM auth.users`
	rows, err := s.db.QueryContext(ctx, q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []UserRow
	for rows.Next() {
		var (
			u         UserRow
			email     sql.NullString
			password  sql.NullString
			confirmed bool
			meta      []byte
		)
		if err := rows.Scan(&u.ID, &email, &password, &confirmed, &meta, &u.CreatedAt, &u.UpdatedAt); err != nil {
			return nil, err
		}
		u.Email = email.String
		u.EncryptedPassword = password.String
		u.EmailConfirmed = confirmed
		if len(meta) > 0 {
			_ = json.Unmarshal(meta, &u.RawUserMetaData)
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (s *postgresSource) profiles(ctx context.Context) (map[string]ProfileRow, error) {
	const q = `SELECT id::text, full_name, avatar_url, appearance, neptun_ics_url,
		neptun_last_sync, preferences, created_at, updated_at
		FROM public.profiles`
	rows, err := s.db.QueryContext(ctx, q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := map[string]ProfileRow{}
	for rows.Next() {
		var (
			p           ProfileRow
			fullName    sql.NullString
			avatarURL   sql.NullString
			appearance  []byte
			icsURL      sql.NullString
			lastSync    sql.NullTime
			preferences []byte
		)
		if err := rows.Scan(&p.ID, &fullName, &avatarURL, &appearance, &icsURL, &lastSync, &preferences, &p.CreatedAt, &p.UpdatedAt); err != nil {
			return nil, err
		}
		p.FullName = fullName.String
		p.AvatarURL = avatarURL.String
		p.Appearance = jsonOrNil(appearance)
		p.NeptunICSURL = icsURL.String
		if lastSync.Valid {
			t := lastSync.Time
			p.NeptunLastSync = &t
		}
		p.Preferences = jsonOrNil(preferences)
		out[p.ID] = p
	}
	return out, rows.Err()
}

func (s *postgresSource) passkeys(ctx context.Context) ([]PasskeyRow, error) {
	// transports is a Postgres text[]; under pgx's database/sql driver it would
	// otherwise arrive as the array literal "{internal}" which json.Unmarshal
	// rejects. Ask Postgres for JSON directly and surface any decode error
	// instead of silently dropping the value.
	const q = `SELECT user_id::text, credential_id, public_key, attestation_type,
		aaguid::text, sign_count, to_json(transports)::text, backup_eligible, backed_up,
		friendly_name, created_at, updated_at, last_used_at
		FROM auth.webauthn_credentials`
	rows, err := s.db.QueryContext(ctx, q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []PasskeyRow
	for rows.Next() {
		var (
			p               PasskeyRow
			attestationType sql.NullString
			aaguid          sql.NullString
			transports      sql.NullString
			backupEligible  sql.NullBool
			backedUp        sql.NullBool
			friendlyName    sql.NullString
			createdAt       sql.NullTime
			updatedAt       sql.NullTime
			lastUsedAt      sql.NullTime
		)
		if err := rows.Scan(
			&p.UserID, &p.CredentialID, &p.PublicKey, &attestationType, &aaguid,
			&p.SignCount, &transports, &backupEligible, &backedUp, &friendlyName,
			&createdAt, &updatedAt, &lastUsedAt,
		); err != nil {
			return nil, err
		}
		p.AttestationType = attestationType.String
		p.AAGUID = parseUUIDBytes(aaguid.String)
		if transports.Valid {
			decoded, err := decodeTransports(transports.String)
			if err != nil {
				return nil, fmt.Errorf("decode transports for credential %x: %w", p.CredentialID, err)
			}
			p.Transports = decoded
		}
		p.BackedUp = backedUp.Bool
		p.BackupEligible = resolveBackupEligible(backupEligible, backedUp)
		p.FriendlyName = friendlyName.String
		if createdAt.Valid {
			p.CreatedAt = createdAt.Time
		}
		if updatedAt.Valid {
			p.UpdatedAt = updatedAt.Time
		}
		if lastUsedAt.Valid {
			t := lastUsedAt.Time
			p.LastUsedAt = &t
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (s *postgresSource) table(ctx context.Context, name string) ([]DataRow, error) {
	if !isDataTable(name) {
		return nil, fmt.Errorf("unknown table %q", name)
	}
	rows, err := s.db.QueryContext(ctx, "SELECT * FROM public."+name)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	cols, err := rows.Columns()
	if err != nil {
		return nil, err
	}

	var out []DataRow
	for rows.Next() {
		raw := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range raw {
			ptrs[i] = &raw[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return nil, err
		}

		row := DataRow{Fields: make(map[string]any, len(cols))}
		for i, c := range cols {
			v := normalizeValue(raw[i])
			switch c {
			case "id":
				row.ID = castString(v)
			case "user_id":
				row.UserID = castString(v)
			default:
				row.Fields[c] = v
			}
		}
		out = append(out, row)
	}
	return out, rows.Err()
}

func isDataTable(name string) bool {
	for _, t := range dataTables {
		if t == name {
			return true
		}
	}
	return false
}

// normalizeValue adapts pgx database/sql driver values to what PocketBase
// record.Set accepts: []byte (jsonb/text/bytea) becomes a string, which the
// JSON and text fields re-parse as needed.
func normalizeValue(v any) any {
	switch t := v.(type) {
	case nil:
		return nil
	case []byte:
		return string(t)
	default:
		return v
	}
}

func castString(v any) string {
	switch t := v.(type) {
	case nil:
		return ""
	case string:
		return t
	default:
		return fmt.Sprintf("%v", v)
	}
}

func jsonOrNil(b []byte) any {
	if len(b) == 0 {
		return nil
	}
	return b
}

// decodeTransports parses the JSON array produced by `to_json(transports)::text`
// in the passkeys query. An empty/NULL column yields no transports; anything
// that is not valid JSON is returned as an error rather than being silently
// dropped (a raw Postgres text[] literal like "{internal}" fails here, which is
// exactly the regression this guards against).
func decodeTransports(s string) ([]string, error) {
	if s == "" || s == "null" {
		return nil, nil
	}
	var out []string
	if err := json.Unmarshal([]byte(s), &out); err != nil {
		return nil, err
	}
	return out, nil
}

// resolveBackupEligible maps the source backup_eligible column onto the stored
// flag. go-webauthn (v0.18) fails a login whose authenticator reports a BE
// flag different from the stored one, so a wrong value locks the passkey out.
// A backed-up credential is by definition backup eligible (BS=1 requires
// BE=1), so a NULL or false BE next to backed_up=true is stored as true; a
// NULL with no backup evidence stays false, matching how the column was
// imported before.
func resolveBackupEligible(be, backedUp sql.NullBool) bool {
	if backedUp.Valid && backedUp.Bool {
		return true
	}
	return be.Bool
}

// parseUUIDBytes turns a canonical UUID string into its 16 raw bytes.
func parseUUIDBytes(s string) []byte {
	if s == "" {
		return nil
	}
	b, err := hex.DecodeString(strings.ReplaceAll(s, "-", ""))
	if err != nil {
		return nil
	}
	return b
}
