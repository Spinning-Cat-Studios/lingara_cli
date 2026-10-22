# frozen_string_literal: true

require_relative "../platform/deletes"

module Lingara
  module CLI
    module Commands
      # The `apps` verbs over the platform objects alone (16.10.26ad D1):
      # list, get, delete, submit and the version lifecycle. `apps list
      # --pinned` reads platform.toml instead (16.10.26ae D4).
      module Apps
        K = Objects::APP
        COLUMNS = [%w[ID id], %w[NAME name], %w[CLIENT parent_id], %w[SERVING serving_version], %w[VISIBILITY visibility]].freeze
        VERSION_COLUMNS = [%w[SEMVER semver], %w[STATE state], %w[STATE_AT state_at], %w[REVIEWED review_basis]].freeze

        def self.list(ctx, parsed)
          if parsed.flags["pinned"]
            require_relative "apps_local"
            return AppsLocal.list_pinned(ctx, Commands.opt(parsed.flags["config"]))
          end
          Objects.print_list(ctx, K, COLUMNS)
        end

        def self.get(ctx, parsed) = Objects.print_get(ctx, K, parsed.args["id"])

        def self.delete(ctx, parsed)
          Deletes.delete_object(ctx, K, parsed) { |o| Assets.message("consequence_app", client: Deletes.parent(o)) }
        end

        # `{"visibility": "public"}`; review itself is the operator's, in the portal.
        def self.submit(ctx, parsed)
          vars = {"id" => parsed.args["id"], "semver" => parsed.flags["version"]}
          request = Http::Request.new(route: Routes.object(K.word, "submit"), vars: vars, body: {json: {"visibility" => "public"}})
          ctx.out.value(ctx.call(request))
        end

        # Every page, newest first; `next_cursor` is the last semver seen.
        def self.versions_list(ctx, parsed)
          route = Routes.object(K.word, "versions")
          items = Objects.pages do |cursor|
            ctx.call(Http::Request.new(route: route, vars: {"id" => parsed.args["id"]}, query: cursor ? [["cursor", cursor]] : []))
          end
          ctx.out.list(items, VERSION_COLUMNS)
        end

        def self.versions_publish(ctx, parsed) = version(ctx, parsed, "publish")
        def self.versions_withdraw(ctx, parsed) = version(ctx, parsed, "withdraw")

        def self.version(ctx, parsed, op)
          ctx.out.value(Objects.version_op(ctx, K, op, parsed.args["id"], parsed.args["semver"]))
        end
      end
    end
  end
end
