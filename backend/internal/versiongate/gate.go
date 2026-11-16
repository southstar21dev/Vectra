// Package versiongate refuses native app builds older than a minimum the owner
// sets in the app_config record, answering 426 Upgrade Required. Builds from
// this feature on send X-App-Version and X-App-Platform; older builds send
// neither, only Ktor's default "ktor-client" User-Agent, and are refused as a
// group when blockLegacy is on. Everything else (browsers, the dashboard, curl,
// the health check) sends no app version and always passes.
package versiongate

import (
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/pocketbase/pocketbase/core"
)

// Collection is the single-record config collection the owner edits.
const Collection = "app_config"

// Message is what builds without the update screen display, so it is written for people.
const Message = "This version of Vectra is no longer supported. Install the latest version to keep using it."

// Request is what the gate reads from an incoming request.
type Request struct {
	Version   string
	Platform  string
	UserAgent string
}

// Config mirrors the app_config record. An empty minimum means no minimum.
type Config struct {
	MinIos      string
	MinAndroid  string
	BlockLegacy bool
}

var versionPattern = regexp.MustCompile(`^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?`)

// parseVersion reads the leading major.minor.patch; a prerelease suffix is ignored.
func parseVersion(text string) ([3]int, bool) {
	match := versionPattern.FindStringSubmatch(strings.TrimSpace(text))
	if match == nil {
		return [3]int{}, false
	}
	var out [3]int
	for i := 0; i < 3; i++ {
		if match[i+1] == "" {
			continue
		}
		n, err := strconv.Atoi(match[i+1])
		if err != nil {
			return [3]int{}, false
		}
		out[i] = n
	}
	return out, true
}

func isOlder(version, minimum [3]int) bool {
	for i := 0; i < 3; i++ {
		if version[i] != minimum[i] {
			return version[i] < minimum[i]
		}
	}
	return false
}

// RequiredVersion decides one request. nil lets it through; a non-nil value
// refuses it and is the minimum to report ("" for a legacy build, which has no
// platform to pick a minimum for).
func RequiredVersion(req Request, cfg Config) *string {
	if strings.TrimSpace(req.Version) == "" {
		if cfg.BlockLegacy && strings.HasPrefix(req.UserAgent, "ktor-client") {
			empty := ""
			return &empty
		}
		return nil
	}
	var minimumText string
	switch strings.ToLower(strings.TrimSpace(req.Platform)) {
	case "ios":
		minimumText = cfg.MinIos
	case "android":
		minimumText = cfg.MinAndroid
	}
	minimum, ok := parseVersion(minimumText)
	if !ok {
		return nil
	}
	version, ok := parseVersion(req.Version)
	if !ok || !isOlder(version, minimum) {
		return nil
	}
	required := strings.TrimPrefix(strings.TrimSpace(minimumText), "v")
	return &required
}

// Register puts the gate in front of every /api/ route.
func Register(se *core.ServeEvent) {
	se.Router.BindFunc(func(e *core.RequestEvent) error {
		if !strings.HasPrefix(e.Request.URL.Path, "/api/") {
			return e.Next()
		}
		required := check(e)
		if required == nil {
			return e.Next()
		}
		return e.JSON(http.StatusUpgradeRequired, map[string]any{
			"status":  http.StatusUpgradeRequired,
			"message": Message,
			"data":    map[string]any{"minVersion": *required},
		})
	})
}

// check fails open: a missing collection or record, or any error, lets the request through,
// because a mistake here sits in front of every API call.
func check(e *core.RequestEvent) (required *string) {
	defer func() {
		if recover() != nil {
			required = nil
		}
	}()
	req := Request{
		Version:   e.Request.Header.Get("X-App-Version"),
		Platform:  e.Request.Header.Get("X-App-Platform"),
		UserAgent: e.Request.Header.Get("User-Agent"),
	}
	// Nothing to decide for a request that can't be refused: skip the lookup.
	if req.Version == "" && !strings.HasPrefix(req.UserAgent, "ktor-client") {
		return nil
	}
	rows, err := e.App.FindRecordsByFilter(Collection, "", "", 1, 0)
	if err != nil || len(rows) == 0 {
		return nil
	}
	return RequiredVersion(req, Config{
		MinIos:      rows[0].GetString("minIos"),
		MinAndroid:  rows[0].GetString("minAndroid"),
		BlockLegacy: rows[0].GetBool("blockLegacy"),
	})
}
