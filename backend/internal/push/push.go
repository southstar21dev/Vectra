package push

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/SherClockHolmes/webpush-go"
	"github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"

	"vectra/pb/internal/ratelimit"
)

// Sends a push notification to a specific user's subscribed devices via
// Web Push (VAPID) to browser subscribers (push_subscriptions.transport='webpush').
//
// Only the target user (matching the JWT auth record) or a PocketBase
// superuser (internal cron/service callers) may trigger a push for a given
// user; otherwise anyone with an account could send phishing/spam pushes.

const (
	// webpushSendTimeout bounds each Web Push HTTP request. webpush-go defaults
	// to a plain &http.Client{} (no timeout), and Handler waits on every
	// delivery goroutine, so without this a single hanging push endpoint would
	// pin the request indefinitely.
	webpushSendTimeout = 15 * time.Second
	webpushTTL         = 60 * 60 * 24 // 24 hours
	defaultBadgePath   = "/icons/badge-96.png"
	defaultTag         = "vectra-notification"
	defaultURL         = "/"

	// maxSubscriptionsPerUser caps a user's push_subscriptions rows (see
	// RegisterSubscriptionValidation). Every row is a delivery on each send,
	// so without it one account could store thousands of endpoints and turn
	// a single /push/send into a large outbound fan-out.
	maxSubscriptionsPerUser = 10

	// sendConcurrency bounds the in-flight Web Push requests per Handler call.
	sendConcurrency = 8

	// sendsPerWindow caps /push/send calls per (non-superuser) caller per
	// sendWindow. Users only trigger their own test notifications; superuser
	// service callers (cron fan-out) are exempt.
	sendsPerWindow = 20
	sendWindow     = time.Minute
)

// sendLimiter is the per-user limiter for Handler. It is a var so tests can
// substitute a fresh one.
var sendLimiter = ratelimit.New(sendsPerWindow, sendWindow)

// request mirrors the Deno PushPayload interface.
type request struct {
	UserID     string   `json:"user_id"`
	Title      string   `json:"title"`
	Body       string   `json:"body"`
	Badge      string   `json:"badge"`
	URL        string   `json:"url"`
	Tag        string   `json:"tag"`
	Transports []string `json:"transports"`
}

