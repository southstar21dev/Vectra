package config

import (
	"strings"
	"testing"
)

func envFrom(m map[string]string) (func(string) string, []string) {
	var environ []string
	for k, v := range m {
		environ = append(environ, k+"="+v)
	}
	return func(k string) string { return m[k] }, environ
}

func full() map[string]string {
	m := map[string]string{}
	for _, v := range Vars {
		m[v.Name] = "x"
	}
	return m
}

func TestCheckCompleteEnvironmentIsClean(t *testing.T) {
	get, env := envFrom(full())
	if got := Check(get, env); len(got) != 0 {
		t.Fatalf("unexpected problems: %v", got)
	}
}

func TestCheckMissingRequiredIsFatalOptionalIsNot(t *testing.T) {
	m := full()
	delete(m, "WEBAUTHN_RP_ORIGINS")
	delete(m, "FUTAR_UPSTREAM_PER_MINUTE")
	get, env := envFrom(m)
	var fatal, soft []string
	for _, p := range Check(get, env) {
		if p.Fatal {
			fatal = append(fatal, p.Message)
		} else {
			soft = append(soft, p.Message)
		}
	}
	if len(fatal) != 1 || !strings.Contains(fatal[0], "WEBAUTHN_RP_ORIGINS") {
		t.Fatalf("fatal = %v", fatal)
	}
	if len(soft) != 1 || !strings.Contains(soft[0], "FUTAR_UPSTREAM_PER_MINUTE") {
		t.Fatalf("soft = %v", soft)
	}
}

func TestCheckFlagsTypos(t *testing.T) {
	m := full()
	m["WEBAUTHN_RP_ORIGIN"] = "https://x" // singular: a typo
	m["HOME"] = "/root"
	get, env := envFrom(m)
	got := Check(get, env)
	if len(got) != 1 || got[0].Fatal || !strings.Contains(got[0].Message, "WEBAUTHN_RP_ORIGIN ") {
		t.Fatalf("got %v", got)
	}
}

func TestValidateOnlyFailsWhenStrict(t *testing.T) {
	m := full()
	delete(m, "FUTAR_API_KEY")
	get, env := envFrom(m)
	if err := Validate(get, env, false); err != nil {
		t.Fatalf("lenient: %v", err)
	}
	err := Validate(get, env, true)
	if err == nil || !strings.Contains(err.Error(), "FUTAR_API_KEY") {
		t.Fatalf("strict: %v", err)
	}
}
