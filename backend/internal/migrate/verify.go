// This file implements post-import verification for the `migrate-supabase`
// importer. Verify answers "did the import actually land?" by comparing the
// Supabase source against the freshly written PocketBase database:
//
//   - row-count parity per table (users, passkeys, every data table);
//   - a per-user spot check that the sampled users' row counts match;
//   - that sampled `vc2:` ciphertext still decrypts through the exact code path
//     the /api/vectra/crypto route uses;
//   - `PRAGMA integrity_check` on the PocketBase data.db;
//   - a login assertion (live, if the owner supplies a test account, otherwise a
//     bcrypt-format check with a note that a live login is a manual step).
//
// The Postgres side is read through the same [source] seam as the importer, so
// the whole thing is unit-testable against an in-memory stub.
package migrate

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"
	"golang.org/x/crypto/bcrypt"

	"vectra/pb/internal/crypto"
)

const (
	defaultVerifySampleUsers = 5
	defaultVerifySampleRows  = 25
)

// ciphertextTables are the collections the app encrypts at rest (everything the
// crypto route writes). push_subscriptions is deliberately absent: its
// subscription payload is not encrypted.
var ciphertextTables = []string{
	"schedule_events",
	"neptun_schedule_events",
	"transit_trackers",
	"trip_plans",
	"course_tracker_data",
	"schedule_items",
}

// VerifyOptions tunes a verification run. Zero values select defaults.
type VerifyOptions struct {
	// SampleUsers is how many users the per-user spot check inspects.
	SampleUsers int
	// SampleRows is how many rows per table are scanned for `vc2:` ciphertext.
	SampleRows int
	// TestEmail/TestPassword are an owner-supplied test account. When set, the
	// login check asserts record.ValidatePassword(TestPassword) for that user.
	TestEmail    string
	TestPassword string
}

func (o VerifyOptions) withDefaults() VerifyOptions {
	if o.SampleUsers <= 0 {
		o.SampleUsers = defaultVerifySampleUsers
	}
	if o.SampleRows <= 0 {
		o.SampleRows = defaultVerifySampleRows
	}
	return o
}

// Check is one named verification outcome. Skipped marks a check that could not
// be evaluated for lack of applicable data (e.g. no imported user has a
// password to validate); a skipped check is neutral and never fails the run.
type Check struct {
	Name    string
	Passed  bool
	Skipped bool
	Detail  string
}

// VerifyReport is the full outcome of a verification run.
type VerifyReport struct {
	Checks []Check
}

// OK reports whether every check passed.
func (r VerifyReport) OK() bool {
	for _, c := range r.Checks {
		if !c.Passed {
			return false
		}
	}
	return true
}

// Failed returns the checks that did not pass.
func (r VerifyReport) Failed() []Check {
	var out []Check
	for _, c := range r.Checks {
		if !c.Passed {
			out = append(out, c)
		}
	}
	return out
}

// Err returns a non-nil error naming every failed check, so a CLI caller can
// exit non-zero after printing the report.
func (r VerifyReport) Err() error {
	if r.OK() {
		return nil
	}
	names := make([]string, 0, len(r.Checks))
	for _, c := range r.Failed() {
		names = append(names, c.Name)
	}
	return fmt.Errorf("migration verification failed: %s", strings.Join(names, ", "))
}

// String renders a human-readable summary for the CLI.
func (r VerifyReport) String() string {
	var b strings.Builder
	b.WriteString("migrate-supabase verification report\n")
	for _, c := range r.Checks {
		status := "PASS"
		switch {
		case c.Skipped:
			status = "SKIP"
		case !c.Passed:
			status = "FAIL"
		}
		fmt.Fprintf(&b, "  [%s] %-32s %s\n", status, c.Name, c.Detail)
	}
	if r.OK() {
		b.WriteString("  => all checks passed\n")
	} else {
		b.WriteString("  => verification FAILED\n")
	}
	return b.String()
}

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

// Verify opens the Supabase Postgres database described by dsn and verifies the
// import against app with default sampling options.
func Verify(app core.App, dsn string) (VerifyReport, error) {
	return VerifyWithOptions(app, dsn, VerifyOptions{})
}

