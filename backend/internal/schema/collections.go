package schema

import (
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/types"
)

// OwnerRules applies the RLS-equivalent rules: a user may only see and mutate
// records where the `user` relation points at themselves. The update rule also
// pins `user` itself, so an owner cannot hand a record (and its contents) over
// to another account by PATCHing the relation.
func OwnerRules(c *core.Collection) {
	c.ListRule = types.Pointer("user = @request.auth.id")
	c.ViewRule = types.Pointer("user = @request.auth.id")
	c.CreateRule = types.Pointer("@request.auth.id != '' && @request.body.user = @request.auth.id")
	c.UpdateRule = types.Pointer("user = @request.auth.id && (@request.body.user:isset = false || @request.body.user = @request.auth.id)")
	c.DeleteRule = types.Pointer("user = @request.auth.id")
}

// SelfRules applies user-record rules: a user may read/update only themselves.
//
// legacy_id is server-owned on both create and update: it is the Supabase UUID
// that passkeys use as the WebAuthn user handle, so a client-chosen value could
// impersonate a migrated account's handle. created_at is server-set on create
// (RegisterTimestamps) and immutable afterwards. `verified` needs no rule:
// PocketBase already refuses changes to it from non-managers.
func SelfRules(c *core.Collection) {
	c.ListRule = types.Pointer("id = @request.auth.id")
	c.ViewRule = types.Pointer("id = @request.auth.id")
	c.CreateRule = types.Pointer("@request.body.legacy_id:isset = false") // signup is public
	c.UpdateRule = types.Pointer("id = @request.auth.id && @request.body.legacy_id:isset = false && @request.body.created_at:isset = false")
	c.DeleteRule = nil // superuser only; account deletion goes through our route
}
