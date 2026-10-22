# frozen_string_literal: true

require_relative "../platform/creation"
require_relative "../platform/deletes"

module Lingara
  module CLI
    module Commands
      # `oauth_client …` (16.10.26ad D1, D2, D5). `--ci` is confidential with
      # the four scopes; whether the client may be one is the server's
      # predicate. `--name` on `update` is a rename; any other flag is a body
      # update over the one editable version.
      module OauthClient
        K = Objects::OAUTH_CLIENT
        COLUMNS = [%w[ID id], %w[NAME name], %w[TYPE client_type], %w[SCOPES allowed_scopes], %w[CREATED created_at]].freeze

        def self.create(ctx, parsed)
          f = parsed.flags
          ci = f["ci"] == true
          type = Commands.opt(f["type"]) || "confidential"
          body = {"client_type" => ci ? "confidential" : type,
                  "allowed_scopes" => ci ? Auth::Tokens::SCOPES.split(" ") : Commands.many(f["scope"]),
                  "billing_mode" => Commands.opt(f["billing-mode"]) || "allowance",
                  "redirect_uris" => Commands.many(f["redirect-uri"])}
          made = Creation.new
          made.run(ctx) do
            client = made.object(ctx, K, f["name"], nil, body)
            made.mint(ctx, K, Objects.str(client["id"])) if ci || type == "confidential"
            [K, Objects.str(client["id"])]
          end
        end

        def self.list(ctx, _parsed) = Objects.print_list(ctx, K, COLUMNS)
        def self.get(ctx, parsed) = Objects.print_get(ctx, K, parsed.args["id"])

        def self.update(ctx, parsed)
          id = parsed.args["id"]
          f = parsed.flags
          scope, uris, api = Commands.many(f["scope"]), Commands.many(f["redirect-uri"]), Commands.opt(f["api-version"])
          last = Commands.opt(f["name"]) && Objects.rename(ctx, K, id, f["name"])
          if !scope.empty? || !uris.empty? || api
            last = Objects.body_update(ctx, K, id) do |body|
              body["allowed_scopes"] = scope unless scope.empty?
              body["api_version"] = api if api
              body["redirect_uris"] = uris unless uris.empty?
            end
          end
          raise Fail.usage("nothing to update: pass --name, --scope, --api-version or --redirect-uri") unless last

          ctx.out.value(last)
        end

        # The children 16.10.26v §7's cascade deletes, counted by kind from the one read.
        def self.delete(ctx, parsed)
          Deletes.delete_object(ctx, K, parsed) do |obj|
            children = Array(obj["children"])
            count = ->(kinds) { children.count { |c| kinds.include?(c["kind"].to_s) }.to_s }
            Assets.message("consequence_oauth_client", apps: count.call(%w[app]), embeds: count.call(%w[embed]),
              webhooks: count.call(%w[webhook_in webhook_out]))
          end
        end

        # Rotation's first call; the old secret stays live.
        def self.secret_create(ctx, parsed)
          ctx.out.value(Objects.secret_create(ctx, K, parsed.args["client_id"]))
          ctx.out.note(Creation::SHOWN_ONCE)
        end

        # Rotation's second call. A secret has no read of its own, so the
        # prompt names the client's.
        def self.secret_delete(ctx, parsed)
          client_id, secret_id = parsed.args.values_at("client_id", "secret_id")
          f = parsed.flags
          email = Confirm.destructive(ctx, yes: f["yes"] == true, confirm: Commands.opt(f["confirm"])) do
            prompt = Deletes.prompt_for(K, Objects.get(ctx, K, client_id), secret_id, nil, ->(_) { Assets.message("consequence_secret") })
            prompt.kind = "secret"
            prompt.pinned_by = 0
            prompt
          end
          route = Routes.object(K.word, "secret_delete")
          ctx.call(Http::Request.new(route: route, vars: {"id" => client_id, "secret_id" => secret_id}, body: {json: {"confirm" => email}}))
          ctx.out.nothing
        end
      end
    end
  end
end
