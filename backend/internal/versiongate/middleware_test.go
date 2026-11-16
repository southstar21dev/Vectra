package versiongate_test

import (
	"net/http"
	"testing"

	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tests"

	"vectra/pb/internal/versiongate"
	_ "vectra/pb/migrations"
)

// TestMiddleware drives the real router: the migration's app_config record is set per scenario.
func TestMiddleware(t *testing.T) {
	configure := func(minIos string, blockLegacy bool) func(testing.TB, *tests.TestApp, *core.ServeEvent) {
		return func(tb testing.TB, app *tests.TestApp, se *core.ServeEvent) {
			rows, err := app.FindRecordsByFilter(versiongate.Collection, "", "", 1, 0)
			if err != nil || len(rows) != 1 {
				tb.Fatalf("app_config record missing: %v", err)
			}
			rows[0].Set("minIos", minIos)
			rows[0].Set("blockLegacy", blockLegacy)
			if err := app.Save(rows[0]); err != nil {
				tb.Fatal(err)
			}
			versiongate.Register(se)
		}
	}
	dropConfig := func(tb testing.TB, app *tests.TestApp, se *core.ServeEvent) {
		col, err := app.FindCollectionByNameOrId(versiongate.Collection)
		if err != nil {
			tb.Fatal(err)
		}
		if err := app.Delete(col); err != nil {
			tb.Fatal(err)
		}
		versiongate.Register(se)
	}
	ios := func(v string) map[string]string {
		return map[string]string{"X-App-Version": v, "X-App-Platform": "ios", "User-Agent": "ktor-client"}
	}

	scenarios := []tests.ApiScenario{
		{
			Name:            "old iOS build refused",
			Method:          http.MethodGet,
			URL:             "/api/health",
			Headers:         ios("1.4.2"),
			ExpectedStatus:  http.StatusUpgradeRequired,
			ExpectedContent: []string{`"status":426`, `"minVersion":"1.6.0"`, `no longer supported`},
			BeforeTestFunc:  configure("1.6.0", true),
		},
		{
			Name:            "current iOS build served",
			Method:          http.MethodGet,
			URL:             "/api/health",
			Headers:         ios("1.6.0"),
			ExpectedStatus:  http.StatusOK,
			ExpectedContent: []string{`"code":200`},
			BeforeTestFunc:  configure("1.6.0", true),
		},
		{
			Name:            "legacy build refused when blocked",
			Method:          http.MethodGet,
			URL:             "/api/health",
			Headers:         map[string]string{"User-Agent": "ktor-client"},
			ExpectedStatus:  http.StatusUpgradeRequired,
			ExpectedContent: []string{`"minVersion":""`},
			BeforeTestFunc:  configure("", true),
		},
		{
			Name:            "legacy build served when not blocked",
			Method:          http.MethodGet,
			URL:             "/api/health",
			Headers:         map[string]string{"User-Agent": "ktor-client"},
			ExpectedStatus:  http.StatusOK,
			ExpectedContent: []string{`"code":200`},
			BeforeTestFunc:  configure("1.6.0", false),
		},
		{
			Name:            "browser served",
			Method:          http.MethodGet,
			URL:             "/api/health",
			Headers:         map[string]string{"User-Agent": "Mozilla/5.0"},
			ExpectedStatus:  http.StatusOK,
			ExpectedContent: []string{`"code":200`},
			BeforeTestFunc:  configure("9.9.9", true),
		},
		{
			Name:            "missing config fails open",
			Method:          http.MethodGet,
			URL:             "/api/health",
			Headers:         ios("0.0.1"),
			ExpectedStatus:  http.StatusOK,
			ExpectedContent: []string{`"code":200`},
			BeforeTestFunc:  dropConfig,
		},
		{
			Name:            "app_config not listable without superuser",
			Method:          http.MethodGet,
			URL:             "/api/collections/app_config/records",
			ExpectedStatus:  http.StatusForbidden,
			ExpectedContent: []string{`"status":403`},
			BeforeTestFunc:  configure("", false),
		},
	}
	for _, s := range scenarios {
		s.Test(t)
	}
}
