# frozen_string_literal: true

require_relative "definition"
require_relative "spec"
require_relative "../dev/manifest"

module Lingara
  module CLI
    module Canvas
      # 16.10.26ad D7 step 2: the web planner's rules (`plan.ts`) over field
      # nodes. Ops come out in topological order and every client reference
      # is a backward `{handle}`. "Still live" means live **and** still on
      # the recorded client: a recorded client is live when the client list
      # has a row with its `runtime_id`; a webhook or app when its list has
      # its `runtime_id` under that client's platform object; a secret when
      # its client is (the platform lists carry no secrets, and a recorded
      # secret is never re-planned). A node with `custom: true` runs a
      # script, which only the browser may do, so it refuses the plan by its
      # handle.
      module Plan
        # One `/platform/objects` list row, as the planner joins it.
        Row = Struct.new(:id, :runtime_id, :parent_id)
        # The live `oauth_client`, `webhook_out` and `app` lists (D7 step 1).
        Live = Struct.new(:clients, :webhooks, :apps)

        # Why a plan is refused: `:script`, `:fields`, `:no_client`,
        # `:cycle`, or `:ephemeral` (an app node rendering from a quick
        # tunnel or `dev.invalid`, 16.10.26ae D10 row 15).
        class Refusal < StandardError
          attr_reader :why, :handle

          def initialize(why, handle, detail)
            super(detail)
            @why = why
            @handle = handle
          end
        end

        def self.row_of(value)
          r = value.is_a?(Hash) ? value : {}
          Row.new(*%w[id runtime_id parent_id].map { |k| r[k].is_a?(String) ? r[k] : "" })
        end

        def self.rows_of(live, kind)
          case kind
          when "client" then live.clients
          when "webhook" then live.webhooks
          else live.apps
          end
        end

        # The platform object id behind a recorded runtime id of `kind`.
        def self.platform_id(live, kind, runtime_id) = rows_of(live, kind).find { |r| r.runtime_id == runtime_id }&.id

        def self.dependant_live?(node, record, client_platform_id, live)
          return true if node.kind == "secret"

          rows_of(live, node.kind).any? { |r| r.runtime_id == record && r.parent_id == client_platform_id }
        end

        # The recorded entries (`{handle => runtime id}`) still live by the rule above.
        def self.still_live(graph, recorded, live)
          (Definition.topo_order(graph) || []).each_with_object({}) do |node, kept|
            record = recorded[node.id]
            next if record.nil?

            client_record = (node.kind == "client") ? record : kept[Definition.client_of(graph, node.id).to_s]
            client = live.clients.find { |c| c.runtime_id == client_record }
            keep = (node.kind == "client") ? !client.nil? : (client && dependant_live?(node, record, client.id, live))
            kept[node.id] = record if keep
          end
        end

        def self.spelled(node, create)
          case node.kind
          when "client" then Spec.client_body(node.fields, create)
          when "webhook" then Spec.webhook_body(node.fields)
          when "app" then Spec.app_body(node.fields)
          end
        rescue TypeError => e
          raise Refusal.new(:fields, node.id, e.message)
        end

        def self.body_for(node, create)
          body = spelled(node, create)
          render_url = body&.dig("manifest", "render_url")
          if node.kind == "app" && Manifest.ephemeral?(render_url.is_a?(String) ? render_url : "")
            raise Refusal.new(:ephemeral, node.id, "renders from an ephemeral URL")
          end

          body
        end

        # One node's op: `create` without a kept record, `update` with one; a
        # dependant names its one client.
        def self.op_for(graph, node, record)
          client = (node.kind == "client") ? nil : Definition.client_of(graph, node.id)
          raise Refusal.new(:no_client, node.id, "needs exactly one client node upstream") if node.kind != "client" && client.nil?

          op = {"handle" => node.id, "kind" => node.kind, "op" => record.nil? ? "create" : "update"}
          op["client"] = {"handle" => client} if client && op["op"] == "create"
          op["id"] = record if record
          body = body_for(node, op["op"] == "create")
          op["body"] = body if body
          op
        end

        # Every field node's op, in plan order, over the kept records.
        def self.build(graph, kept)
          order = Definition.topo_order(graph)
          raise Refusal.new(:cycle, "", "the canvas has a cycle") if order.nil?

          scripted = order.find(&:custom)
          raise Refusal.new(:script, scripted.id, "runs a script") if scripted

          # A recorded secret is never re-planned.
          order.reject { |n| n.kind == "secret" && kept.key?(n.id) }.map { |n| op_for(graph, n, kept[n.id]) }
        end
      end
    end
  end
end
