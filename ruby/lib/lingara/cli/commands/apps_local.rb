# frozen_string_literal: true

require_relative "../dev/rows"
require_relative "../dev/dev_api"
require_relative "../platform/objects"

module Lingara
  module CLI
    module Commands
      # The local verbs (16.10.26ae D4, D9): `apps status` reads the dev
      # version; `apps pin`, `apps unpin` and `apps list --pinned` edit or
      # read `platform.toml` alone (pin's one read is `app.get` for a name).
      module AppsLocal
        PT = Project::PlatformToml
        UUID = /\A\h{8}-\h{4}-\h{4}-\h{4}-\h{12}\z/

        # D9's three lines. The lease time is `HH:MM` of the server's UTC stamp.
        def self.status_line(view)
          url = view["render_url"]
          return "dev: serving version" unless url.is_a?(String)

          session = view["session"].is_a?(Hash) ? view["session"] : {}
          return "dev: parked (last #{url})" unless session["state"] == "live"

          lease = session["lease_expires_at"].is_a?(String) ? session["lease_expires_at"][11, 5] : "?"
          "dev: live #{url} (lease until #{lease} UTC)"
        end

        # Live with its lease, parked with its last URL, or the serving
        # version; `--json` is the `GET …/dev` body as the server answered.
        def self.status(ctx, parsed)
          toml, cwd = Rows.open(ctx, Commands.opt(parsed.flags["config"]))
          row = Rows.resolve(toml, Commands.opt(parsed.args["name"]), cwd)
          raise Fail.usage("#{row.name} is not registered yet: run lingara apps update #{row.name}") if row.id.nil?

          view = DevApi.get(ctx, row.id)
          ctx.out.json ? ctx.out.json_value(view) : ctx.out.print("#{status_line(view)}\n")
        end

        # A platform object's name as a local handle: `Luba Dive Log` → `luba-dive-log`.
        def self.handle_of(name) = name.gsub(/[^A-Za-z0-9]/, "-").downcase.split("-").reject(&:empty?).join("-")

        def self.relative_path(project, base)
          return "." if project == base
          return project.delete_prefix("#{base}/") if project.start_with?("#{base}/")

          project
        end

        # `apps pin <id|name> [--path <dir>]`: a row in platform.toml and,
        # when it has none of that name, in the example. Never a platform write.
        def self.pin(ctx, parsed)
          app = parsed.args["app"]
          path = Commands.opt(parsed.flags["path"])
          cwd = Rows.cwd
          name, id = pin_name(ctx, app, path)
          raise Fail.from_message(Exit::USAGE, "app_name_invalid", name: name) unless Project::Schema.app_name_ok?(name)

          found = Rows.located(ctx, Commands.opt(parsed.flags["config"]), cwd)
          file = found || File.join(cwd, Project::Write::FILE)
          names = File.exist?(file) ? Rows.load_at(ctx, file, cwd).apps.map(&:name) : []
          raise Fail.usage("#{PT.shown(file, cwd)} already pins #{name}") if names.include?(name)

          project = Rows.real(File.expand_path(path || ".", cwd))
          row = Project::Write::NewRow.new(name: name, path: relative_path(project, Rows.real(File.dirname(file))), port: PT::DEFAULT_PORT)
          Project::Write.pin_row(file, row, id)
          ctx.out.nothing
        end

        def self.pin_name(ctx, app, path)
          return [handle_of(Objects.str(Objects.get(ctx, Objects::APP, app)["name"])), app] if app.match?(UUID)
          raise Fail.usage("pinning #{app} by name needs --path <dir>") if path.nil?

          [app, nil]
        end

        # `apps unpin <name>`: platform.toml only; the shared example changes by review.
        def self.unpin(ctx, parsed)
          toml, cwd = Rows.open(ctx, Commands.opt(parsed.flags["config"]))
          name = parsed.args["name"]
          unless Project::Write.remove_row(toml.file, name)
            raise Fail.from_message(Exit::USAGE, "app_row_unknown", file: PT.shown(toml.file, cwd), value: name)
          end

          ctx.out.nothing
        end

        # `apps list --pinned`: the rows, as JSON or as a NAME/ID/PATH/PORT table.
        def self.list_pinned(ctx, config)
          toml, = Rows.open(ctx, config)
          rows = toml.apps.map { |r| {"name" => r.name, "id" => r.id, "path" => r.path, "port" => r.port} }
          return ctx.out.json_value(rows) if ctx.out.json

          ctx.out.print(Output.render(%w[NAME ID PATH PORT], rows.map { |r| [r["name"], Output.cell(r["id"]), r["path"], r["port"].to_s] }))
        end
      end
    end
  end
end
