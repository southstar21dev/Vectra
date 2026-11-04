package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Turns on PocketBase's built-in per-IP rate limiter. It ships disabled
// (Settings.RateLimits.Enabled defaults to false), which leaves the records
// API — including auth-with-password — open to unthrottled guessing. Keyed by
// e.RealIP(), so it relies on the TrustedProxy setting from 1700000008.
//
// The rules are PocketBase's own defaults. `*:auth` matches every
// auth-with-password / OAuth2 / OTP call on any auth collection; a configured
// rule list that lacks it gets the default `*:auth` rule appended.
//
// An operator who already enabled the limiter is left alone.
func init() {
	defaultRules := []core.RateLimitRule{
		{Label: "*:auth", MaxRequests: 2, Duration: 3},
		{Label: "*:create", MaxRequests: 20, Duration: 5},
		{Label: "/api/batch", MaxRequests: 3, Duration: 1},
		{Label: "/api/", MaxRequests: 300, Duration: 10},
	}

	m.Register(func(app core.App) error {
		settings := app.Settings()
		if settings.RateLimits.Enabled {
			return nil
		}
		if len(settings.RateLimits.Rules) == 0 {
			settings.RateLimits.Rules = append([]core.RateLimitRule(nil), defaultRules...)
		}
		hasAuth := false
		for _, r := range settings.RateLimits.Rules {
			if r.Label == "*:auth" {
				hasAuth = true
				break
			}
		}
		if !hasAuth {
			settings.RateLimits.Rules = append(settings.RateLimits.Rules, defaultRules[0])
		}
		settings.RateLimits.Enabled = true
		return app.Save(settings)
	}, func(app core.App) error {
		settings := app.Settings()
		if !settings.RateLimits.Enabled {
			return nil
		}
		settings.RateLimits.Enabled = false
		return app.Save(settings)
	})
}
