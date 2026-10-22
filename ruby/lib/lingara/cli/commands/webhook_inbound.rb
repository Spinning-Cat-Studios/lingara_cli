# frozen_string_literal: true

require_relative "../platform/creation"
require_relative "../platform/deletes"

module Lingara
  module CLI
    module Commands
      # `webhook inbound …` (16.10.26ad D1, D2): a confidential client (its
      # body never names `events:write`), its secret, then the `webhook_in`
      # object. No `--event` omits `event_types`, which 16.10.26v §5 projects
      # as every inbound type.
      module WebhookInbound
        K = Objects::WEBHOOK_INBOUND
        COLUMNS = [%w[ID id], %w[NAME name], %w[CLIENT parent_id], %w[EVENTS event_types]].freeze

        def self.create(ctx, parsed)
          name = parsed.flags["name"]
          events = Commands.many(parsed.flags["event"])
          made = Creation.new
          hook = made.run(ctx) do
            client_body = {"client_type" => "confidential", "allowed_scopes" => []}
            client = Objects.str(made.object(ctx, Objects::OAUTH_CLIENT, name, nil, client_body)["id"])
            made.mint(ctx, Objects::OAUTH_CLIENT, client)
            body = events.empty? ? {} : {"event_types" => events}
            [K, Objects.str(made.object(ctx, K, name, client, body)["id"])]
          end
          ingest = "#{ctx.http.bases[:api].sub(%r{/+\z}, "")}/v1/events"
          client_id = Objects.str(hook["client_id"])
          ctx.out.note("Senders POST to #{ingest} as client #{client_id.empty? ? "-" : client_id}, with its secret and an Idempotency-Key header.")
        end

        def self.list(ctx, _parsed) = Objects.print_list(ctx, K, COLUMNS)
        def self.get(ctx, parsed) = Objects.print_get(ctx, K, parsed.args["id"])

        # `--name` renames; `--event` replaces the event types in a body update.
        def self.update(ctx, parsed)
          id = parsed.args["id"]
          events = Commands.many(parsed.flags["event"])
          last = Commands.opt(parsed.flags["name"]) && Objects.rename(ctx, K, id, parsed.flags["name"])
          last = Objects.body_update(ctx, K, id) { |body| body["event_types"] = events } unless events.empty?
          raise Fail.usage("nothing to update: pass --name or --event") unless last

          ctx.out.value(last)
        end

        def self.delete(ctx, parsed)
          Deletes.delete_object(ctx, K, parsed) { |o| Assets.message("consequence_webhook_inbound", client: Deletes.parent(o)) }
        end
      end
    end
  end
end
