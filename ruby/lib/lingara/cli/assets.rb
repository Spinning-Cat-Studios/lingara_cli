# frozen_string_literal: true

require "json"

module Lingara
  module CLI
    # Every shared file this kit reads, from its own `assets/` (ADR
    # 16.10.26af D8): `endpoints.json` (where production is),
    # `messages.en.json` (what a prompt says, byte for byte), `tools.toml`
    # (cloudflared's floor), the platform.toml schema and the app templates.
    # All are committed copies held equal to spec/ and templates/ by `make
    # check-kit-assets`, because the gem carries only ruby/. Read relative to
    # this file, so the checkout and an installed gem agree.
    module Assets
      DIR = File.join(__dir__, "assets")

      def self.read(name) = File.read(File.join(DIR, name), encoding: "UTF-8")

      def self.texts
        @texts ||= JSON.parse(read("messages.en.json"))["messages"].to_h { |m| [m["id"], m["text"]] }
      end

      # The message `id` with each `{name}` filled.
      def self.message(id, vars = {})
        vars.reduce(texts.fetch(id, id)) { |text, (k, v)| text.gsub("{#{k}}", v.to_s) }
      end

      # `client_id`, `api_url`, `platform_url`.
      def self.endpoints
        @endpoints ||= JSON.parse(read("endpoints.json"))
      end

      def self.schema_path = File.join(DIR, "platform-toml.schema.json")

      # `tools.toml`'s `[cloudflared] min_version`, as dotted numbers.
      def self.cloudflared_min_version
        require "tomlrb"
        version = Tomlrb.parse(read("tools.toml")).dig("cloudflared", "min_version").to_s
        version.split(".").map(&:to_i)
      end

      # Every file of one template (`<lang>/<variant>/`) as `[relative path,
      # bytes]`, in byte order; empty when there is none.
      def self.template(lang, variant)
        root = File.join(DIR, "templates", "apps", lang, variant)
        return [] unless File.directory?(root)

        Dir.glob("**/*", File::FNM_DOTMATCH, base: root).sort
          .select { |p| File.file?(File.join(root, p)) }
          .map { |p| [p, File.binread(File.join(root, p))] }
      end
    end
  end
end
