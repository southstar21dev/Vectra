package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Per-IP limits for the password-reset endpoints. PocketBase tags them `requestPasswordReset` and
// `confirmPasswordReset`, which the `*:auth` rule from 1700000009 doesn't match, so they only fell
// under the broad `/api/` rule (300 per 10s). PocketBase already holds each account to one reset
// email per 2 minutes, but one IP could still make the server mail hundreds of different addresses
// a minute and get the sender flagged as spam.
//
// The confirm limit is generous: tokens are signed and short-lived, so it only stops hammering.
// A rule an operator already set for either label is left alone.
func init() {
	rules := []core.RateLimitRule{
		{Label: "*:requestPasswordReset", MaxRequests: 3, Duration: 60},
		{Label: "*:confirmPasswordReset", MaxRequests: 10, Duration: 60},
	}

	m.Register(func(app core.App) error {
		settings := app.Settings()
		existing := map[string]bool{}
		for _, r := range settings.RateLimits.Rules {
			existing[r.Label] = true
		}
		changed := false
		for _, r := range rules {
			if !existing[r.Label] {
				settings.RateLimits.Rules = append(settings.RateLimits.Rules, r)
				changed = true
			}
		}
		if !changed {
			return nil
		}
		return app.Save(settings)
	}, func(app core.App) error {
		settings := app.Settings()
		kept := settings.RateLimits.Rules[:0]
		for _, r := range settings.RateLimits.Rules {
			drop := false
			for _, ours := range rules {
				if r == ours {
					drop = true
					break
				}
			}
			if !drop {
				kept = append(kept, r)
			}
		}
		settings.RateLimits.Rules = kept
		return app.Save(settings)
	})
}
