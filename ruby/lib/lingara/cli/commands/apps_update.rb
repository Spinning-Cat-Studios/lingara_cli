# frozen_string_literal: true

require_relative "apps_create"

module Lingara
  module CLI
    module Commands
      # `apps update` (16.10.26ad D2; 16.10.26ae D6). One dispatch point on
      # `--manifest`: with it, `apps update <id> --manifest <path>` makes a
      # new version from the file; without it, the project's manifest goes
      # to the **dev version** (`PATCH …/dev`, never its `render_url`), or
      # with `--version` becomes an immutable draft, and a row with no id is
      # registered first. A quick-tunnel or `dev.invalid` render URL bound
      # for a version is refused before any request (D6 step 3, D10 row 15).
      module AppsUpdate
        def self.update(ctx, parsed)
          manifest = Commands.opt(parsed.flags["manifest"])
          return from_file(ctx, parsed.args["id"], manifest) if manifest

          project_update(ctx, parsed.args["id"], Commands.opt(parsed.flags["version"]), Commands.opt(parsed.flags["config"]))
        end

        def self.from_file(ctx, id, path)
          raise Fail.usage("apps update --manifest needs the app's id") if id.nil?

          manifest = AppsCreate.manifest_body(path)["manifest"]
          render_url = manifest.is_a?(Hash) ? manifest["render_url"] : nil
          raise Fail.from_message(Exit::RENDER_URL_EPHEMERAL, "render_url_ephemeral", file: path) if Manifest.ephemeral?(render_url.to_s)

          ctx.out.value(Objects.body_update(ctx, Objects::APP, id) { |body| body["manifest"] = manifest })
        end

        def self.project_update(ctx, wanted, version, config)
          toml, cwd = Rows.open(ctx, config)
          row = Rows.resolve(toml, wanted, cwd)
          manifest = DevProject.read(toml, row, cwd)
          if version && Manifest.ephemeral?(manifest["render_url"].to_s)
            raise Fail.from_message(Exit::RENDER_URL_EPHEMERAL, "render_url_ephemeral", file: DevProject.source(toml, row, cwd))
          end

          codes = DevProject.warn(ctx, manifest)
          return DevProject.register(ctx, toml, row, manifest) if row.id.nil?

          answer = version ? cut(ctx, row.id, version, manifest) : patch(ctx, row.id, manifest)
          # The server's answer, with `warnings: [code]` under `--json`.
          ctx.out.value(ctx.out.json ? answer.merge("warnings" => codes) : answer)
        end

        # Step 3: an immutable draft from the project's manifest; the
        # server's lifecycle rules stand, as `refused`.
        def self.cut(ctx, id, semver, manifest)
          route = Routes.object("app", "version_create")
          ctx.call(Http::Request.new(route: route, vars: {"id" => id}, body: {json: {"semver" => semver, "body" => {"manifest" => manifest}}}))
        end

        def self.patch(ctx, id, manifest)
          require_relative "../dev/dev_api"
          DevApi.patch_with_retry(ctx, id) { |draft| {"manifest" => manifest, "draft_version" => draft} }
        end
      end
    end
  end
end
