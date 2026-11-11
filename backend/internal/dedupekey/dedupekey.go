// Package dedupekey rewrites stored transit_trackers.dedupe_key values from the
// old global digest to the per-user `dk2:` digest the crypto route now returns.
//
// The client stores whatever the route's `digest` op returns as dedupe_key,
// and the (user, dedupe_key) unique index stops the same tracker being added
// twice. The old digest was SHA256(server key | parts), the same for every
// account; the new one is an HMAC over the owner's id and that old digest
// (crypto.UpgradeDigest). So a stored old key can be turned into exactly the
// value the route now returns for the same parts and owner, without knowing
// the parts, and existing trackers keep deduping against new adds.
//
// Upgrade never deletes anything. A row whose new key is already taken by
// another tracker of the same user (only possible when old-format rows are
// imported after the switch) keeps its old key and is reported. Values are
// rewritten with a direct column update, so no hooks run and no timestamps
// change.
package dedupekey

import (
	"fmt"
	"log"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/crypto"
)

const table = "transit_trackers"

// Report is the outcome of an Upgrade run.
type Report struct {
	// Converted is the number of keys rewritten.
	Converted int
	// Left lists the record ids whose key was left as it was, with the reason.
	Left []string
}

type row struct {
	ID   string `db:"id"`
	User string `db:"user"`
	Key  string `db:"dedupe_key"`
}

// Upgrade rewrites every dedupe_key that lacks the dk2 prefix, in one
// transaction. keyFn is called only when there is at least one such key; if it
// fails, Upgrade changes nothing and returns the error. A second run changes
// nothing.
func Upgrade(app core.App, keyFn func() ([]byte, error)) (Report, error) {
	var report Report
	err := app.RunInTransaction(func(tx core.App) error {
		report = Report{}
		var rows []row
		err := tx.DB().NewQuery(
			"SELECT id, [[user]], dedupe_key FROM " + table +
				" WHERE substr(dedupe_key, 1, {:n}) != {:p} ORDER BY id",
		).Bind(dbx.Params{"n": len(crypto.DigestPrefix), "p": crypto.DigestPrefix}).All(&rows)
		if err != nil {
			return fmt.Errorf("dedupekey: scan: %w", err)
		}
		if len(rows) == 0 {
			return nil
		}
		key, err := keyFn()
		if err != nil {
			return fmt.Errorf("dedupekey: %d transit tracker key(s) must be rewritten, but the key is unavailable: %w", len(rows), err)
		}
		if len(key) == 0 {
			return fmt.Errorf("dedupekey: %d transit tracker key(s) must be rewritten, but the key is empty", len(rows))
		}
		for _, r := range rows {
			reason, err := rewrite(tx, key, r)
			if err != nil {
				return err
			}
			if reason != "" {
				report.Left = append(report.Left, r.ID+" ("+reason+")")
				continue
			}
			report.Converted++
		}
		return nil
	})
	if err != nil {
		return Report{}, err
	}
	if report.Converted > 0 || len(report.Left) > 0 {
		log.Printf("dedupekey: rewrote %d transit tracker key(s); left %d unchanged", report.Converted, len(report.Left))
		for _, l := range report.Left {
			log.Printf("dedupekey: left %s/%s", table, l)
		}
	}
	return report, nil
}

// rewrite updates one row. A non-empty reason means the row was left as it
// is; an error aborts the run.
func rewrite(tx core.App, key []byte, r row) (string, error) {
	if r.User == "" {
		return "record has no owner", nil
	}
	next, _ := crypto.UpgradeDigest(key, r.User, r.Key)

	var taken int
	err := tx.DB().NewQuery(
		"SELECT COUNT(*) FROM " + table + " WHERE [[user]] = {:u} AND dedupe_key = {:k} AND id != {:id}",
	).Bind(dbx.Params{"u": r.User, "k": next, "id": r.ID}).Row(&taken)
	if err != nil {
		return "", fmt.Errorf("dedupekey: check %s: %w", r.ID, err)
	}
	if taken > 0 {
		return "the new key is already used by another tracker of the same user", nil
	}

	res, err := tx.DB().Update(table,
		dbx.Params{"dedupe_key": next},
		dbx.HashExp{"id": r.ID, "dedupe_key": r.Key},
	).Execute()
	if err != nil {
		return "", fmt.Errorf("dedupekey: update %s: %w", r.ID, err)
	}
	if n, err := res.RowsAffected(); err == nil && n != 1 {
		return "", fmt.Errorf("dedupekey: update %s touched %d rows, want 1", r.ID, n)
	}
	return "", nil
}
