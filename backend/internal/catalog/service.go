package catalog

import (
	"context"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"ganigeorgiev.com/pocketbase/pocketbase/core"

	"vectra/pb/internal/futar"
)

// Schedule of the refresh loop.
const (
	// RefreshEvery is how old the live copy may get before it is rebuilt.
	RefreshEvery = 24 * time.Hour
	// A failed build is retried after retryStart, doubling up to retryMax; the
	// last good copy keeps being served meanwhile.
	retryStart = 5 * time.Minute
	retryMax   = 2 * time.Hour
	// buildTimeout bounds one whole build (about 700 paced calls).
	buildTimeout = 45 * time.Minute
)

// Service owns the builder loop.
type Service struct {
	Store *Store
	Gen   *Generator
	// Now and the durations are fields so tests can run the loop quickly.
	Now        func() time.Time
	Every      time.Duration
	RetryStart time.Duration
	RetryMax   time.Duration
}

// NewService wires a Service around store and gen with the production schedule.
func NewService(store *Store, gen *Generator) *Service {
	return &Service{Store: store, Gen: gen, Now: time.Now, Every: RefreshEvery, RetryStart: retryStart, RetryMax: retryMax}
}

// Refresh builds a catalog once and, when it passes the gates, makes it the
// live copy.
func (s *Service) Refresh(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, buildTimeout)
	defer cancel()
	var prev *Catalog
	if snap := s.Store.Current(); snap != nil {
		raw := map[string][]byte{}
		for name, f := range snap.Files {
			raw[name] = f.Raw
		}
		if c, err := Decode(raw); err == nil {
			prev = c
		}
	}
	c, err := s.Gen.Build(ctx, prev)
	if err != nil {
		return err
	}
	raw, err := c.Encode()
	if err != nil {
		return err
	}
	return s.Store.Save(s.Now(), raw)
}

// Run builds on boot when there is no fresh copy, then refreshes every Every,
// retrying failures with back-off, until ctx ends.
func (s *Service) Run(ctx context.Context) {
	wait := time.Duration(0)
	if snap := s.Store.Current(); snap != nil {
		if age := s.Now().Sub(snap.Built); age >= 0 && age < s.Every {
			wait = s.Every - age
		}
	}
	retry := s.RetryStart
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		if err := s.Refresh(ctx); err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("catalog: refresh failed (keeping the last good copy, retry in %s): %v", retry, err)
			wait = retry
			retry = min(retry*2, s.RetryMax)
			continue
		}
		log.Printf("catalog: refreshed")
		wait, retry = s.Every, s.RetryStart
	}
}

// Register serves /catalog/{file} and starts the builder. dataDir is the
// PocketBase data dir; the catalog lives in <dataDir>/catalog so it survives a
// restart. CATALOG_BUILD=0 turns the builder off (the route still serves what
// is on disk). The returned func stops the builder.
func Register(se *core.ServeEvent, dataDir string) (stop func()) {
	store := NewStore(filepath.Join(dataDir, "catalog"))
	h := Handler(store)
	// Public: transit data, no auth. Same file names as the old web host.
	se.Router.GET("/catalog/{file}", h)
	se.Router.HEAD("/catalog/{file}", h)

	ctx, cancel := context.WithCancel(context.Background())
	switch key := strings.TrimSpace(os.Getenv("FUTAR_API_KEY")); {
	case strings.TrimSpace(os.Getenv("CATALOG_BUILD")) == "0":
		log.Printf("catalog: builder disabled (CATALOG_BUILD=0)")
	case key == "":
		log.Printf("catalog: FUTAR_API_KEY is not set, the builder will not run")
	default:
		svc := NewService(store, &Generator{Key: key, Gate: futar.WaitUpstream})
		go svc.Run(ctx)
	}
	return cancel
}
