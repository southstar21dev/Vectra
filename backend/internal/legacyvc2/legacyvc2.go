// Package legacyvc2 re-encrypts stored legacy `vc2:` ciphertext as `vc3:`.
//
// vc2 values are AES-256-GCM under the shared server key with no additional
// data, so anyone who obtains one can have the crypto route open it from any
// account. vc3 is the same scheme with the owning user's id as GCM additional
// data. The Supabase importer (internal/migrate) stored vc2 blobs verbatim, and
// the route emitted vc2 before vc3 existed, so production rows can still hold
// them. Upgrade rewrites each one as vc3 bound to the record's owner, which lets
// the route stop accepting vc2.
//
// Which values are covered: every text, JSON and editor field of every
// non-system base or auth collection whose stored value starts with `vc2:`
// (for JSON fields: holds a JSON string that starts with `vc2:`, which is how
// trip_plans.legs and transit_trackers.stops are stored). The owner is the
// record itself for the `users` collection and the `user` relation everywhere
// else. Today that is users.neptun_ics_url and the encrypted fields of
// schedule_events, neptun_schedule_events, schedule_items,
// course_tracker_data, transit_trackers and trip_plans.
//
// Upgrade never destroys data. A value that does not decrypt, or whose record
// has no owner, is left exactly as it is and logged by collection, record id
// and field (never by value). A field that mentions `vc2:` somewhere other than
// at the start (for example nested inside a JSON object) is also left alone and
// logged. Values are rewritten with a direct column update, so no record hooks
// run and no timestamps change.
package legacyvc2

import (
	"encoding/json"
	"fmt"
	"log"
	"sort"
	"strings"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"

	"vectra/pb/internal/crypto"
)

// Value names one stored field value. It never carries the value itself.
type Value struct {
	Collection string
	RecordID   string
	Field      string
	Reason     string
}

func (v Value) String() string {
	s := v.Collection + "/" + v.RecordID + "." + v.Field
	if v.Reason != "" {
		s += " (" + v.Reason + ")"
	}
	return s
}

// Report is the outcome of an Upgrade run.
type Report struct {
	// Converted is the number of vc2 values rewritten as vc3.
	Converted int
	// Left are top-level vc2 values that were not converted (did not decrypt,
	// or no owner). They are unchanged in the database.
	Left []Value
	// Nested are fields that contain `vc2:` other than as a whole value. They
	// are unchanged and need a manual look.
	Nested []Value
}

// candidate is a top-level vc2 value found in the database.
type candidate struct {
	table   string
	id      string
	field   string
	owner   string
	cipher  string
	isJSON  bool
	current string // the raw column value, used as an optimistic-lock guard
}

// Upgrade finds every stored top-level `vc2:` value and rewrites it as `vc3:`
// bound to its owner, in one transaction. keyFn is only called when there is
// at least one vc2 value; if it fails, Upgrade returns an error and changes
// nothing. Running Upgrade again after a successful run changes nothing.
func Upgrade(app core.App, keyFn func() ([]byte, error)) (Report, error) {
	var report Report
	err := app.RunInTransaction(func(txApp core.App) error {
		report = Report{}
		cands, nested, err := scan(txApp)
		if err != nil {
			return err
		}
		report.Nested = nested
		if len(cands) == 0 {
			return nil
		}

		key, err := keyFn()
		if err != nil {
			return fmt.Errorf("legacyvc2: %d stored vc2 value(s) must be re-encrypted to vc3, but the key is unavailable: %w", len(cands), err)
		}
		if len(key) == 0 {
			return fmt.Errorf("legacyvc2: %d stored vc2 value(s) must be re-encrypted to vc3, but the key is empty", len(cands))
		}

		for _, c := range cands {
			reason, err := convert(txApp, key, c)
			if err != nil {
				return err
			}
			if reason != "" {
				report.Left = append(report.Left, Value{Collection: c.table, RecordID: c.id, Field: c.field, Reason: reason})
				continue
			}
			report.Converted++
		}
		return nil
	})
	if err != nil {
		return Report{}, err
	}
	logReport(report)
	return report, nil
}

