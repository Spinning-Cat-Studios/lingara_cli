# frozen_string_literal: true

module Lingara
  module CLI
    module Canvas
      # A canvas definition (Backend 2.10.26e §2), as far as apply reads it,
      # and the order the web's `flowDefinition.ts` walks it in.
      module Definition
        # `custom`: a script node, which runs only in the browser's sandbox
        # (2.10.26b D5).
        Node = Struct.new(:id, :kind, :fields, :custom)
        Graph = Struct.new(:nodes, :edges)

        def self.rec(value) = value.is_a?(Hash) ? value : {}
        def self.str(value) = value.is_a?(String) ? value : ""

        def self.read(value)
          d = rec(value)
          nodes = Array(d["nodes"]).map { |n| rec(n) }.map do |n|
            Node.new(str(n["id"]), str(n["kind"]), rec(n["fields"]), n["custom"] == true || n["script"].is_a?(String))
          end
          edges = Array(d["edges"]).map { |e| [str(rec(e)["from"]), str(rec(e)["to"])] }
          Graph.new(nodes, edges)
        end

        # Kahn's algorithm, ties broken by node order, as `topoOrder`; nil on a cycle.
        def self.topo_order(graph)
          indegree = graph.nodes.to_h { |n| [n.id, graph.edges.count { |(_, to)| to == n.id }] }
          done = {}
          order = []
          while order.size < graph.nodes.size
            nxt = graph.nodes.find { |n| !done[n.id] && indegree[n.id].zero? }
            return nil if nxt.nil?

            order << nxt
            done[nxt.id] = true
            graph.edges.each { |(from, to)| indegree[to] -= 1 if from == nxt.id && indegree.key?(to) }
          end
          order
        end

        # A dependent node's client: its one direct upstream `client` node,
        # or nil for zero or two.
        def self.client_of(graph, handle)
          parents = graph.nodes.select { |n| n.kind == "client" && graph.edges.include?([n.id, handle]) }
          (parents.size == 1) ? parents.first.id : nil
        end
      end
    end
  end
end
