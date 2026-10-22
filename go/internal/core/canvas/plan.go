package canvas

import (
	"getlingara.com/cli/internal/core/manifest"
	"getlingara.com/cli/internal/jsonv"
)

// D7 step 2: the web planner's rules (plan.ts) over field nodes.
//
// Ops come out in topological order and every client reference is a
// backward {handle}. "Still live" means live and still on the recorded
// client: a recorded client is live when the client list has a row with its
// runtime_id; a webhook or app when its list has its runtime_id under that
// client's platform object; a secret when its client is (the platform lists
// carry no secrets, and a recorded secret is never re-planned). A node with
// custom: true runs a script, which only the browser's sandbox may do, so
// it refuses the plan by its handle.

// Records is node handle → the runtime id an earlier apply recorded.
type Records map[string]string

// Row is one /platform/objects list row, as the planner joins it.
type Row struct {
	ID, RuntimeID, ParentID string
}

// ReadRow reads a list item.
func ReadRow(v any) Row {
	return Row{ID: jsonv.Text(v, "id"), RuntimeID: jsonv.Text(v, "runtime_id"), ParentID: jsonv.Text(v, "parent_id")}
}

// Live is the live oauth_client, webhook_out and app lists (D7 step 1).
type Live struct {
	Clients, Webhooks, Apps []Row
}

func (l Live) clientByRuntime(runtimeID string) (Row, bool) {
	return find(l.Clients, runtimeID)
}

func find(rows []Row, runtimeID string) (Row, bool) {
	for _, r := range rows {
		if r.RuntimeID == runtimeID {
			return r, true
		}
	}
	return Row{}, false
}

// PlatformID is the platform object id behind a recorded runtime id of kind.
func (l Live) PlatformID(kind, runtimeID string) (string, bool) {
	rows := l.Apps
	switch kind {
	case "client":
		rows = l.Clients
	case "webhook":
		rows = l.Webhooks
	}
	r, ok := find(rows, runtimeID)
	return r.ID, ok
}

// Op is {handle, kind, op, client?: {handle}, id?, body?}, the dry-run's
// shape.
type Op struct {
	Handle string
	Kind   string
	Op     string
	Client string
	ID     string
	Body   any
}

// Wire is the op as the dry-run takes it.
func (o Op) Wire() *jsonv.Object {
	v := jsonv.Obj("handle", o.Handle, "kind", o.Kind, "op", o.Op)
	if o.Client != "" {
		v.Set("client", jsonv.Obj("handle", o.Client))
	}
	if o.ID != "" {
		v.Set("id", o.ID)
	}
	if o.Body != nil {
		v.Set("body", o.Body)
	}
	return v
}

// Refusal is why a plan cannot be built. Kind is script, fields, no_client,
// cycle or ephemeral (an app node whose manifest's render URL is a quick
// tunnel or dev.invalid, 16.10.26ae D10 row 15).
type Refusal struct {
	Kind   string
	Handle string
	Detail string
}

// StillLive is the recorded entries still live by the rule above.
func StillLive(def Definition, recorded Records, live Live) Records {
	kept := Records{}
	order, _ := def.TopoOrder()
	for _, node := range order {
		record, ok := recorded[node.ID]
		if ok && keep(def, node, record, kept, live) {
			kept[node.ID] = record
		}
	}
	return kept
}

func keep(def Definition, node Node, record string, kept Records, live Live) bool {
	if node.Kind == "client" {
		_, ok := live.clientByRuntime(record)
		return ok
	}
	handle, ok := def.ClientOf(node.ID)
	if !ok {
		return false
	}
	clientRecord, ok := kept[handle]
	if !ok {
		return false
	}
	client, ok := live.clientByRuntime(clientRecord)
	if !ok {
		return false
	}
	return dependantLive(node, record, client.ID, live)
}

func dependantLive(node Node, record, clientPlatformID string, live Live) bool {
	rows := live.Apps
	switch node.Kind {
	case "secret":
		return true
	case "webhook":
		rows = live.Webhooks
	}
	for _, r := range rows {
		if r.RuntimeID == record && r.ParentID == clientPlatformID {
			return true
		}
	}
	return false
}

func bodyFor(node Node, create bool) (any, *Refusal) {
	var body any
	var invalid string
	switch node.Kind {
	case "client":
		body, invalid = clientSpec(node.Fields, create)
	case "webhook":
		body, invalid = webhookSpec(node.Fields)
	case "app":
		body, invalid = appSpec(node.Fields)
	default:
		return nil, nil
	}
	if invalid != "" {
		return nil, &Refusal{Kind: "fields", Handle: node.ID, Detail: invalid}
	}
	if node.Kind == "app" && manifest.Ephemeral(jsonv.Text(body, "manifest", "render_url")) {
		return nil, &Refusal{Kind: "ephemeral", Handle: node.ID}
	}
	return body, nil
}

// Build is D7 step 2: every field node's op, in plan order, over the kept
// records.
func Build(def Definition, kept Records) ([]Op, *Refusal) {
	order, ok := def.TopoOrder()
	if !ok {
		return nil, &Refusal{Kind: "cycle"}
	}
	for _, n := range order {
		if n.Custom {
			return nil, &Refusal{Kind: "script", Handle: n.ID}
		}
	}
	var ops []Op
	for _, node := range order {
		record, recorded := kept[node.ID]
		if recorded && node.Kind == "secret" {
			continue
		}
		op, refusal := planOp(def, node, record, recorded)
		if refusal != nil {
			return nil, refusal
		}
		ops = append(ops, op)
	}
	return ops, nil
}

func planOp(def Definition, node Node, record string, recorded bool) (Op, *Refusal) {
	op := Op{Handle: node.ID, Kind: node.Kind, Op: "create"}
	if node.Kind != "client" {
		client, ok := def.ClientOf(node.ID)
		if !ok {
			return op, &Refusal{Kind: "no_client", Handle: node.ID}
		}
		op.Client = client
	}
	if recorded {
		op.Op, op.Client, op.ID = "update", "", record
	}
	body, refusal := bodyFor(node, op.Op == "create")
	op.Body = body
	return op, refusal
}