// Handler implements POST /api/vectra/push/send (JWT-gated). It mirrors the
// Deno function's response body {sent, total, results}.
func Handler(e *core.RequestEvent) error {
	var body request
	if err := e.BindBody(&body); err != nil {
		return e.BadRequestError("Invalid request body", err)
	}
	if body.UserID == "" || body.Title == "" || body.Body == "" {
		return e.BadRequestError("Missing required fields: user_id, title, body", nil)
	}

	// Authorization: the target user themselves or a superuser (internal
	// service-role caller). The route is bound with apis.RequireAuth(), so
	// e.Auth is expected to be set; the nil guard avoids a panic on direct use.
	if e.Auth == nil || (!e.Auth.IsSuperuser() && e.Auth.Id != body.UserID) {
		return e.ForbiddenError("Not authorized to send a push for this user", nil)
	}
	if !e.Auth.IsSuperuser() && !sendLimiter.Allow(e.Auth.Id, time.Now()) {
		return e.TooManyRequestsError("Too many push requests. Please wait a moment and try again.", nil)
	}

	vapidPublic := os.Getenv("VAPID_PUBLIC_KEY")
	vapidPrivate := os.Getenv("VAPID_PRIVATE_KEY")
	if vapidPublic == "" || vapidPrivate == "" {
		return e.InternalServerError(
			"VAPID keys not configured. Set VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY in the function secrets.", nil)
	}
	// webpush-go prepends "mailto:" to anything that isn't an https: URL, so a
	// "mailto:" prefix here would produce "mailto:mailto:..." and Apple answers
	// that malformed JWT subject with 403 BadJwtToken. Pass the bare address.
	vapidSubject := strings.TrimPrefix(strings.TrimSpace(os.Getenv("VAPID_SUBJECT")), "mailto:")
	if vapidSubject == "" {
		vapidSubject = "admin@vectra.app"
	}

	// Newest first, so the dedup below keeps the most recently refreshed row
	// (its keys are the ones the browser currently holds).
	records, err := e.App.FindRecordsByFilter(
		"push_subscriptions",
		"user = {:u}",
		"-updated_at",
		0,
		0,
		dbx.Params{"u": body.UserID},
	)
	if err != nil {
		return e.InternalServerError("Failed to load push subscriptions", err)
	}
	if len(records) == 0 {
		return e.JSON(http.StatusOK, map[string]any{
			"message": "No push subscriptions found for user",
		})
	}

	// Deduplicate by endpoint so duplicate DB rows never cause duplicate
	// deliveries, and drop rows that can never be delivered (unparseable JSON,
	// no endpoint, or an endpoint outside the push-service allowlist); delete
	// both best-effort.
	seen := map[string]bool{}
	unique := make([]*core.Record, 0, len(records))
	var discard []*core.Record
	for _, rec := range records {
		endpoint, ok := endpointOf(rec)
		if !ok || ValidateEndpoint(endpoint) != nil {
			discard = append(discard, rec)
			continue
		}
		key := "webpush:" + endpoint
		if seen[key] {
			discard = append(discard, rec)
			continue
		}
		seen[key] = true
		unique = append(unique, rec)
	}
	for _, rec := range discard {
		_ = e.App.Delete(rec) // best-effort, like the Deno void-delete
	}
	if len(unique) == 0 {
		return e.JSON(http.StatusOK, map[string]any{
			"message": "No push subscriptions found for user",
		})
	}

	filtered := unique

	tag := body.Tag
	if tag == "" {
		tag = defaultTag
	}
	url := body.URL
	if url == "" {
		url = defaultURL
	}
	badge := body.Badge
	if badge == "" {
		badge = defaultBadgePath
	}

	// No `icon` is sent: the service worker picks it per platform. The `icon`
	// slot renders as the large icon on the right of Android notifications (an
	// extra icon the user doesn't want) and as the app icon on the left of
	// desktop toasts (which stays).
	notificationPayload, _ := json.Marshal(map[string]any{
		"title": body.Title,
		"body":  body.Body,
		"badge": badge,
		"data":  map[string]any{"url": url},
		"tag":   tag,
	})

	// webpush topic: base64url-safe digest of the tag (see topicFor).
	topic := ""
	if body.Tag != "" {
		topic = topicFor(body.Tag)
	}

	d := &delivery{
		app:              e.App,
		ctx:              e.Request.Context(),
		vapidPublic:      vapidPublic,
		vapidPrivate:     vapidPrivate,
		vapidSubject:     vapidSubject,
		notificationBody: notificationPayload,
		topic:            topic,
		// Upstream reply bodies are operator diagnostics; only superusers
		// (service callers) see them.
		exposeDetail: e.Auth.IsSuperuser(),
	}

	results := d.sendAll(filtered)

	sent := 0
	for _, r := range results {
		if v, ok := r.Value.(map[string]any); ok && v["success"] == true {
			sent++
		}
	}

	return e.JSON(http.StatusOK, map[string]any{
		"sent":    sent,
		"total":   len(filtered),
		"results": results,
	})
}

// sendAll delivers to every record through at most sendConcurrency concurrent
// sends. AllSettled envelope, mirroring Promise.allSettled in the Deno
// function: every branch catches its own errors, so all entries are
// "fulfilled". Index assignment preserves result order.
func (d *delivery) sendAll(records []*core.Record) []settledResult {
	results := make([]settledResult, len(records))
	sem := make(chan struct{}, sendConcurrency)
	var wg sync.WaitGroup
	for i, rec := range records {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, rec *core.Record) {
			defer wg.Done()
			defer func() { <-sem }()
			results[i] = settledResult{Status: "fulfilled", Value: d.send(rec)}
		}(i, rec)
	}
	wg.Wait()
	return results
}

type settledResult struct {
	Status string `json:"status"`
	Value  any    `json:"value"`
}

// delivery carries the per-request send configuration.
type delivery struct {
	app              core.App
	ctx              context.Context
	vapidPublic      string
	vapidPrivate     string
	vapidSubject     string
	notificationBody []byte
	topic            string
	exposeDetail     bool

	// Overridable network hook, left nil in production (Handler). Tests inject
	// a stub so the delivery branch can be exercised without real sends.
	webpushSendFn func(message []byte, sub *webpush.Subscription, opts *webpush.Options) (*http.Response, error)
}