// VerifyWithOptions is Verify with explicit options.
func VerifyWithOptions(app core.App, dsn string, opts VerifyOptions) (VerifyReport, error) {
	if strings.TrimSpace(dsn) == "" {
		return VerifyReport{}, errors.New("migrate-supabase: --dsn is required")
	}
	src, err := openPostgres(dsn)
	if err != nil {
		return VerifyReport{}, err
	}
	defer src.close()
	return verifySource(app, src, opts)
}

// verifySource is the testable core: given an app and a source it runs every
// check and returns the report. It returns a non-nil error only when a check
// could not be evaluated at all (a read failure); individual check failures are
// recorded in the report so the caller sees the full picture.
func verifySource(app core.App, src source, opts VerifyOptions) (VerifyReport, error) {
	opts = opts.withDefaults()
	ctx := context.Background()
	rep := VerifyReport{}

	users, err := src.users(ctx)
	if err != nil {
		return rep, fmt.Errorf("verify: read users: %w", err)
	}
	passkeys, err := src.passkeys(ctx)
	if err != nil {
		return rep, fmt.Errorf("verify: read passkeys: %w", err)
	}
	tables := make(map[string][]DataRow, len(dataTables))
	for _, name := range dataTables {
		rows, err := src.table(ctx, name)
		if err != nil {
			return rep, fmt.Errorf("verify: read %s: %w", name, err)
		}
		tables[name] = rows
	}

	// Only auth.users rows with an email are importable (PocketBase auth records
	// require one); every other row is deliberately dropped by the importer, so
	// the expected counts below exclude them.
	importable := make(map[string]bool, len(users))
	importableUsers := make([]UserRow, 0, len(users))
	for _, u := range users {
		if u.ID != "" && u.Email != "" {
			importable[u.ID] = true
			importableUsers = append(importableUsers, u)
		}
	}

	// Migrated PocketBase records are exactly those whose owner carries a
	// non-empty legacy_id. Scoping to them (rather than every row in the
	// collection) keeps parity honest on an instance that already holds
	// unrelated records.
	migratedUsers, err := app.FindRecordsByFilter("users", "legacy_id != ''", "legacy_id", 0, 0)
	if err != nil {
		return rep, fmt.Errorf("verify: list migrated users: %w", err)
	}
	pbUserByLegacy := make(map[string]*core.Record, len(migratedUsers))
	for _, u := range migratedUsers {
		pbUserByLegacy[u.GetString("legacy_id")] = u
	}

	// --- row-count parity ---------------------------------------------------
	rep.Checks = append(rep.Checks, countCheck("users", len(importableUsers), len(migratedUsers)))
	rep.Checks = append(rep.Checks, countCheck(
		"passkeys",
		countPasskeys(passkeys, importable),
		countMigrated(app, "passkeys"),
	))
	for _, name := range dataTables {
		rep.Checks = append(rep.Checks, countCheck(
			name,
			countTableRows(name, tables[name], importable),
			countMigrated(app, name),
		))
	}

	// --- per-user spot check ------------------------------------------------
	rep.Checks = append(rep.Checks,
		perUserChecks(app, importableUsers, tables, passkeys, pbUserByLegacy, opts.SampleUsers)...)

	// --- ciphertext decryptability ------------------------------------------
	rep.Checks = append(rep.Checks, decryptChecks(app, opts.SampleRows)...)

	// --- data.db integrity --------------------------------------------------
	rep.Checks = append(rep.Checks, integrityCheck(app))

	// --- login --------------------------------------------------------------
	rep.Checks = append(rep.Checks, loginCheck(app, migratedUsers, opts))

	return rep, nil
}

// ---------------------------------------------------------------------------
// Count parity
// ---------------------------------------------------------------------------

func countCheck(name string, want, got int) Check {
	return Check{
		Name:   "count:" + name,
		Passed: want == got,
		Detail: fmt.Sprintf("source=%d pocketbase=%d", want, got),
	}
}

func countPasskeys(rows []PasskeyRow, importable map[string]bool) int {
	n := 0
	for _, p := range rows {
		if importable[p.UserID] {
			n++
		}
	}
	return n
}

func countTableRows(table string, rows []DataRow, importable map[string]bool) int {
	n := 0
	for _, r := range rows {
		if !importable[r.UserID] {
			continue
		}
		// PWA-only: FCM rows are deliberately not imported.
		if table == "push_subscriptions" {
			if t, ok := r.Fields["transport"].(string); ok && t == "fcm" {
				continue
			}
		}
		n++
	}
	return n
}

