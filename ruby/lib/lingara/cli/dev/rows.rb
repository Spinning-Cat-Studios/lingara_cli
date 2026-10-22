# frozen_string_literal: true

require_relative "../project/platform_toml"

module Lingara
  module CLI
    # Opening `platform.toml` for a command and picking its row (16.10.26ae
    # D4, D7 step 1). Opening runs every refusal before any request, warns
    # once when git tracks the file, and applies its `[platform]` table:
    # `api` unless `LINGARA_API_URL` is set, `profile` unless `--profile` or
    # `LINGARA_PROFILE` chose one.
    module Rows
      PT = Project::PlatformToml

      def self.real(path)
        File.realpath(path)
      rescue SystemCallError
        path
      end

      # The process's directory, through any symlink, so a row's path compares.
      def self.cwd = real(Dir.pwd)

      # The file `--config`, `LINGARA_PLATFORM_TOML` or the walk up names, if any.
      def self.located(ctx, config, dir) = PT.locate(config, ctx.env, dir)

      # Reads and checks the file at `path`, warns if it is tracked, and
      # applies its `[platform]` table.
      def self.load_at(ctx, path, dir)
        name = PT.shown(path, dir)
        toml = PT.load(path, name)
        ctx.out.note(Assets.message("platform_toml_tracked", path: name)) if Project::Git.tracked?(path)
        ctx.http.bases[:api] = toml.api if ctx.env["LINGARA_API_URL"].nil? && toml.api
        ctx.profile = toml.profile if !ctx.profile_chosen && toml.profile
        toml
      end

      # The command's `platform.toml`, which must exist.
      def self.open(ctx, config)
        dir = cwd
        path = located(ctx, config, dir)
        raise Fail.usage("no platform.toml at or above #{dir}: run lingara apps create, or lingara apps pin") if path.nil?
        raise PT.schema_fail(path, "the file does not exist") unless File.exist?(path)

        [load_at(ctx, path, dir), dir]
      end

      def self.unknown(toml, dir, value)
        Fail.from_message(Exit::USAGE, "app_row_unknown", file: PT.shown(toml.file, dir), value: value)
      end

      # How deep the row's project is, when it contains `dir`.
      def self.depth(toml, row, dir)
        project = real(PT.project_dir(toml, row))
        (dir == project || dir.start_with?("#{project}/")) ? project.split("/").size : nil
      end

      # The row named (by `name` or `id`), or with none named: the only row,
      # or the one whose project contains `dir` (the deepest, when they nest).
      def self.resolve(toml, wanted, dir)
        return named(toml, wanted, dir) if wanted
        return toml.apps.first if toml.apps.size == 1

        containing(toml, dir) || refuse_unnamed(toml, dir)
      end

      def self.named(toml, wanted, dir)
        toml.apps.find { |r| r.name == wanted || r.id == wanted } || raise(unknown(toml, dir, wanted))
      end

      # The one deepest row whose project contains `dir`, if one is deepest.
      def self.containing(toml, dir)
        rows = toml.apps.filter_map { |r| (d = depth(toml, r, dir)) && [d, r] }.sort_by { |(d, _)| -d }
        return nil if rows.empty?

        (rows.size == 1 || rows[1][0] < rows[0][0]) ? rows[0][1] : nil
      end

      def self.refuse_unnamed(toml, dir)
        raise unknown(toml, dir, "(no app is pinned)") if toml.apps.empty?

        raise Fail.from_message(Exit::USAGE, "app_row_ambiguous", file: PT.shown(toml.file, dir), names: toml.apps.map(&:name).join(", "))
      end
    end
  end
end
