# frozen_string_literal: true

require "json"

module Lingara
  module CLI
    # What the CLI checks in a wire manifest before it sends one (ADR
    # 16.10.26ae D6): the ephemeral render URL a version may never carry,
    # the manifest rules a warning depends on, and the warnings themselves,
    # pinned by `spec/manifest-warnings.json` (the app kits'
    # `manifestWarnings`). The server stays the authority on every rule;
    # these run first so a refusal costs no request.
    module Manifest
      # The slots a manifest may name (the app view's `AppSlotName`).
      SLOTS = %w[home.side plans.empty_detail].freeze

      WARNING_TEXTS = {
        "single_locale" => "the listing is in its default locale only; give name and description a second locale"
      }.freeze

      # The URL's host, lowercased: no scheme, user, port or path.
      def self.host(url)
        rest = url.include?("://") ? url[(url.index("://") + 3)..] : url
        authority = rest.split(%r{[/?#]}).first.to_s
        authority.split("@").last.to_s.split(":").first.to_s.downcase
      end

      # A quick tunnel's or the template's placeholder: never a version's URL.
      def self.ephemeral?(render_url)
        h = host(render_url.to_s)
        h == "dev.invalid" || h == "trycloudflare.com" || h.end_with?(".trycloudflare.com")
      end

      def self.locale_map?(value)
        value.is_a?(Hash) && !value.empty? && value.values.all? { |s| s.is_a?(String) && !s.empty? }
      end

      def self.duplicate?(value) = value.is_a?(Array) && value.uniq { |x| JSON.generate(x) }.size != value.size

      def self.default_locale(m) = m["default_locale"].is_a?(String) ? m["default_locale"] : ""

      # `default_locale` names an entry in each of `name` and `description`.
      def self.locale_covered?(m)
        locale = default_locale(m)
        !locale.empty? && %w[name description].all? { |key| !m[key].is_a?(Hash) || m[key].key?(locale) }
      end

      def self.render_url_ok?(url) = url.is_a?(String) && url.match?(%r{\Ahttps://}i) && !host(url).empty?

      # The rules a warning depends on, in the app kits' order: each a
      # name and a test of the manifest that holds when the rule is kept.
      RULES = [
        ["manifest_version", ->(m) { m["manifest_version"] == 1 }],
        ["default_locale", ->(m) { locale_covered?(m) }],
        ["name", ->(m) { locale_map?(m["name"]) }],
        ["description", ->(m) { locale_map?(m["description"]) }],
        ["render_url", ->(m) { render_url_ok?(m["render_url"]) }],
        ["slots", ->(m) { m["slots"].is_a?(Array) && !m["slots"].empty? && m["slots"].all? { |s| SLOTS.include?(s) } }],
        ["context", ->(m) { m["context"].nil? || (m["context"].is_a?(Array) && m["context"].all?(String)) }],
        ["locales", ->(m) { !m.key?("locales") }],
        ["duplicate", ->(m) { %w[slots context scopes].none? { |k| duplicate?(m[k]) } }]
      ].freeze

      # The first rule `m` breaks, of the ones a warning depends on.
      def self.broken_rule(m) = RULES.find { |(_, kept)| !kept.call(m) }&.first

      # Where `name` and `description` both have an entry, and
      # `long_description` too when present and not null, in byte order.
      def self.locales(m)
        return [] unless broken_rule(m).nil?

        long = m["long_description"]
        keys = ->(v) { v.is_a?(Hash) ? v.keys : [] }
        covered = ->(l) { keys.call(m["description"]).include?(l) && (long.nil? || keys.call(long).include?(l)) }
        keys.call(m["name"]).select(&covered).sort_by(&:b)
      end

      # The listing's warnings: v1 `single_locale` alone. Never a refusal.
      def self.warnings(m)
        ls = locales(m)
        (ls.size == 1 && ls.first == m["default_locale"]) ? ["single_locale"] : []
      end

      def self.warning_text(code) = WARNING_TEXTS.fetch(code, "")
    end
  end
end
