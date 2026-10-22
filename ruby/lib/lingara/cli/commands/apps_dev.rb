# frozen_string_literal: true

require_relative "apps_create"
require_relative "../dev/session"

module Lingara
  module CLI
    module Commands
      # `lingara apps dev [name]` (16.10.26ae D7), steps 1 and 2: resolve the
      # row and run every refusal offline, so a refused run sends no
      # request; then register a row with no id (D6 step 4) and hand over to
      # `DevSession`. `--reset` is here too: one `PATCH {render_url: null}`
      # and nothing else. On Windows the session is unsupported in this
      # release (16.10.26af D2): Ruby cannot give the app a process group
      # there, so it exits `failure` with `apps_dev_unsupported_platform`.
      module AppsDev
        PT = Project::PlatformToml

        def self.dev(ctx, parsed)
          f = parsed.flags
          toml, cwd = Rows.open(ctx, Commands.opt(f["config"]))
          row = Rows.resolve(toml, Commands.opt(parsed.args["name"]), cwd)
          return reset(ctx, row) if f["reset"]
          raise Fail.from_message(Exit::FAILURE, "apps_dev_unsupported_platform", platform: "Windows") if Env.windows?

          plan = preflight(ctx, f, toml, row, cwd)
          plan.id = row.id || register(ctx, toml, row, cwd)
          DevSession.session(ctx, plan)
        end

        def self.register(ctx, toml, row, cwd)
          app = DevProject.register(ctx, toml, row, DevProject.read(toml, row, cwd))
          # The registration printed the app; `apps dev` prints one value, at its exit.
          ctx.out.stdout = +""
          Objects.str(app["id"])
        end

        def self.ignored_or_refuse(path, cwd)
          return if Project::Git.ignored?(path)

          raise Fail.from_message(Exit::USAGE, "path_not_ignored", path: PT.shown(path, cwd))
        end

        # `KEY=VALUE` lines; blank lines, `#` comments and an `export `
        # prefix are skipped.
        def self.env_file(path)
          File.read(path, encoding: "UTF-8").split("\n").filter_map do |raw|
            line = raw.strip.delete_prefix("export ")
            eq = line.index("=")
            next if line.start_with?("#") || eq.nil? || eq.zero?

            value = line[(eq + 1)..].strip
            [line[0...eq].strip, value[/\A(["'])(.*)\1\z/, 2] || value]
          end
        rescue SystemCallError => e
          raise Fail.usage("#{path}: #{e.message}")
        end

        def self.target(ctx, tunnel, render_url)
          return [:fixed, render_url] if tunnel == "none" && render_url
          raise Fail.usage("--tunnel none needs --render-url") if tunnel == "none"
          raise Fail.usage("--render-url needs --tunnel none") if render_url

          require_relative "../supervise/lookup"
          [:cloudflared, Cloudflared.find(ctx.env)]
        end

        # Step 2: every refusal before the network. The session record is
        # read, never refreshed; a refresh, if due, rides the first request.
        def self.preflight(ctx, f, toml, row, cwd)
          raise Fail.usage("#{row.name}'s row has no start command") if row.start.nil?

          dir = PT.project_dir(toml, row)
          env = row_env(row, dir, cwd)
          write_env = Commands.opt(f["write-env"]) && File.expand_path(f["write-env"], cwd)
          ignored_or_refuse(write_env, cwd) if write_env
          to = target(ctx, Commands.opt(f["tunnel"]), Commands.opt(f["render-url"]))
          signed_in!(ctx)
          DevSession::Plan.new(name: row.name, id: "", dir: dir, port: f["port"] || row.port, start: row.start,
            render_path: row.render_path || "/", target: to, take_over: f["take-over"] == true,
            client: row.credentials.include?("client_credentials"), env: env, write_env: write_env,
            show_env: f["show-env"] == true, probe: ctx.env["LINGARA_TUNNEL_PROBE_URL"])
        end

        # A session to sign in with, read and never refreshed here: a
        # refresh, if due, rides the first request.
        def self.signed_in!(ctx)
          return if ctx.ci_pair || ctx.app.stores.load(ctx.profile)

          raise Fail.auth_required("not signed in: run `lingara login`")
        end

        # The row's `env`, then its `env_file`, which git must ignore.
        def self.row_env(row, dir, cwd)
          env = row.env.to_a
          return env unless row.env_file

          path = File.join(dir, row.env_file)
          ignored_or_refuse(path, cwd)
          env + env_file(path)
        end

        # `--reset`: the owner's install back on the serving version. A live
        # session refuses it (`apps_dev_reset_held`).
        def self.reset(ctx, row)
          raise Fail.usage("#{row.name} is not registered yet: run lingara apps update #{row.name} first") if row.id.nil?

          begin
            DevApi.patch_with_retry(ctx, row.id) { |draft| {"render_url" => nil, "draft_version" => draft} }
          rescue Fail => e
            raise e unless e.exit_code == Exit::DEV_SESSION_HELD

            raise Fail.from_message(Exit::DEV_SESSION_HELD, "apps_dev_reset_held", name: row.name)
          end
          ctx.out.note(Assets.message("apps_dev_reset", name: row.name))
          ctx.out.nothing
        end
      end
    end
  end
end
