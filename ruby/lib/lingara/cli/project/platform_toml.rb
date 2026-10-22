# frozen_string_literal: true

require_relative "schema"
require_relative "secrets"
require_relative "write"
require_relative "git"

module Lingara
  module CLI
    module Project
      # `platform.toml` (16.10.26ae D4): the gitignored, per-developer file
      # naming the apps a checkout works on. Found by `--config`, then
      # `LINGARA_PLATFORM_TOML`, then by walking up from the current
      # directory as Cargo does. Read in a fixed order, so the first refusal
      # is the same in every kit: TOML syntax (`platform_toml_schema`), a
      # secret-shaped value or key (`platform_toml_secret`), then the schema
      # (`platform_toml_schema`). TOML is tomlrb's (16.10.26af D2).
      module PlatformToml
        DEFAULT_PORT = 8787

        # One `[[apps]]` row; `id` is nil for the example's placeholder.
        Row = Struct.new(:name, :id, :path, :port, :start, :render_path, :manifest, :manifest_command,
          :credentials, :env_file, :env)
        Toml = Struct.new(:file, :api, :profile, :apps)

        def self.text(value) = value.is_a?(String) ? value : nil

        # The directory every row's `path` is relative to.
        def self.project_dir(toml, row) = File.expand_path(row.path, File.dirname(toml.file))

        # The file's name as messages print it: relative to `cwd` where it can be.
        def self.shown(path, cwd) = path.start_with?("#{cwd}/") ? path.delete_prefix("#{cwd}/") : path

        def self.file?(path) = File.file?(path)

        # The nearest `platform.toml` in `from` or a directory above it.
        def self.find_upward(from)
          dir = from
          loop do
            candidate = File.join(dir, Write::FILE)
            return candidate if file?(candidate)
            return nil if File.dirname(dir) == dir

            dir = File.dirname(dir)
          end
        end

        # `--config`, then `LINGARA_PLATFORM_TOML`, then the nearest
        # `platform.toml` at or above `cwd`.
        def self.locate(config, env, cwd)
          explicit = config || env["LINGARA_PLATFORM_TOML"]
          return find_upward(cwd) if explicit.nil?

          File.absolute_path?(explicit) ? explicit : File.join(cwd, explicit)
        end

        def self.schema_fail(file, reason) = Fail.from_message(Exit::PLATFORM_TOML_SCHEMA, "platform_toml_schema", file: file, reason: reason)

        def self.row(v)
          id = text(v["id"])
          Row.new(name: text(v["name"]) || "", id: (id == Write::PLACEHOLDER_ID) ? nil : id, path: text(v["path"]) || ".",
            port: v["port"].is_a?(Integer) ? v["port"] : DEFAULT_PORT, start: text(v["start"]),
            render_path: text(v["render_path"]), manifest: text(v["manifest"]), manifest_command: text(v["manifest_command"]),
            credentials: Array(v["credentials"]).grep(String), env_file: text(v["env_file"]),
            env: v["env"].is_a?(Hash) ? v["env"] : {})
        end

        # Reads and checks `path`; `name` is how messages name it.
        def self.load(path, name)
          value = parse(File.read(path, encoding: "UTF-8"), name)
          key = Secrets.first(value)
          raise Fail.from_message(Exit::PLATFORM_TOML_SECRET, "platform_toml_secret", file: name, key: key) if key

          reason = Schema.check(value)
          raise schema_fail(name, reason) if reason

          platform = value["platform"].is_a?(Hash) ? value["platform"] : {}
          Toml.new(path, text(platform["api"]), text(platform["profile"]), Array(value["apps"]).map { |r| row(r) })
        end

        def self.parse(source, name)
          require "tomlrb"
          Tomlrb.parse(source)
        rescue Tomlrb::ParseError, ArgumentError => e
          raise schema_fail(name, e.message.lines.first.to_s.strip)
        end
      end
    end
  end
end
