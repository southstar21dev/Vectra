package ratelimit

import (
	"strconv"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestLimiterResets(t *testing.T) {
	l := New(1, time.Minute)
	now := time.Now()
	require.True(t, l.Allow("ip", now))
	require.False(t, l.Allow("ip", now.Add(30*time.Second)), "second call in the same window is blocked")
	require.True(t, l.Allow("ip", now.Add(time.Minute)), "window resets after the duration")
	require.True(t, l.Allow("other-ip", now), "limits are per key")
}

func TestAllowNIsAllOrNothing(t *testing.T) {
	l := New(10, time.Minute)
	now := time.Now()
	require.True(t, l.AllowN("k", 7, now))
	require.False(t, l.AllowN("k", 4, now), "7+4 exceeds the cap")
	require.True(t, l.AllowN("k", 3, now), "the rejected call consumed nothing")
	require.False(t, l.Allow("k", now))
	require.False(t, l.AllowN("fresh", 11, now), "n above max can never pass")
	require.True(t, l.AllowN("fresh", 0, now))
}

// TestLimiterStaysBounded floods a small-cap limiter with distinct live keys:
// the map never grows past the cap, and expired entries are reclaimed by the
// once-per-window sweep.
func TestLimiterStaysBounded(t *testing.T) {
	l := New(5, time.Minute)
	l.cap = 100
	now := time.Now()
	for i := 0; i < 1000; i++ {
		require.True(t, l.Allow("k"+strconv.Itoa(i), now), "a new key is always admitted")
		require.LessOrEqual(t, len(l.clients), l.cap)
	}

	later := now.Add(2 * time.Minute)
	require.True(t, l.Allow("late", later))
	require.Len(t, l.clients, 1, "the sweep drops every expired entry")
}

func TestClientKeyGroupsIPv6By64(t *testing.T) {
	require.Equal(t, "203.0.113.7", ClientKey("203.0.113.7"))
	require.Equal(t, "203.0.113.7", ClientKey("::ffff:203.0.113.7"), "IPv4-mapped stays per address")
	a := ClientKey("2001:db8:1:2:aaaa::1")
	require.Equal(t, "2001:db8:1:2::/64", a)
	require.Equal(t, a, ClientKey("2001:db8:1:2:ffff:ffff:ffff:ffff"), "same /64 shares a bucket")
	require.NotEqual(t, a, ClientKey("2001:db8:1:3::1"), "a different /64 gets its own bucket")
	require.Equal(t, "not-an-ip", ClientKey("not-an-ip"))
}
