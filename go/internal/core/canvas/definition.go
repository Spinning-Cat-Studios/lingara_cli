// Package canvas is canvas apply's pure half (ADR 16.10.26ad D7; contract
// B7): the definition, the web planner's rules and the argv each op becomes.
// Both plan and argv are pinned by conformance/vectors/canvas-plans.json,
// which every kit's tests read: nothing ties this planner to the web's
// plan.ts mechanically, so a change there updates the vectors by hand.
package canvas

import "getlingara.com/cli/internal/jsonv"

// Node is one definition node, as far as apply reads it.
type Node struct {
	ID     string
	Kind   string
	Fields any
	// Custom is a script node: it runs only in the browser's sandbox
	// (2.10.26b D5).
	Custom bool
}

// Edge is one definition edge.
type Edge struct{ From, To string }

// Definition is a canvas definition (Backend 2.10.26e §2): nodes, edges,
// and the order the web's flowDefinition.ts walks them in.
type Definition struct {
	Nodes []Node
	Edges []Edge
}

// ReadDefinition reads a definition leniently: what is missing is empty.
func ReadDefinition(v any) Definition {
	var d Definition
	for _, n := range jsonv.Arr(jsonv.Get(v, "nodes")) {
		custom, _ := jsonv.Bool(jsonv.Get(n, "custom"))
		_, script := jsonv.Str(jsonv.Get(n, "script"))
		d.Nodes = append(d.Nodes, Node{
			ID:     jsonv.Text(n, "id"),
			Kind:   jsonv.Text(n, "kind"),
			Fields: jsonv.Get(n, "fields"),
			Custom: custom || script,
		})
	}
	for _, e := range jsonv.Arr(jsonv.Get(v, "edges")) {
		d.Edges = append(d.Edges, Edge{From: jsonv.Text(e, "from"), To: jsonv.Text(e, "to")})
	}
	return d
}

// TopoOrder is Kahn's algorithm, ties broken by node order, as topoOrder;
// false on a cycle.
func (d Definition) TopoOrder() ([]Node, bool) {
	indegree := make([]int, len(d.Nodes))
	for i, n := range d.Nodes {
		for _, e := range d.Edges {
			if e.To == n.ID {
				indegree[i]++
			}
		}
	}
	done := make([]bool, len(d.Nodes))
	var order []Node
	for len(order) < len(d.Nodes) {
		next := d.nextReady(done, indegree)
		if next < 0 {
			return nil, false
		}
		done[next] = true
		order = append(order, d.Nodes[next])
		d.release(d.Nodes[next].ID, indegree)
	}
	return order, true
}

func (d Definition) nextReady(done []bool, indegree []int) int {
	for i := range d.Nodes {
		if !done[i] && indegree[i] == 0 {
			return i
		}
	}
	return -1
}

func (d Definition) release(from string, indegree []int) {
	for _, e := range d.Edges {
		if e.From != from {
			continue
		}
		if j := d.index(e.To); j >= 0 && indegree[j] > 0 {
			indegree[j]--
		}
	}
}

func (d Definition) index(id string) int {
	for i, n := range d.Nodes {
		if n.ID == id {
			return i
		}
	}
	return -1
}

// ClientOf is a dependent node's client: its one direct upstream client
// node, or false for zero or two (clientHandleOf).
func (d Definition) ClientOf(handle string) (string, bool) {
	var parents []string
	for _, n := range d.Nodes {
		if n.Kind != "client" {
			continue
		}
		for _, e := range d.Edges {
			if e.From == n.ID && e.To == handle {
				parents = append(parents, n.ID)
				break
			}
		}
	}
	if len(parents) != 1 {
		return "", false
	}
	return parents[0], true
}

// HasNode reports whether a node has this id.
func (d Definition) HasNode(id string) bool {
	return d.index(id) >= 0
}
