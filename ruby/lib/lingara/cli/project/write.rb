# frozen_string_literal: true

module Lingara
  module CLI
    module Project
      # Writing `platform.toml`, its example and `.gitignore`, byte for byte
      # as spec/templates.md says (16.10.26ae D1, D4), so every kit leaves the
      # same files behind. The edits are line-based on purpose: a
      # developer's comments and layout outside the touched row survive.
      module Write
        FILE = "platform.toml"
        EXAMPLE = "platform.toml.example"
        PLACEHOLDER_ID = "00000000-0000-0000-0000-000000000000"

        FILE_HEADER = "# platform.toml — gitignored; copy from platform.toml.example.\n" \
          "# Never put a secret here. lingara refuses to read a value that looks like one.\nschema = 1\n"
        EXAMPLE_HEADER = "# platform.toml.example — checked in. Copy it to platform.toml (gitignored),\n" \
          "# then run lingara apps update to register your own private copy of each app.\nschema = 1\n"

        # A row as `create` and `pin` write it; `manifest` is
        # `[key, value]`, the key `manifest` or `manifest_command`.
        NewRow = Struct.new(:name, :path, :port, :start, :manifest)

        # A TOML basic string.
        def self.quote(text) = "\"#{text.gsub("\\", "\\\\\\\\").gsub('"', '\\"')}\""

        def self.row_text(row, id)
          out = "\n[[apps]]\nname = #{quote(row.name)}\n"
          out << "id = #{quote(id)}\n" if id
          out << "path = #{quote(row.path)}\nport = #{row.port}\n"
          out << "start = #{quote(row.start)}\n" if row.start
          out << "#{row.manifest[0]} = #{quote(row.manifest[1])}\n" if row.manifest
          out
        end

        def self.read_or(path, header)
          text = File.read(path, encoding: "UTF-8")
          (!text.empty? && !text.end_with?("\n")) ? "#{text}\n" : text
        rescue SystemCallError
          header
        end

        def self.lines(text) = text.empty? ? [] : text.chomp("\n").split("\n", -1)
        def self.joined(lines) = lines.map { |l| "#{l}\n" }.join

        # Each `[[apps]]` row's line span `[start, end)`: up to the next
        # table header that is not its own `[apps.env]`.
        def self.rows(ls)
          header = ->(l) { l.lstrip.start_with?("[") && l.strip != "[apps.env]" }
          starts = ls.each_index.select { |i| ls[i].strip == "[[apps]]" }
          starts.map do |s|
            nxt = ((s + 1)...ls.size).find { |i| header.call(ls[i]) }
            [s, nxt || ls.size]
          end
        end

        def self.span_of(ls, name)
          want = "name = #{quote(name)}"
          rows(ls).find { |(s, e)| ls[s...e].any? { |l| l.strip == want } }
        end

        def self.row?(text, name) = !span_of(lines(text), name).nil?

        # Appends the row to `file` (with `id`, if any) and, when the example
        # beside it has none of that name, to the example.
        def self.pin_row(file, row, id)
          File.write(file, read_or(file, FILE_HEADER) + row_text(row, id))
          example = File.join(File.dirname(file), EXAMPLE)
          current = read_or(example, EXAMPLE_HEADER)
          File.write(example, current + row_text(row, PLACEHOLDER_ID)) unless row?(current, row.name)
        end

        # Writes `id` into the row named `name`: replaces its `id` line, or
        # adds one after its `name` line.
        def self.set_id(file, name, id)
          ls = lines(File.read(file, encoding: "UTF-8"))
          span = span_of(ls, name)
          return if span.nil?

          key = ->(l, k) { l.match?(/\A\s*#{k}\s*=/) }
          existing = (span[0]...span[1]).find { |i| key.call(ls[i], "id") }
          if existing
            ls[existing] = "id = #{quote(id)}"
          else
            name_at = (span[0]...span[1]).find { |i| key.call(ls[i], "name") } || span[0]
            ls.insert(name_at + 1, "id = #{quote(id)}")
          end
          File.write(file, joined(ls))
        end

        # Removes the row named `name`; false when there is none.
        def self.remove_row(file, name)
          ls = lines(File.read(file, encoding: "UTF-8"))
          span = span_of(ls, name)
          return false if span.nil?

          start = span[0]
          start -= 1 while span[1] == ls.size && start.positive? && ls[start - 1].strip.empty?
          File.write(file, joined(ls[0...start] + ls[span[1]..]))
          true
        end

        # Adds a `platform.toml` line to `<dir>/.gitignore` when no line is one.
        def self.ensure_gitignore(dir)
          path = File.join(dir, ".gitignore")
          text = read_or(path, "")
          File.write(path, "#{text}#{FILE}\n") unless text.split("\n").any? { |l| l.strip == FILE }
        end
      end
    end
  end
end
