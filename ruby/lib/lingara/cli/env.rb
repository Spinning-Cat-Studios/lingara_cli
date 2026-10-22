# frozen_string_literal: true

module Lingara
  module CLI
    # The process environment, read once, and the config dir and
    # `config.toml` (ADR 16.10.26ad D4). The dir is `LINGARA_CONFIG_DIR`, else
    # `$XDG_CONFIG_HOME/lingara`, else `~/.config/lingara` on macOS and Linux
    # alike, and `%APPDATA%\lingara` on Windows. `config.toml` holds only
    # non-secret per-profile settings: `[profiles.<name>]` `api_url` and
    # `output`.
    class Env
      def initialize(vars)
        @vars = vars.to_h
      end

      # A set, non-empty variable, or nil.
      def [](name)
        v = @vars[name]
        (v.nil? || v.empty?) ? nil : v
      end

      # Every set variable: the environment `apps dev`'s child starts from.
      def all = @vars.dup

      def self.windows? = Gem.win_platform?

      def config_dir
        explicit = self["LINGARA_CONFIG_DIR"]
        return explicit if explicit
        return File.join(self["APPDATA"] || Dir.home, "lingara") if Env.windows?

        xdg = self["XDG_CONFIG_HOME"]
        return File.join(xdg, "lingara") if xdg

        File.join(self["HOME"] || Dir.home, ".config", "lingara")
      end

      # `[profiles.<name>]` from `config.toml`, read for its two string keys
      # only, as the reference kits do: a full TOML read would refuse a whole
      # file for one bad line elsewhere in it.
      def profile_config(profile)
        text = File.read(File.join(config_dir, "config.toml"), encoding: "UTF-8")
        in_profile = false
        text.each_line.with_object({}) do |raw, out|
          line = raw.strip
          table = line.match(/\A\[(.+)\]\z/)
          in_profile = [%(profiles.#{profile}), %(profiles."#{profile}")].include?(table[1].strip) if table
          pair = line.match(/\A(api_url|output)\s*=\s*"([^"]*)"/)
          out[pair[1]] = pair[2] if in_profile && pair
        end
      rescue SystemCallError
        {}
      end

      # `LC_ALL`, else `LC_MESSAGES`, else `LANG`, as BCP 47 (contract B5):
      # `en_US.UTF-8` is `en-US`; `C` and `POSIX` are none.
      def accept_language
        raw = self["LC_ALL"] || self["LC_MESSAGES"] || self["LANG"]
        tag = raw&.split(/[.@]/)&.first.to_s.tr("_", "-")
        (tag.empty? || %w[C POSIX].include?(tag)) ? nil : tag
      end
    end
  end
end
