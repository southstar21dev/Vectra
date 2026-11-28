package crypto

import (
	"testing"

	"github.com/stretchr/testify/require"
)

// TestDk2Vector pins the whole stored-key chain: stored dedupe_key values are
// bindDigest over digestValue under keys derived from the raw server key, so
// any change to those functions silently re-keys every tracker. See digestValue.
func TestDk2Vector(t *testing.T) {
	dkey := deriveDigestKey(deriveKey("test-key"))
	got := bindDigest(dkey, "user1", digestValue("test-key", []string{"a", "b"}))
	require.Equal(t, "dk2:d2399cfba51d6a4aa131ae8a65de0f58d5314dd08b3a50999703491a7f7fae93", got)
}
