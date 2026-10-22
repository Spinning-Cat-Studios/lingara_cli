# frozen_string_literal: true

require_relative "../platform/creation"
require_relative "../platform/deletes"

module Lingara
  module CLI
    module Commands
      # `embed …` (16.10.26ad D1, D2): the metered, confidential client first
      # (its body never names `embed:mint`, the embed's projection adds it),
      # its secret, then the embed.
      module Embed
        K = Objects::EMBED
        COLUMNS = [%w[ID id], %w[NAME name], %w[CLIENT parent_id], %w[ORIGINS origins], %w[SERVING serving_version]].freeze

        def self.create(ctx, parsed)
          f = parsed.flags
          made = Creation.new
          made.run(ctx) do
            body = {"client_type" => "confidential", "billing_mode" => "metered", "allowed_scopes" => Commands.many(f["scope"])}
            client = Objects.str(made.object(ctx, Objects::OAUTH_CLIENT, f["name"], nil, body)["id"])
            made.mint(ctx, Objects::OAUTH_CLIENT, client)
            [K, Objects.str(made.object(ctx, K, f["name"], client, {"origins" => Commands.many(f["origin"])})["id"])]
          end
        end

        def self.list(ctx, _parsed) = Objects.print_list(ctx, K, COLUMNS)
        def self.get(ctx, parsed) = Objects.print_get(ctx, K, parsed.args["id"])

        # `--name` renames; `--origin` replaces the origins in a body update.
        def self.update(ctx, parsed)
          id = parsed.args["id"]
          origins = Commands.many(parsed.flags["origin"])
          last = Commands.opt(parsed.flags["name"]) && Objects.rename(ctx, K, id, parsed.flags["name"])
          last = Objects.body_update(ctx, K, id) { |body| body["origins"] = origins } unless origins.empty?
          raise Fail.usage("nothing to update: pass --name or --origin") unless last

          ctx.out.value(last)
        end

        def self.delete(ctx, parsed)
          Deletes.delete_object(ctx, K, parsed) { |o| Assets.message("consequence_embed", client: Deletes.parent(o)) }
        end
      end
    end
  end
end
