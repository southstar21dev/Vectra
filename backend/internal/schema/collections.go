package schema

import (
	"ganigeorgiev.com/pocketbase/pocketbase/core"
	"ganigeorgiev.com/pocketbase/pocketbase/tools/types"
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

// OwnerCollections returns every non-system base collection that has a `user`
// relation to the users collection: the data a user owns. Derive the set from
// the live schema with it (account deletion relies on the relations' cascade,
// rule migrations and tests iterate it) instead of keeping another hand-made
// list. It includes server-managed collections such as passkeys, whose rules
// differ from OwnerRules; callers that set rules must skip those.
func OwnerCollections(app core.App) ([]*core.Collection, error) {
	users, err := app.FindCollectionByNameOrId("users")
	if err != nil {
		return nil, err
	}
	var all []*core.Collection
	if err := app.CollectionQuery().OrderBy("name").All(&all); err != nil {
		return nil, err
	}
	var owned []*core.Collection
	for _, c := range all {
		if c.System || !c.IsBase() {
			continue
		}
		rel, ok := c.Fields.GetByName("user").(*core.RelationField)
		if ok && rel.CollectionId == users.Id {
			owned = append(owned, c)
		}
	}
	return owned, nil
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
