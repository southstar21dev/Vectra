// Package config is the single list of the environment variables the backend
// reads, and the boot-time check of them. The packages that use a variable
// still read it where they need it (tests set them per case); this package
// makes a missing or misspelled one visible when the server starts instead of
// when a route is first hit. backend/README.md documents the same list.
package config

import (
	"fmt"
	"log"
	"sort"
	"strings"
)

// Var describes one environment variable.
type Var struct {
	Name string
	// Required variables make a route fail (500) when unset: the route named
	// in Used cannot work without them.
	Required bool
	Used     string
}

// Vars is every variable the backend reads. Add a new setting here first.
var Vars = []Var{
	{"NEPTUN_ENCRYPTION_KEY", true, "/crypto, /neptun/replace, legacy vc2 upgrade"},
	{"FUTAR_API_KEY", true, "/futar and the /catalog/*.json builder"},
	{"VAPID_PUBLIC_KEY", true, "/push/send"},
	{"VAPID_PRIVATE_KEY", true, "/push/send"},
	{"VAPID_SUBJECT", false, "/push/send (defaults to a placeholder address)"},
	{"WEBAUTHN_RP_ID", true, "passkeys"},
	{"WEBAUTHN_RP_ORIGINS", true, "passkeys (comma-separated)"},
	{"WEBAUTHN_RP_DISPLAY_NAME", true, "passkeys"},
	{"CATALOG_BUILD", false, "/catalog/*.json builder (0 turns the daily build off; needs FUTAR_API_KEY otherwise)"},
	{"TRUSTED_PROXY", false, "client IP for rate limits (cloudflare, 1, or unset)"},
}

// watchedPrefixes are the prefixes of our own variables; a variable with one of
// them that is not in Vars is almost certainly a typo.
var watchedPrefixes = []string{"NEPTUN_", "FUTAR_", "VAPID_", "WEBAUTHN_", "TRUSTED_PROXY", "CATALOG_"}

// Problem is one finding of Check.
type Problem struct {
	Fatal   bool
	Message string
}

// Check inspects the environment (environ is os.Environ() style "K=V" pairs, getenv
// reads one value) and returns what is missing or suspicious.
func Check(getenv func(string) string, environ []string) []Problem {
	var out []Problem
	known := map[string]bool{}
	for _, v := range Vars {
		known[v.Name] = true
		if strings.TrimSpace(getenv(v.Name)) != "" {
			continue
		}
		if v.Required {
			out = append(out, Problem{true, fmt.Sprintf("%s is not set (needed by %s)", v.Name, v.Used)})
		} else {
			out = append(out, Problem{false, fmt.Sprintf("%s is not set (optional: %s)", v.Name, v.Used)})
		}
	}
	var unknown []string
	for _, kv := range environ {
		name, _, _ := strings.Cut(kv, "=")
		if known[name] {
			continue
		}
		for _, p := range watchedPrefixes {
			if strings.HasPrefix(name, p) {
				unknown = append(unknown, name)
				break
			}
		}
	}
	sort.Strings(unknown)
	for _, name := range unknown {
		out = append(out, Problem{false, fmt.Sprintf("%s is set but the backend does not read it (typo?)", name)})
	}
	return out
}

// Validate logs every finding of Check. It returns an error naming the
// missing required variables only when strict is true; otherwise a missing
// required variable is logged loudly and the affected route keeps answering
// 500 as before, so a deploy that lacks one is not turned into an outage.
func Validate(getenv func(string) string, environ []string, strict bool) error {
	var fatal []string
	for _, p := range Check(getenv, environ) {
		if p.Fatal {
			log.Printf("config: ERROR %s", p.Message)
			fatal = append(fatal, p.Message)
		} else {
			log.Printf("config: warning %s", p.Message)
		}
	}
	if strict && len(fatal) > 0 {
		return fmt.Errorf("invalid configuration: %s", strings.Join(fatal, "; "))
	}
	return nil
}