// countMigrated counts the PocketBase records in a collection whose owner is a
// migrated user (non-empty legacy_id). A -1 result means the count could not be
// read; the caller records it as a failed check (source=N pocketbase=-1).
func countMigrated(app core.App, collection string) int {
	recs, err := app.FindRecordsByFilter(collection, "user.legacy_id != ''", "", 0, 0)
	if err != nil {
		return -1
	}
	return len(recs)
}

// ---------------------------------------------------------------------------
// Per-user spot check
// ---------------------------------------------------------------------------

func perUserChecks(
	app core.App,
	users []UserRow,
	tables map[string][]DataRow,
	passkeys []PasskeyRow,
	pbByLegacy map[string]*core.Record,
	n int,
) []Check {
	sorted := append([]UserRow(nil), users...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].ID < sorted[j].ID })
	if len(sorted) > n {
		sorted = sorted[:n]
	}

	if len(sorted) == 0 {
		return []Check{{Name: "per-user", Passed: true, Detail: "no importable users to sample"}}
	}

	checks := make([]Check, 0, len(sorted))
	for _, u := range sorted {
		checks = append(checks, perUserCheck(app, u, tables, passkeys, pbByLegacy))
	}
	return checks
}

func perUserCheck(
	app core.App,
	u UserRow,
	tables map[string][]DataRow,
	passkeys []PasskeyRow,
	pbByLegacy map[string]*core.Record,
) Check {
	name := "per-user:" + u.ID
	rec, ok := pbByLegacy[u.ID]
	if !ok {
		return Check{Name: name, Passed: false, Detail: "user was not imported"}
	}

	want := map[string]int{"passkeys": 0}
	for _, p := range passkeys {
		if p.UserID == u.ID {
			want["passkeys"]++
		}
	}
	for _, table := range dataTables {
		want[table] = 0
		for _, row := range tables[table] {
			if row.UserID != u.ID {
				continue
			}
			// PWA-only: FCM rows are deliberately not imported.
			if table == "push_subscriptions" {
				if t, ok := row.Fields["transport"].(string); ok && t == "fcm" {
					continue
				}
			}
			want[table]++
		}
	}

	var mismatches []string
	for _, table := range append([]string{"passkeys"}, dataTables...) {
		got, err := app.FindRecordsByFilter(table, "user = {:u}", "", 0, 0, dbx.Params{"u": rec.Id})
		if err != nil {
			return Check{Name: name, Passed: false, Detail: fmt.Sprintf("count %s: %v", table, err)}
		}
		if want[table] != len(got) {
			mismatches = append(mismatches, fmt.Sprintf("%s source=%d pb=%d", table, want[table], len(got)))
		}
	}
	if len(mismatches) > 0 {
		return Check{Name: name, Passed: false, Detail: strings.Join(mismatches, "; ")}
	}
	return Check{Name: name, Passed: true, Detail: fmt.Sprintf("all table counts match for %s", u.Email)}
}

// ---------------------------------------------------------------------------
// Ciphertext decryptability
// ---------------------------------------------------------------------------

func decryptChecks(app core.App, sampleRows int) []Check {
	key, err := crypto.EncryptionKeyFromEnv()
	if err != nil {
		return []Check{{Name: "decrypt:key", Passed: false, Detail: err.Error()}}
	}
	checks := make([]Check, 0, len(ciphertextTables))
	for _, table := range ciphertextTables {
		checks = append(checks, decryptCheck(app, table, key, sampleRows))
	}
	return checks
}

func decryptCheck(app core.App, table string, key []byte, sampleRows int) Check {
	name := "decrypt:" + table
	records, err := app.FindRecordsByFilter(table, "", "id", sampleRows, 0)
	if err != nil {
		return Check{Name: name, Passed: false, Detail: "list: " + err.Error()}
	}

	found := 0
	var failures []string
	for _, rec := range records {
		// vc3 values are bound to their owner, so decrypt as that user (vc2
		// ignores it). Every ciphertext table carries the `user` relation.
		owner := rec.GetString("user")
		for _, field := range rec.Collection().Fields {
			v, ok := ciphertextString(rec.Get(field.GetName()))
			if !ok {
				continue
			}
			found++
			if _, err := crypto.DecryptValueFor(key, v, owner); err != nil {
				failures = append(failures, fmt.Sprintf("%s.%s: %v", rec.Id, field.GetName(), err))
			}
		}
	}

	switch {
	case len(failures) > 0:
		return Check{
			Name:   name,
			Passed: false,
			Detail: fmt.Sprintf("%d/%d ciphertext values failed to decrypt: %s", len(failures), found, strings.Join(failures, "; ")),
		}
	case found == 0:
		return Check{Name: name, Passed: true, Detail: "no ciphertext values in sample"}
	default:
		return Check{Name: name, Passed: true, Detail: fmt.Sprintf("%d ciphertext values decrypted", found)}
	}
}

