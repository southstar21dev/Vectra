package versiongate

import "testing"

func TestRequiredVersion(t *testing.T) {
	on := Config{MinIos: "1.6.0", BlockLegacy: true}
	off := Config{MinIos: "1.6.0"}
	ptr := func(s string) *string { return &s }

	cases := []struct {
		name string
		req  Request
		cfg  Config
		want *string
	}{
		{"older iOS refused", Request{Version: "1.4.2", Platform: "ios"}, on, ptr("1.6.0")},
		{"equal iOS served", Request{Version: "1.6.0", Platform: "ios"}, on, nil},
		{"compared numerically", Request{Version: "1.10.0", Platform: "ios"}, on, nil},
		{"no Android minimum", Request{Version: "1.0.0", Platform: "android"}, on, nil},
		{"Android minimum applies", Request{Version: "1.8.4", Platform: "android"}, Config{MinAndroid: "v1.9"}, ptr("1.9")},
		{"unknown platform served", Request{Version: "0.1.0", Platform: "web"}, on, nil},
		{"legacy ktor refused", Request{UserAgent: "ktor-client"}, on, ptr("")},
		{"legacy ktor served when off", Request{UserAgent: "ktor-client"}, off, nil},
		{"browser served", Request{UserAgent: "Mozilla/5.0"}, on, nil},
		{"wget served", Request{UserAgent: "Wget/1.21"}, on, nil},
		{"unparseable version served", Request{Version: "abc", Platform: "ios"}, on, nil},
		{"unparseable minimum served", Request{Version: "1.0.0", Platform: "ios"}, Config{MinIos: "soon"}, nil},
		{"empty minimum served", Request{Version: "1.0.0", Platform: "ios"}, Config{}, nil},
		{"prerelease of minimum served", Request{Version: "1.6.0-rc.1", Platform: "ios"}, on, nil},
		{"dev build below minimum refused", Request{Version: "1.5.9-dev.3", Platform: "ios"}, on, ptr("1.6.0")},
		{"platform case-insensitive", Request{Version: "1.0.0", Platform: "iOS"}, on, ptr("1.6.0")},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := RequiredVersion(tc.req, tc.cfg)
			switch {
			case tc.want == nil && got != nil:
				t.Fatalf("want served, got refused with %q", *got)
			case tc.want != nil && got == nil:
				t.Fatalf("want refused with %q, got served", *tc.want)
			case tc.want != nil && *got != *tc.want:
				t.Fatalf("want %q, got %q", *tc.want, *got)
			}
		})
	}
}
