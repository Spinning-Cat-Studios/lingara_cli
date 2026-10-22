# frozen_string_literal: true

module Lingara
  module CLI
    module Project
      # `spec/platform-toml.schema.json` over the parsed file (16.10.26ae
      # D4), through json_schemer (16.10.26af D2), so the committed schema
      # itself is the rule set rather than a hand copy of it. The first error
      # in json_schemer's order becomes the refusal's reason, spelled as the
      # reference kits spell theirs where a keyword names one.
      module Schema
        def self.schemer
          @schemer ||= begin
            require "json_schemer"
            JSONSchemer.schema(JSON.parse(Assets.read("platform-toml.schema.json")))
          end
        end

        # A lowercase letter, then up to 39 lowercase letters, digits or hyphens.
        def self.app_name_ok?(name) = name.is_a?(String) && name.match?(/\A[a-z][a-z0-9-]{0,39}\z/)

        # The reason the file breaks the schema, or nil.
        def self.check(value)
          error = schemer.validate(jsonable(value)).first
          error && reason(error)
        end

        # TOML dates and times are strings to the schema, as they are to the
        # reference kits' JSON view of the file.
        def self.jsonable(value)
          case value
          when Hash then value.transform_values { |v| jsonable(v) }
          when Array then value.map { |v| jsonable(v) }
          when String, Integer, Float, true, false, nil then value
          else value.to_s
          end
        end

        def self.reason(error)
          at = error["data_pointer"].delete_prefix("/").gsub(%r{/(\d+)(?=/|\z)}, '[\1]').tr("/", ".")
          case error["type"]
          when "required" then "#{[at, *error.dig("details", "missing_keys")].reject(&:empty?).join(".")} is missing"
          when "schema" then "#{at} is not a key this file takes"
          when "const" then "#{at} is #{error["data"].inspect}; this lingara reads 1"
          when "not" then "#{at}: manifest and manifest_command are one or the other"
          else "#{at.empty? ? "the file" : at}: #{error["error"]}"
          end
        end
      end
    end
  end
end
