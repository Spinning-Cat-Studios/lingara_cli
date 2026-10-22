# frozen_string_literal: true

require_relative "plan"

module Lingara
  module CLI
    module Canvas
      # 16.10.26ad D7 steps 3–4: the dry-run's verdicts over the plan become
      # this CLI's own command lines. A `refused` verdict stops apply with
      # every refusal named; a `would_replace`, which the token door no
      # longer sends, stops it before any write. A `would_update` webhook
      # carries exactly the flags its `changes` name: `--url`/`--event` (a
      # new version that keeps the endpoint and its secret) and `--enable`
      # (no builder spec disables one).
      #
      # A word of an argv is `[:text, s]`, `[:made, handle]` (an id an
      # earlier step makes) or `[:manifest, handle]` (an app op's manifest
      # file). What a step records is `:runtime_id`, `[:client_secret,
      # handle]`, `:secret_id` or `:unchanged` (16.10.26x D4: runtime ids only).
      module Argv
        Step = Struct.new(:handle, :kind, :action, :argv, :records)

        # Why apply stops before writing: every refusal, or the node a
        # `would_replace` names.
        class Stop < StandardError
          attr_reader :refused, :replace

          def initialize(refused, replace = nil)
            super(replace.nil? ? "refused" : "would_replace")
            @refused = refused
            @replace = replace
          end
        end

        def self.t(text) = [:text, text]
        def self.s(value) = value.is_a?(String) ? value : ""
        def self.strings(value) = value.is_a?(Array) ? value.grep(String) : []
        def self.repeated(flag, values) = values.flat_map { |v| [t(flag), t(v)] }

        # Every step, or why apply must stop before writing anything.
        def self.steps(ops, verdicts, live, kept)
          refused = verdicts.select { |v| v["verdict"] == "refused" }.map { |v| [s(v["handle"]), s(v["code"]), s(v["error"])] }
          raise Stop.new(refused) unless refused.empty?

          replace = verdicts.find { |v| v["verdict"] == "would_replace" }
          raise Stop.new([], s(replace["handle"])) if replace

          ops.map { |op| step(op, verdicts.find { |v| v["handle"] == op["handle"] } || {}, live, kept, ops) }
        end

        # The client an op points at: made in this run, or a recorded one's
        # platform object id.
        def self.client_ref(handle, live, kept, ops)
          made_here = ops.any? { |o| o["handle"] == handle && o["op"] == "create" }
          known = kept.key?(handle) ? Plan.platform_id(live, "client", kept[handle]) : nil
          (!made_here && known) ? t(known) : [:made, handle]
        end

        def self.step(op, verdict, live, kept, ops)
          changes = strings(verdict["changes"])
          unchanged = op["op"] == "update" && changes.empty?
          action = unchanged ? "unchanged" : op["op"]
          return Step.new(op["handle"], op["kind"], action, [], :unchanged) if unchanged

          own_id = op["id"] && Plan.platform_id(live, op["kind"], op["id"])
          pieces = {body: op["body"] || {}, own: own_id ? t(own_id) : [:made, op["handle"]], changes: changes,
                    client: op["client"] ? client_ref(op["client"]["handle"], live, kept, ops) : [:made, ""]}
          argv, records = argv_for(op, verdict, pieces)
          Step.new(op["handle"], op["kind"], action, argv, records)
        end

        def self.client_argv(op, p)
          head = (op["op"] == "create") ? [t("oauth_client"), t("create")] : [t("oauth_client"), t("update"), p[:own]]
          [*head, t("--name"), t(s(p[:body]["name"])), *repeated("--scope", strings(p[:body]["scopes"])),
            *repeated("--redirect-uri", strings(p[:body]["redirect_uris"]))]
        end

        def self.argv_for(op, verdict, p)
          created = (op["op"] == "create") ? :runtime_id : :unchanged
          case op["kind"]
          when "client" then [client_argv(op, p), created]
          when "secret" then secret_argv(op, verdict, p)
          when "webhook" then [(op["op"] == "create") ? webhook_create(p) : webhook_update(p), created]
          else [app_argv(op, p), created]
          end
        end

        def self.secret_argv(op, verdict, p)
          return [[], [:client_secret, op.dig("client", "handle").to_s]] if verdict["note"] == "minted_with_client"

          [[t("oauth_client"), t("secret"), t("create"), p[:client]], :secret_id]
        end

        def self.app_argv(op, p)
          manifest = [:manifest, op["handle"]]
          return [t("apps"), t("update"), p[:own], t("--manifest"), manifest] unless op["op"] == "create"

          [t("apps"), t("create"), t(Spec.app_name(p[:body], op["handle"])), t("--client"), p[:client], t("--manifest"), manifest]
        end

        def self.webhook_create(p)
          [t("webhook"), t("outbound"), t("create"), t("--client"), p[:client], t("--url"), t(s(p[:body]["url"])),
            *repeated("--event", strings(p[:body]["event_types"]))]
        end

        def self.webhook_update(p)
          argv = [t("webhook"), t("outbound"), t("update"), p[:own]]
          argv.push(t("--url"), t(s(p[:body]["url"]))) if p[:changes].include?("url")
          argv.concat(repeated("--event", strings(p[:body]["event_types"]))) if p[:changes].include?("event_types")
          argv.push(t("--enable")) if p[:changes].include?("enabled")
          argv
        end
      end
    end
  end
end
