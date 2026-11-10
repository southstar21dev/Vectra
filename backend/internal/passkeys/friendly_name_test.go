package passkeys

import (
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/stretchr/testify/require"
)

func TestTruncateFriendlyName(t *testing.T) {
	require.Equal(t, "iPhone", truncateFriendlyName("iPhone"))

	long := strings.Repeat("é", 5000)
	got := truncateFriendlyName(long)
	require.Equal(t, maxFriendlyNameRunes, utf8.RuneCountInString(got))
	require.True(t, utf8.ValidString(got))
}