// convert re-encrypts one value. A non-empty reason means the value was left
// as it is; an error aborts the whole run.
func convert(app core.App, key []byte, c candidate) (string, error) {
	if c.owner == "" {
		return "record has no owner", nil
	}
	plain, err := crypto.DecryptValue(key, c.cipher)
	if err != nil {
		return "does not decrypt", nil
	}
	next, err := crypto.EncryptValueFor(key, plain, c.owner)
	if err != nil {
		return "", fmt.Errorf("legacyvc2: encrypt %s/%s.%s: %w", c.table, c.id, c.field, err)
	}
	// Belt and braces: the new value must open for its owner before it
	// replaces the old one.
	if back, err := crypto.DecryptValueFor(key, next, c.owner); err != nil || back != plain {
		return "", fmt.Errorf("legacyvc2: vc3 round trip failed for %s/%s.%s", c.table, c.id, c.field)
	}

	stored := next
	if c.isJSON {
		raw, err := json.Marshal(next)
		if err != nil {
			return "", fmt.Errorf("legacyvc2: encode %s/%s.%s: %w", c.table, c.id, c.field, err)
		}
		stored = string(raw)
	}

	res, err := app.DB().Update(c.table,
		dbx.Params{c.field: stored},
		dbx.HashExp{"id": c.id, c.field: c.current},
	).Execute()
	if err != nil {
		return "", fmt.Errorf("legacyvc2: update %s/%s.%s: %w", c.table, c.id, c.field, err)
	}
	if n, err := res.RowsAffected(); err == nil && n != 1 {
		return "", fmt.Errorf("legacyvc2: update %s/%s.%s touched %d rows, want 1", c.table, c.id, c.field, n)
	}
	return "", nil
}

// scan lists every top-level vc2 value, plus fields that mention vc2 elsewhere.
func scan(app core.App) ([]candidate, []Value, error) {
	collections, err := app.FindAllCollections(core.CollectionTypeBase, core.CollectionTypeAuth)
	if err != nil {
		return nil, nil, err
	}
	sort.Slice(collections, func(i, j int) bool { return collections[i].Name < collections[j].Name })

	var cands []candidate
	var nested []Value
	for _, col := range collections {
		if col.System && col.Name != "users" {
			continue // PocketBase's own collections never hold app ciphertext
		}
		ownerField := ""
		if col.Name != "users" {
			if f, ok := col.Fields.GetByName("user").(*core.RelationField); ok && f != nil {
				ownerField = f.Name
			}
		}
		for _, field := range col.Fields {
			isJSON := false
			switch field.(type) {
			case *core.TextField, *core.EditorField:
			case *core.JSONField:
				isJSON = true
			default:
				continue
			}
			name := field.GetName()

			var records []*core.Record
			if err := app.RecordQuery(col).AndWhere(dbx.Like(name, crypto.V2Prefix)).All(&records); err != nil {
				return nil, nil, fmt.Errorf("legacyvc2: scan %s.%s: %w", col.Name, name, err)
			}
			for _, rec := range records {
				raw, cipher, ok := topLevel(rec.Get(name))
				if !strings.Contains(raw, crypto.V2Prefix) {
					continue // SQLite LIKE is case-insensitive; "VC2:" is not ours
				}
				if !ok {
					nested = append(nested, Value{Collection: col.Name, RecordID: rec.Id, Field: name, Reason: "mentions vc2: but is not a vc2 value"})
					continue
				}
				owner := rec.Id
				if col.Name != "users" {
					owner = ""
					if ownerField != "" {
						owner = rec.GetString(ownerField)
					}
				}
				cands = append(cands, candidate{
					table: col.Name, id: rec.Id, field: name, owner: owner,
					cipher: cipher, isJSON: isJSON, current: raw,
				})
			}
		}
	}
	return cands, nested, nil
}

// topLevel returns the raw stored string and, when the whole value is a vc2
// ciphertext (directly, or as a JSON string), that ciphertext.
func topLevel(v any) (raw string, cipher string, ok bool) {
	switch t := v.(type) {
	case string:
		return t, t, strings.HasPrefix(t, crypto.V2Prefix)
	case []byte:
		s := string(t)
		return s, s, strings.HasPrefix(s, crypto.V2Prefix)
	case types.JSONRaw:
		raw = string(t)
		var s string
		if err := json.Unmarshal(t, &s); err != nil {
			return raw, "", false
		}
		return raw, s, strings.HasPrefix(s, crypto.V2Prefix)
	default:
		return "", "", false
	}
}

// logReport writes to the process log (stderr, so it shows in the container
// log at boot). It deliberately avoids app.Logger(): that writes to the
// auxiliary database, which the migration runner holds in a transaction.
func logReport(r Report) {
	if r.Converted == 0 && len(r.Left) == 0 && len(r.Nested) == 0 {
		return
	}
	log.Printf("legacyvc2: re-encrypted %d vc2 value(s) to vc3; left %d unchanged; %d field(s) mention vc2 elsewhere",
		r.Converted, len(r.Left), len(r.Nested))
	for _, v := range r.Left {
		log.Printf("legacyvc2: left unchanged: %s", v)
	}
	for _, v := range r.Nested {
		log.Printf("legacyvc2: not converted: %s", v)
	}
}
