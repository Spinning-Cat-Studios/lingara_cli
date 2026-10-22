# frozen_string_literal: true

require_relative "../platform/creation"
require_relative "../platform/deletes"

module Lingara
  module CLI
    module Commands
      # `webhook outbound …` (16.10.26ad D1, D2, D5). `--url`/`--event` are a
      # body update that keeps the endpoint and its signing secret;
      # `--enable`/`--disable` follow it on the object-level route.
      module WebhookOutbound
        K = Objects::WEBHOOK_OUTBOUND
        COLUMNS = [%w[ID id], %w[CLIENT parent_id], %w[URL url], %w[EVENTS event_types], %w[ENABLED enabled]].freeze

        # Create, publish, mint the signing secret (revealed once), read. The
        # verb takes no name, so the object is named by its URL, cut to the
        # 80 characters a platform object name may hold.
        def self.create_outbound(ctx, client, url, events)
          made = Creation.new
          made.run(ctx) do
            hook = made.object(ctx, K, url[0, 80], client, {"url" => url, "event_types" => events})
            made.mint(ctx, K, Objects.str(hook["id"]))
            [K, Objects.str(hook["id"])]
          end
        end

        def self.create(ctx, parsed)
          f = parsed.flags
          create_outbound(ctx, f["client"], f["url"], Commands.many(f["event"]))
        end

        def self.list(ctx, _parsed) = Objects.print_list(ctx, K, COLUMNS)
        def self.get(ctx, parsed) = Objects.print_get(ctx, K, parsed.args["id"])

        def self.action(ctx, op, id) = ctx.call(Http::Request.new(route: Routes.object(K.word, op), vars: {"id" => id}))

        def self.test(ctx, parsed) = ctx.out.value(action(ctx, "test", parsed.args["id"]))

        def self.update(ctx, parsed)
          id = parsed.args["id"]
          f = parsed.flags
          url = Commands.opt(f["url"])
          events = Commands.many(f["event"])
          last = nil
          if url || !events.empty?
            last = Objects.body_update(ctx, K, id) do |body|
              body["url"] = url if url
              body["event_types"] = events unless events.empty?
            end
          end
          last = action(ctx, f["enable"] ? "enable" : "disable", id) if f["enable"] || f["disable"]
          raise Fail.usage("nothing to update: pass --url, --event, --enable or --disable") unless last

          ctx.out.value(last)
        end

        def self.delete(ctx, parsed)
          Deletes.delete_object(ctx, K, parsed) { Assets.message("consequence_webhook_outbound") }
        end

        # The old object and its serving version's body: where the new
        # endpoint's client and any omitted flag come from.
        def self.lookups(ctx, id)
          old = Objects.get(ctx, K, id)
          semver = Objects.str(old["serving_version"])
          semver = Objects.str(Array(old["versions"]).first&.dig("semver")) if semver.empty?
          version = Objects.version_op(ctx, K, "version_get", id, semver)
          [old, version["body"].is_a?(Hash) ? version["body"] : {}]
        end

        # A new endpoint with a new signing secret, then the old one deleted.
        # Both prompts, or the `--confirm` check, complete **before** the new
        # endpoint's first write. If the create lands and the delete is
        # refused, the new secret is still revealed and both ids named.
        def self.replace(ctx, parsed)
          id = parsed.args["id"]
          f = parsed.flags
          looked = nil
          email = Confirm.destructive(ctx, yes: f["yes"] == true, confirm: Commands.opt(f["confirm"])) do
            looked = lookups(ctx, id)
            Deletes.prompt_for(K, looked[0], id, nil, ->(_) { Assets.message("consequence_replace") })
          end
          old, body = looked || lookups(ctx, id)
          events = Commands.many(f["event"])
          events = Commands.many(body["event_types"]) if events.empty?
          created = create_outbound(ctx, Objects.str(old["parent_id"]), Commands.opt(f["url"]) || Objects.str(body["url"]), events)
          destroy_old(ctx, id, email, created)
        end

        def self.destroy_old(ctx, id, email, created)
          Objects.destroy(ctx, K, id, nil, email)
        rescue Fail => e
          e.made = [["webhook_outbound", Objects.str(created["id"])]]
          e.hint("the old endpoint #{id} still delivers; delete it with `lingara webhook outbound delete #{id}`")
          raise
        end
      end
    end
  end
end
