# frozen_string_literal: true

require_relative "types"

module Lingara
  module CLI
    module Literal
      # The closed key map from `lingara.v1`'s `ManifestOptions` to the wire
      # manifest (spec/connection-literal.md), in `lingara.manifest`'s member
      # order. A key the map does not name is refused, never passed through.
      module ManifestMap
        # Literal key → wire key, in the order the wire manifest writes them.
        KEYS = [%w[defaultLocale default_locale], %w[name name], %w[description description], %w[renderUrl render_url],
          %w[slots slots], %w[context context], %w[scopes scopes], %w[tutorNote tutor_note],
          %w[longDescription long_description], %w[developer developer], %w[images images]].freeze
        REQUIRED = %w[name description renderUrl slots].freeze
        DEVELOPER = [%w[name name], %w[url url], %w[supportUrl support_url]].freeze
        IMAGE = [%w[sha256 sha256], %w[role role], %w[alt alt]].freeze
        LOCALE_MAPS = %w[name description longDescription].freeze
        DEFAULTS = {"context" => [], "scopes" => [], "tutorNote" => false}.freeze

        def self.find(members, key) = members.find { |m| m.key == key }&.value

        # A string is the default locale's; an object passes its tags through.
        def self.locale_map(lit, locale) = (lit[0] == :str) ? {locale => lit[1]} : Literal.to_json_value(lit)

        # An object whose keys must all be in `map`, renamed through it.
        def self.renamed(lit, map, locale)
          return Literal.to_json_value(lit) unless lit[0] == :obj

          stray = lit[1].find { |m| map.none? { |(k, _)| k == m.key } }
          raise Literal.at("manifest_key", stray.at) if stray

          map.each_with_object({}) do |(from, to), out|
            value = find(lit[1], from)
            out[to] = (from == "alt") ? locale_map(value, locale) : Literal.to_json_value(value) if value
          end
        end

        def self.value_of(from, value, locale)
          return locale_map(value, locale) if LOCALE_MAPS.include?(from)
          return renamed(value, DEVELOPER, locale) if from == "developer"
          return value[1].map { |i| renamed(i, IMAGE, locale) } if from == "images" && value[0] == :arr

          Literal.to_json_value(value)
        end

        # The wire manifest for the `manifest` object at `pos`.
        def self.convert(members, pos)
          stray = members.find { |m| KEYS.none? { |(k, _)| k == m.key } }
          raise Literal.at("manifest_key", stray.at) if stray
          raise Literal.at("manifest_key", pos) if REQUIRED.any? { |k| find(members, k).nil? }

          locale_lit = find(members, "defaultLocale")
          locale = (locale_lit && locale_lit[0] == :str) ? locale_lit[1] : "en"
          out = {"manifest_version" => 1, "default_locale" => locale_lit ? Literal.to_json_value(locale_lit) : "en"}
          out.merge(members_of(members, locale))
        end

        # Every key after `defaultLocale`, in wire order, with `lingara.v1`'s
        # defaults for the three it fills.
        def self.members_of(members, locale)
          KEYS.drop(1).each_with_object({}) do |(from, to), out|
            value = find(members, from)
            if value
              out[to] = value_of(from, value, locale)
            elsif DEFAULTS.key?(from)
              out[to] = DEFAULTS[from]
            end
          end
        end
      end
    end
  end
end
