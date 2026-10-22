# frozen_string_literal: true

require "fileutils"

module Lingara
  module CLI
    # Rendering a template (16.10.26ae D2, D3; spec/templates.md): the fixed
    # `{{key}}` set, the array syntax each language's bare template uses,
    # the two renaming rules, and `template.toml`, read and never written.
    module Template
      # What the scaffold substitutes, each derived once (spec/templates.md).
      Keys = Struct.new(:name, :display_name, :description, :port, :slots, :context)
      # `template.toml`: the row's start command and its manifest source,
      # `[key, value]` with the key `manifest` or `manifest_command`.
      Meta = Struct.new(:start, :manifest)

      META = "template.toml"

      # `["a", "b"]` in the syntax the template's language writes an array in.
      def self.array(lang, variant, items)
        inner = items.map { |i| "\"#{i}\"" }.join(", ")
        return "[#{inner}]" unless variant == "bare"

        case lang
        when "go" then "[]string{#{inner}}"
        when "java" then "List.of(#{inner})"
        when "kotlin" then "listOf(#{inner})"
        else "[#{inner}]"
        end
      end

      # A segment starting `_` is written with `.`; a `.tmpl` suffix is dropped.
      def self.project_path(relative)
        segments = relative.split("/").map { |s| s.start_with?("_") ? ".#{s[1..]}" : s }
        segments[-1] = segments[-1].delete_suffix(".tmpl")
        segments.join("/")
      end

      def self.substitute(text, lang, variant, keys)
        pairs = [["{{name}}", keys.name], ["{{display_name}}", keys.display_name], ["{{description}}", keys.description],
          ["{{port}}", keys.port.to_s], ["{{slots}}", array(lang, variant, keys.slots)],
          ["{{context}}", array(lang, variant, keys.context)]]
        pairs.reduce(text) { |t, (k, v)| t.gsub(k) { v } }
      end

      def self.parse_meta(text)
        require "tomlrb"
        meta = Tomlrb.parse(text)
        get = ->(k) { meta[k].is_a?(String) ? meta[k] : nil }
        source = if get.call("manifest") then ["manifest", get.call("manifest")]
        elsif get.call("manifest_command") then ["manifest_command", get.call("manifest_command")]
        end
        Meta.new(get.call("start"), source)
      end

      # Every file of `<lang>/<variant>`, rendered, and its `template.toml`.
      def self.render(lang, variant, keys)
        files = Assets.template(lang, variant)
        raise Fail.failure("this lingara carries no #{lang} #{variant} template") if files.empty?

        meta = Meta.new(nil, nil)
        out = []
        files.each do |(path, bytes)|
          text = bytes.dup.force_encoding("UTF-8")
          if path == META then meta = parse_meta(text)
          else out << [project_path(path), substitute(text, lang, variant, keys)]
          end
        end
        [out, meta]
      end

      # Writes every file under `dir`, creating directories as it goes.
      def self.write_all(dir, files)
        files.each do |(path, text)|
          target = File.join(dir, path)
          FileUtils.mkdir_p(File.dirname(target))
          File.binwrite(target, text)
        end
      end
    end
  end
end
