# frozen_string_literal: true

require "open3"
require_relative "manifest"
require_relative "../literal/connection"
require_relative "../platform/creation"
require_relative "../project/platform_toml"

module Lingara
  module CLI
    # A project's manifest and its registration (16.10.26ae D1 step 2, D6
    # steps 1, 4 and 5): read from `lingara/connection.js` by the literal
    # reader, or from `manifest_command`'s stdout; checked; its warnings
    # printed; and, for a row with no id, registered as a private app.
    module DevProject
      PT = Project::PlatformToml

      def self.file_of(toml, row) = File.join(PT.project_dir(toml, row), row.manifest || "lingara/connection.js")

      # The manifest's source as messages name it, relative to `cwd`.
      def self.source(toml, row, cwd)
        return row.manifest_command if row.manifest_command

        PT.shown(file_of(toml, row), cwd)
      end

      # The platform shell's argv for one command line.
      def self.shell_args(line) = Env.windows? ? ["cmd", "/C", line] : ["sh", "-c", line]

      # D3: `manifest_command` through the platform shell, in the project;
      # its stdout is the wire manifest, its stderr the developer's.
      def self.run_command(command, dir)
        out, status = Open3.capture2(*shell_args(command), chdir: dir, in: File::NULL)
        raise Fail.failure("#{command} exited #{status.exitstatus || "on a signal"}; fix it, then run lingara apps update") unless status.success?

        JSON.parse(out)
      rescue JSON::ParserError => e
        raise Fail.usage("#{command} printed no manifest JSON: #{e.message}")
      end

      def self.from_connection(toml, row, name)
        text = begin
          File.read(file_of(toml, row), encoding: "UTF-8")
        rescue SystemCallError => e
          raise Fail.usage("#{name}: #{e.message}")
        end
        Literal.app_manifest(text)
      rescue Literal::Refusal => e
        raise Literal.refusal_fail(e, name)
      end

      # The wire manifest the project describes, checked before any request.
      def self.read(toml, row, cwd)
        name = source(toml, row, cwd)
        manifest = row.manifest_command ? run_command(row.manifest_command, PT.project_dir(toml, row)) : from_connection(toml, row, name)
        rule = manifest.is_a?(Hash) ? Manifest.broken_rule(manifest) : "manifest_version"
        raise Fail.new(Exit::USAGE, "manifest_invalid", "#{name}: the manifest breaks the #{rule} rule") if rule

        manifest
      end

      # D6 step 5: one `manifest_warning` line each, never a failure.
      def self.warn(ctx, manifest)
        codes = Manifest.warnings(manifest)
        codes.each { |code| ctx.out.note(Assets.message("manifest_warning", code: code, text: Manifest.warning_text(code))) }
        codes
      end

      # The display name D2 derives: each `-` a space, each word capitalised.
      def self.display_name(name) = name.split("-", -1).map { |w| w.empty? ? "" : w[0].upcase + w[1..] }.join(" ")

      # D1 step 2 / D6 step 4: the private OAuth client, then the app under
      # it through the registration form's own steps, the id written into
      # the row. Prints the app as the registration form does.
      def self.register(ctx, toml, row, manifest)
        title = display_name(row.name)
        client_body = {"client_type" => "confidential", "allowed_scopes" => [], "billing_mode" => "allowance", "redirect_uris" => []}
        made = Creation.new
        app = made.run(ctx) do
          client = made.object(ctx, Objects::OAUTH_CLIENT, title, nil, client_body)
          created = made.object(ctx, Objects::APP, title, Objects.str(client["id"]), {"manifest" => manifest})
          [Objects::APP, Objects.str(created["id"])]
        end
        Project::Write.set_id(toml.file, row.name, Objects.str(app["id"]))
        app
      end
    end
  end
end
