# frozen_string_literal: true

require "fileutils"
require_relative "../platform/creation"
require_relative "../dev/rows"
require_relative "../dev/project"
require_relative "../dev/template"

module Lingara
  module CLI
    module Commands
      # `apps create` (16.10.26ad D2; 16.10.26ae D1), with **one dispatch
      # point**: with `--manifest` and `--client`, 16.10.26ad's registration
      # path; with neither, 16.10.26ae's scaffold — offline, the project
      # pinned in `platform.toml`, then registered as a private app unless
      # `--no-register` or there is no session. In Thor's sense of the word,
      # a generator (16.10.26af D2).
      module AppsCreate
        PT = Project::PlatformToml
        DEFAULT_DESCRIPTION = "A Lingara app."

        # `--manifest <path>` is the file's JSON as the app body's `manifest`.
        def self.manifest_body(path)
          text = begin
            File.read(path, encoding: "UTF-8")
          rescue SystemCallError => e
            raise Fail.usage("#{path}: #{e.message}")
          end
          {"manifest" => JSON.parse(text)}
        rescue JSON::ParserError => e
          raise Fail.usage("#{path} is not JSON: #{e.message.lines.first.to_s.strip}")
        end

        def self.create(ctx, parsed)
          f = parsed.flags
          manifest, client = Commands.opt(f["manifest"]), Commands.opt(f["client"])
          return register(ctx, parsed.args["name"], client, manifest) if manifest && client
          raise Fail.usage("--manifest and --client go together: both register an app from a file, neither scaffolds a project") if manifest || client

          scaffold(ctx, parsed.args["name"], f)
        end

        # The registration form: register the object, write no files.
        def self.register(ctx, name, client, manifest)
          body = manifest_body(manifest)
          made = Creation.new
          made.run(ctx) { [Objects::APP, Objects.str(made.object(ctx, Objects::APP, name, client, body)["id"])] }
        end

        # The description goes inside a string literal in every template language.
        def self.description_ok?(text) = !text.match?(/["\\`$\#{}]/) && !text.match?(/\p{Cc}/)

        def self.keys(name, f)
          raise Fail.from_message(Exit::USAGE, "app_name_invalid", name: name) unless Project::Schema.app_name_ok?(name)

          description = Commands.opt(f["description"]) || DEFAULT_DESCRIPTION
          raise Fail.usage("--description may not hold \", \\, `, $, #, { or }, or a control character") unless description_ok?(description)

          context = Commands.many(f["context"])
          bad = context.find { |c| !c.match?(/\A[a-z][a-z_]*\z/) }
          raise Fail.usage("--context #{bad} is not a context slice name") if bad

          slots = Commands.many(f["slot"])
          Template::Keys.new(name: name, display_name: DevProject.display_name(name), description: description,
            port: f["port"] || PT::DEFAULT_PORT, slots: slots.empty? ? ["home.side"] : slots, context: context)
        end

        # `dir` relative to `base`, `/`-separated; `.` when they are one.
        def self.relative_to(dir, base)
          return "." if dir == base
          return dir.delete_prefix("#{base}/") if dir.start_with?("#{base}/")

          dir
        end

        # Step 3's file: `--config` or `LINGARA_PLATFORM_TOML`, else the
        # nearest above the project's parent, else a new one inside the project.
        def self.pin_file(ctx, name, config, dir, cwd)
          explicit = (config || ctx.env["LINGARA_PLATFORM_TOML"]) ? Rows.located(ctx, config, cwd) : nil
          found = explicit || PT.find_upward(File.dirname(dir))
          return File.join(dir, Project::Write::FILE) if found.nil? || !File.file?(found)
          raise Fail.usage("#{PT.shown(found, cwd)} already pins #{name}") if Rows.load_at(ctx, found, cwd).apps.any? { |r| r.name == name }

          found
        end

        def self.scaffold(ctx, name, f)
          keys = keys(name, f)
          cwd = Rows.cwd
          dir_flag = Commands.opt(f["dir"])
          wanted = dir_flag ? File.expand_path(dir_flag, cwd) : File.join(cwd, name)
          if File.directory?(wanted) && !Dir.empty?(wanted)
            raise Fail.from_message(Exit::USAGE, "dir_not_empty", path: PT.shown(wanted, cwd))
          end

          files, meta = Template.render(Commands.opt(f["lang"]) || "typescript", f["bare"] ? "bare" : "default", keys)
          file = pin_file(ctx, name, Commands.opt(f["config"]), wanted, cwd)
          write_project(wanted, files, file, keys, meta)
          register_it(ctx, name, f["no-register"] == true, File.join(Rows.real(File.dirname(file)), File.basename(file)), cwd)
        end

        def self.write_project(wanted, files, file, keys, meta)
          FileUtils.mkdir_p(wanted)
          dir = Rows.real(wanted)
          Template.write_all(dir, files)
          base = Rows.real(File.dirname(file))
          row = Project::Write::NewRow.new(name: keys.name, path: relative_to(dir, base), port: keys.port, start: meta.start, manifest: meta.manifest)
          Project::Write.pin_row(File.join(base, File.basename(file)), row, nil)
          Project::Write.ensure_gitignore(base)
        end

        # Step 2, last: offline when `--no-register`, or with no session to use.
        def self.register_it(ctx, name, no_register, file, cwd)
          return ctx.out.nothing if no_register

          if ctx.ci_pair.nil? && ctx.app.stores.load(ctx.profile).nil?
            ctx.out.note(Assets.message("apps_create_login_hint", name: name))
            return ctx.out.nothing
          end
          toml = Rows.load_at(ctx, file, cwd)
          row = Rows.resolve(toml, name, cwd)
          manifest = DevProject.read(toml, row, cwd)
          DevProject.warn(ctx, manifest)
          DevProject.register(ctx, toml, row, manifest)
        end
      end
    end
  end
end
