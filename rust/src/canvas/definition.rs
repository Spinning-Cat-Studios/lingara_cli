//! A canvas definition (Backend 2.10.26e §2), as far as apply reads it:
//! nodes with their kind, fields and whether a script runs them, the edges,
//! and the order the web's `flowDefinition.ts` walks them in.

use serde_json::Value;

#[derive(Debug, Clone, PartialEq)]
pub struct Node {
    pub id: String,
    pub kind: String,
    pub fields: Value,
    /// A script node: it runs only in the browser's sandbox (2.10.26b D5).
    pub custom: bool,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Definition {
    pub nodes: Vec<Node>,
    pub edges: Vec<(String, String)>,
}

impl Definition {
    pub fn read(value: &Value) -> Definition {
        let nodes = value["nodes"].as_array().cloned().unwrap_or_default();
        let edges = value["edges"].as_array().cloned().unwrap_or_default();
        Definition {
            nodes: nodes
                .iter()
                .map(|n| Node {
                    id: n["id"].as_str().unwrap_or_default().to_string(),
                    kind: n["kind"].as_str().unwrap_or_default().to_string(),
                    fields: n["fields"].clone(),
                    custom: n["custom"].as_bool().unwrap_or(false) || n["script"].is_string(),
                })
                .collect(),
            edges: edges
                .iter()
                .map(|e| (e["from"].as_str().unwrap_or_default().to_string(), e["to"].as_str().unwrap_or_default().to_string()))
                .collect(),
        }
    }

    /// Kahn's algorithm, ties broken by node order, as `topoOrder`; `None`
    /// on a cycle.
    pub fn topo_order(&self) -> Option<Vec<&Node>> {
        let mut indegree: Vec<usize> = self.nodes.iter().map(|n| self.edges.iter().filter(|(_, to)| *to == n.id).count()).collect();
        let mut done = vec![false; self.nodes.len()];
        let mut order = Vec::new();
        while order.len() < self.nodes.len() {
            let next = (0..self.nodes.len()).find(|&i| !done[i] && indegree[i] == 0)?;
            done[next] = true;
            order.push(&self.nodes[next]);
            for (from, to) in &self.edges {
                if *from == self.nodes[next].id
                    && let Some(j) = self.nodes.iter().position(|n| n.id == *to)
                {
                    indegree[j] = indegree[j].saturating_sub(1);
                }
            }
        }
        Some(order)
    }

    /// A dependent node's client: its one direct upstream `client` node, or
    /// `None` for zero or two (`clientHandleOf`).
    pub fn client_of(&self, handle: &str) -> Option<&str> {
        let parents: Vec<&Node> = self
            .nodes
            .iter()
            .filter(|n| n.kind == "client" && self.edges.iter().any(|(from, to)| *from == n.id && to == handle))
            .collect();
        match parents.as_slice() {
            [one] => Some(one.id.as_str()),
            _ => None,
        }
    }
}