// send dispatches one subscription row and returns the result value object,
// mirroring the object each Deno map callback returns.
func (d *delivery) send(rec *core.Record) map[string]any {
	return d.sendWebPush(rec)
}

func (d *delivery) sendWebPush(rec *core.Record) map[string]any {
	var sub webpush.Subscription
	if err := json.Unmarshal(subscriptionOf(rec), &sub); err != nil || ValidateEndpoint(sub.Endpoint) != nil {
		// Never dial an endpoint outside the allowlist (Handler already filters
		// these; this guards direct callers too).
		d.deleteRecord(rec)
		return map[string]any{"success": false, "removed": true, "reason": "invalid-subscription", "endpoint": sub.Endpoint}
	}
	endpoint := sub.Endpoint

	resp, err := d.webpushSend(&sub)
	if err != nil {
		// NB: webpush-go returns non-2xx responses as a response (no error),
		// so status handling happens below; transport/encryption failures land
		// here.
		return map[string]any{"success": false, "error": err.Error(), "endpoint": endpoint}
	}
	defer resp.Body.Close()
	// Keep a bounded slice of the push service's reply: on 4xx it names the
	// reason (e.g. VAPID key mismatch), which is otherwise invisible.
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	_, _ = io.Copy(io.Discard, resp.Body)

	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone {
		// Subscription is stale/expired — remove it so future sends skip it.
		d.deleteRecord(rec)
		return map[string]any{"success": false, "removed": true, "reason": "stale-subscription", "endpoint": endpoint}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		out := map[string]any{
			"success":    false,
			"error":      "Send failed",
			"statusCode": resp.StatusCode,
			"endpoint":   endpoint,
		}
		if d.exposeDetail {
			out["detail"] = strings.TrimSpace(string(respBody))
		}
		return out
	}
	return map[string]any{"success": true, "endpoint": endpoint}
}

