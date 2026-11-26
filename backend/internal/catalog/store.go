package catalog

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// File is one served catalog file, with its gzip form prepared once.
type File struct {
	Raw      []byte
	Gzip     []byte
	ETag     string
	GzipETag string
}

// Snapshot is one complete, consistent set of the three files.
type Snapshot struct {
	Built time.Time
	Files map[string]*File
}

// Store keeps the last good snapshot in memory and on disk. The disk layout is
// <dir>/set-<unix seconds>/<file> plus <dir>/current naming the live set; the
// pointer is replaced atomically, so a crash mid-save leaves the old set live.
type Store struct {
	dir string
	cur atomic.Pointer[Snapshot]
}

// NewStore opens dir (created on first save) and loads the live set, if any.
// An unreadable or inconsistent set is ignored: the next build replaces it.
func NewStore(dir string) *Store {
	s := &Store{dir: dir}
	if snap, err := s.load(); err == nil {
		s.cur.Store(snap)
	}
	return s
}

// Current returns the live snapshot, or nil when none was ever built.
func (s *Store) Current() *Snapshot { return s.cur.Load() }

func newSnapshot(built time.Time, raw map[string][]byte) (*Snapshot, error) {
	snap := &Snapshot{Built: built, Files: map[string]*File{}}
	for _, name := range Files {
		b, ok := raw[name]
		if !ok || len(b) == 0 {
			return nil, fmt.Errorf("missing %s", name)
		}
		sum := sha256.Sum256(b)
		tag := hex.EncodeToString(sum[:12])
		var buf bytes.Buffer
		zw, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
		if _, err := zw.Write(b); err != nil {
			return nil, err
		}
		if err := zw.Close(); err != nil {
			return nil, err
		}
		snap.Files[name] = &File{Raw: b, Gzip: buf.Bytes(), ETag: `"` + tag + `"`, GzipETag: `"` + tag + `-gzip"`}
	}
	return snap, nil
}

func (s *Store) load() (*Snapshot, error) {
	ptr, err := os.ReadFile(filepath.Join(s.dir, "current"))
	if err != nil {
		return nil, err
	}
	name := strings.TrimSpace(string(ptr))
	secs, err := strconv.ParseInt(strings.TrimPrefix(name, "set-"), 10, 64)
	if err != nil || !strings.HasPrefix(name, "set-") {
		return nil, errors.New("bad catalog pointer")
	}
	raw := map[string][]byte{}
	for _, f := range Files {
		b, err := os.ReadFile(filepath.Join(s.dir, name, f))
		if err != nil {
			return nil, err
		}
		raw[f] = b
	}
	// Refuse a set that no longer parses or is degraded; the builder replaces it.
	c, err := Decode(raw)
	if err != nil {
		return nil, err
	}
	if err := c.check(nil); err != nil {
		return nil, err
	}
	return newSnapshot(time.Unix(secs, 0), raw)
}

// Save persists raw as the new live set and makes it current.
func (s *Store) Save(built time.Time, raw map[string][]byte) error {
	snap, err := newSnapshot(built, raw)
	if err != nil {
		return err
	}
	name := "set-" + strconv.FormatInt(built.Unix(), 10)
	setDir := filepath.Join(s.dir, name)
	if err := os.MkdirAll(setDir, 0o755); err != nil {
		return err
	}
	for _, f := range Files {
		if err := writeFileAtomic(filepath.Join(setDir, f), raw[f]); err != nil {
			return err
		}
	}
	if err := writeFileAtomic(filepath.Join(s.dir, "current"), []byte(name+"\n")); err != nil {
		return err
	}
	s.cur.Store(snap)
	// Drop older sets; only the live one is needed.
	if entries, err := os.ReadDir(s.dir); err == nil {
		for _, e := range entries {
			if e.IsDir() && e.Name() != name && strings.HasPrefix(e.Name(), "set-") {
				_ = os.RemoveAll(filepath.Join(s.dir, e.Name()))
			}
		}
	}
	return nil
}

func writeFileAtomic(path string, b []byte) error {
	tmp := path + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