// ciphertextString extracts a `vc2:`/`vc3:` ciphertext from a record field
// value. Text fields arrive as plain strings; JSON fields (trip_plans.legs)
// arrive as a types.JSONRaw holding a JSON-encoded string and are unwrapped
// first.
func ciphertextString(v any) (string, bool) {
	var s string
	switch t := v.(type) {
	case string:
		s = t
	case []byte:
		s = string(t)
	case types.JSONRaw:
		if err := json.Unmarshal(t, &s); err != nil {
			return "", false
		}
	default:
		return "", false
	}
	if strings.HasPrefix(s, crypto.V2Prefix) || strings.HasPrefix(s, crypto.V3Prefix) {
		return s, true
	}
	return "", false
}

// ---------------------------------------------------------------------------
// Integrity and login
// ---------------------------------------------------------------------------

func integrityCheck(app core.App) Check {
	name := "integrity:data.db"
	dbPath := filepath.Join(app.DataDir(), "data.db")
	var res string
	if err := app.DB().NewQuery("PRAGMA integrity_check").Row(&res); err != nil {
		return Check{Name: name, Passed: false, Detail: fmt.Sprintf("%s: %v", dbPath, err)}
	}
	return Check{Name: name, Passed: res == "ok", Detail: fmt.Sprintf("%s => %s", dbPath, res)}
}

func loginCheck(app core.App, migratedUsers []*core.Record, opts VerifyOptions) Check {
	const name = "login"

	if strings.TrimSpace(opts.TestEmail) != "" {
		rec, err := findRecord(app, "users", "email = {:e}", dbx.Params{"e": opts.TestEmail})
		if err != nil {
			return Check{Name: name, Passed: false, Detail: "lookup: " + err.Error()}
		}
		if rec == nil {
			return Check{Name: name, Passed: false, Detail: "no user with email " + opts.TestEmail}
		}
		if !rec.ValidatePassword(opts.TestPassword) {
			return Check{Name: name, Passed: false, Detail: "ValidatePassword failed for " + opts.TestEmail}
		}
		return Check{Name: name, Passed: true, Detail: "ValidatePassword passed for " + opts.TestEmail}
	}

	// No test account supplied: validate a stored hash. Supabase auth.users
	// legitimately contains OAuth/magic-link accounts imported with no
	// encrypted_password (no hash), so a hashless user must not fail the check:
	// select one that actually has a hash and validate it. If none has a hash,
	// the check is skipped rather than failed.
	if len(migratedUsers) == 0 {
		return Check{Name: name, Passed: false, Detail: "no imported users to inspect"}
	}
	var checked *core.Record
	var invalid []string
	for _, rec := range migratedUsers {
		hash := rec.GetString("password:hash")
		if hash == "" {
			continue
		}
		if err := validBcryptHash(hash); err != nil {
			invalid = append(invalid, fmt.Sprintf("%s: %v", rec.Email(), err))
			continue
		}
		if checked == nil {
			checked = rec
		}
	}
	switch {
	case checked != nil:
		return Check{
			Name:   name,
			Passed: true,
			Detail: fmt.Sprintf("%s: valid bcrypt hash; live login is a manual step", checked.Email()),
		}
	case len(invalid) > 0:
		return Check{Name: name, Passed: false, Detail: strings.Join(invalid, "; ")}
	default:
		return Check{
			Name:    name,
			Passed:  true,
			Skipped: true,
			Detail:  "skipped: no imported user has a password hash (OAuth/magic-link accounts have none); live login is a manual step",
		}
	}
}

func validBcryptHash(hash string) error {
	if !strings.HasPrefix(hash, "$2a$") {
		return fmt.Errorf("stored hash %q is not a $2a$ bcrypt hash", hash)
	}
	if _, err := bcrypt.Cost([]byte(hash)); err != nil {
		return fmt.Errorf("stored hash is not a valid bcrypt hash: %w", err)
	}
	return nil
}