// webpushSend builds the VAPID options and dispatches the web push, honouring
// the test override hook.
func (d *delivery) webpushSend(sub *webpush.Subscription) (*http.Response, error) {
	opts := &webpush.Options{
		Subscriber:      d.vapidSubject,
		TTL:             webpushTTL,
		Urgency:         webpush.UrgencyNormal,
		VAPIDPublicKey:  d.vapidPublic,
		VAPIDPrivateKey: d.vapidPrivate,
		Topic:           d.topic,
		HTTPClient: &http.Client{
			Timeout: webpushSendTimeout,
			// The endpoint host is allowlisted; a redirect would leave it.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}
	if d.webpushSendFn != nil {
		return d.webpushSendFn(d.notificationBody, sub, opts)
	}
	ctx := d.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	return webpush.SendNotificationWithContext(ctx, d.notificationBody, sub, opts)
}

func (d *delivery) deleteRecord(rec *core.Record) {
	_ = d.app.Delete(rec)
}

// ---------------------------------------------------------------------------
// record helpers
// ---------------------------------------------------------------------------

func subscriptionOf(rec *core.Record) types.JSONRaw {
	if raw, ok := rec.Get("subscription").(types.JSONRaw); ok {
		return raw
	}
	return nil
}

// endpointOf returns the subscription's endpoint, and false when the stored
// JSON does not parse or carries no endpoint.
func endpointOf(rec *core.Record) (string, bool) {
	var s struct {
		Endpoint string `json:"endpoint"`
	}
	if err := json.Unmarshal(subscriptionOf(rec), &s); err != nil || s.Endpoint == "" {
		return "", false
	}
	return s.Endpoint, true
}

// ---------------------------------------------------------------------------
// endpoint allowlist
// ---------------------------------------------------------------------------

// allowedPushHosts / allowedPushHostSuffixes are the browser push services a
// Web Push subscription endpoint may point at (FCM for Chrome/Edge-on-Android,
// Apple for Safari, WNS for Edge on Windows, Mozilla autopush for Firefox).
// The server POSTs to whatever endpoint a client stored, so without this a
// user could aim the send path at internal hosts (SSRF).
var (
	// Google's push services are listed by exact host: a generic
	// ".googleapis.com" suffix would also admit storage.googleapis.com and every
	// other Google API as a POST target.
	allowedPushHosts = []string{
		"fcm.googleapis.com",
		"jmt17.google.com",
		"updates.push.services.mozilla.com",
	}
	allowedPushHostSuffixes = []string{
		".push.apple.com",
		".notify.windows.com",
		".push.services.mozilla.com",
	}
)

// ValidateEndpoint reports whether endpoint is an https URL on the default
// port whose host is a known push service.
func ValidateEndpoint(endpoint string) error {
	if endpoint == "" {
		return errors.New("push endpoint is required")
	}
	u, err := url.Parse(endpoint)
	if err != nil {
		return errors.New("push endpoint is not a valid URL")
	}
	if u.Scheme != "https" || u.User != nil || u.Opaque != "" {
		return errors.New("push endpoint must be an https URL")
	}
	if p := u.Port(); p != "" && p != "443" {
		return errors.New("push endpoint must use the default https port")
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	for _, h := range allowedPushHosts {
		if host == h {
			return nil
		}
	}
	for _, suffix := range allowedPushHostSuffixes {
		if strings.HasSuffix(host, suffix) && len(host) > len(suffix) {
			return nil
		}
	}
	return errors.New("push endpoint host is not an allowed push service")
}

// RegisterSubscriptionValidation rejects push_subscriptions records created or
// updated through the records API whose endpoint is not an allowlisted push
// service, so an invalid endpoint is never stored in the first place, and
// after a successful create evicts the owner's oldest rows beyond
// maxSubscriptionsPerUser (see pruneSubscriptions). Call it once during boot.
func RegisterSubscriptionValidation(app core.App) {
	validate := func(e *core.RecordRequestEvent) error {
		endpoint, ok := endpointOf(e.Record)
		if !ok {
			return e.BadRequestError("Invalid push subscription", nil)
		}
		if err := ValidateEndpoint(endpoint); err != nil {
			return e.BadRequestError("Invalid push subscription endpoint", nil)
		}
		return e.Next()
	}
	evictOldest := func(e *core.RecordRequestEvent) error {
		if err := e.Next(); err != nil {
			return err
		}
		// Best-effort: the new row is already saved, so a failed prune must
		// not fail the create.
		pruneSubscriptions(e.App, e.Record)
		return nil
	}
	app.OnRecordCreateRequest("push_subscriptions").BindFunc(validate)
	app.OnRecordCreateRequest("push_subscriptions").BindFunc(evictOldest)
	app.OnRecordUpdateRequest("push_subscriptions").BindFunc(validate)
}

// pruneSubscriptions keeps the owner of created at no more than
// maxSubscriptionsPerUser distinct endpoints, deleting the oldest rows (by
// created_at) beyond that, so a new subscription always succeeds instead of
// locking a user with stale devices out. Rows sharing an endpoint with a newer
// row (including created itself) do not count toward the cap: they are one
// device, and Handler already dedupes them by endpoint. The client deletes a
// device's previous row before re-subscribing, so that path never evicts.
func pruneSubscriptions(app core.App, created *core.Record) {
	others, err := app.FindRecordsByFilter(
		"push_subscriptions",
		"user = {:u} && id != {:id}",
		"-created_at",
		0,
		0,
		dbx.Params{"u": created.GetString("user"), "id": created.Id},
	)
	if err != nil {
		return
	}
	seen := map[string]bool{}
	if endpoint, ok := endpointOf(created); ok {
		seen[endpoint] = true
	}
	for _, rec := range others {
		endpoint, ok := endpointOf(rec)
		if ok && seen[endpoint] {
			continue
		}
		if len(seen) >= maxSubscriptionsPerUser {
			_ = app.Delete(rec)
			continue
		}
		if ok {
			seen[endpoint] = true
		}
	}
}

// topicFor derives a Web Push Topic header from a notification tag. Apple's
// push service requires a valid base64url value of at most 32 characters
// (anything else is rejected with 400 BadWebPushTopic, e.g. a 17-char tag is
// not a valid base64 length), so the tag is hashed and cut to exactly 32
// characters. The same tag always maps to the same topic, so same-tag
// notifications still coalesce while a device is offline.
func topicFor(tag string) string {
	sum := sha256.Sum256([]byte(tag))
	return base64.RawURLEncoding.EncodeToString(sum[:])[:32]
}
