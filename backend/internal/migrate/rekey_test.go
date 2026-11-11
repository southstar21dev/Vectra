package migrate

import (
	"strings"
	"testing"

	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/tests"
	"github.com/stretchr/testify/require"

	"vectra/pb/internal/crypto"
	"vectra/pb/internal/dedupekey"
)

// TestRerunAfterDedupeRekeyFindsTracker: once an imported tracker's old-format
// dedupe_key has been rewritten to dk2, a re-run must still recognise it (by
// its derived id) and skip it, instead of trying to insert it again and
// failing the whole table on the duplicate id.
func TestRerunAfterDedupeRekeyFindsTracker(t *testing.T) {
	t.Setenv("NEPTUN_ENCRYPTION_KEY", "migrate-rekey-test-key")
	app, err := tests.NewTestApp()
	require.NoError(t, err)
	defer app.Cleanup()

	src := seedSource()
	_, err = importAll(app, src, Options{})
	require.NoError(t, err)

	rep, err := dedupekey.Upgrade(app, crypto.DigestKeyFromEnv)
	require.NoError(t, err)
	require.Equal(t, 1, rep.Converted)
	tt, err := app.FindFirstRecordByFilter("transit_trackers", "id != ''", dbx.Params{})
	require.NoError(t, err)
	require.True(t, strings.HasPrefix(tt.GetString("dedupe_key"), crypto.DigestPrefix))

	again, err := importAll(app, src, Options{})
	require.NoError(t, err)
	require.Equal(t, 1, reportsByTable(again)["transit_trackers"].SkippedExisting)
	all, err := app.FindAllRecords("transit_trackers")
	require.NoError(t, err)
	require.Len(t, all, 1, "the tracker must not be duplicated")
}
