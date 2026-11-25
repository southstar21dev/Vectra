// Package reauthtest gives tests a safe way to replace the re-auth limiters.
package reauthtest

import (
	"testing"
	"time"

	"vectra/pb/internal/ratelimit"
	"vectra/pb/internal/reauth"
)

// Use swaps in fresh re-auth limiters (perUser checks per user, perIP per
// client IP, both per minute) and restores the real ones when the test ends.
// Tests that use it must not run in parallel with each other.
func Use(t testing.TB, perUser, perIP int) {
	t.Helper()
	prevUser, prevIP := reauth.UserLimiter, reauth.IPLimiter
	reauth.UserLimiter = ratelimit.New(perUser, time.Minute)
	reauth.IPLimiter = ratelimit.New(perIP, time.Minute)
	t.Cleanup(func() { reauth.UserLimiter, reauth.IPLimiter = prevUser, prevIP })
}
