# frozen_string_literal: true

require "json"

module Lingara
  module CLI
    # Contract B1's streams and table, and B2's refusal line (ADR 16.10.26ad
    # D6). Data goes to stdout, buffered and written once by `Main`, so an
    # in-process verb that canvas apply runs hands its JSON back instead;
    # everything else goes to stderr.
    class Output
      attr_accessor :stdout
      attr_reader :json

      def initialize(err, json)
        @err = err
        @json = json
        @stdout = +""
      end

      # An empty value is `-`, so no cell is blank and the split stays exact.
      def self.cell_text(cell) = (cell.nil? || cell == "") ? "-" : cell

      # A header and its rows: left-aligned, two spaces apart, no trailing
      # whitespace, never truncated.
      def self.render(columns, rows)
        all = [columns, *rows]
        widths = columns.each_index.map { |i| all.map { |r| cell_text(r[i]).length }.max }
        all.map { |r| "#{columns.each_index.map { |i| cell_text(r[i]).ljust(widths[i]) }.join("  ").rstrip}\n" }.join
      end

      # One JSON value as a cell: scalars as text, lists of scalars joined
      # with commas, `null` and `[]` as `-`.
      def self.cell(value)
        case value
        when nil then "-"
        when String then value
        when Array then array_cell(value)
        when Hash then JSON.generate(value)
        else JSON.generate(value)
        end
      end

      def self.array_cell(value)
        return "-" if value.empty?
        return value.map { |v| cell(v) }.join(",") if value.none? { |v| v.is_a?(Hash) || v.is_a?(Array) }

        JSON.generate(value)
      end

      # A `get`: B1's `FIELD  VALUE` form, one row per member in the server's order.
      def self.fields(object)
        return [["value", cell(object)]] unless object.is_a?(Hash)

        object.map { |k, v| [k, cell(v)] }
      end

      def print(text) = @stdout << text

      # One line on stderr.
      def note(line) = @err.write("#{line}\n")

      # A prompt on stderr, left on the line it ends.
      def prompt(text) = @err.write(text)

      def json_value(value) = print("#{JSON.generate(value)}\n")

      # One server value: as JSON, or as the `FIELD  VALUE` table.
      def value(value)
        json ? json_value(value) : print(Output.render(%w[FIELD VALUE], Output.fields(value)))
      end

      # A list: one JSON array, or the schema's columns read from each item.
      def list(items, columns)
        return json_value(items) if json

        rows = items.map { |item| columns.map { |(_, key)| Output.cell(item.is_a?(Hash) ? item[key] : nil) } }
        print(Output.render(columns.map(&:first), rows))
      end

      # A command with nothing to print: nothing, or `{}` under `--json`.
      def nothing = json && print("{}\n")

      # The lines `fail` prints on stderr: B2's one line and its advice, or
      # one JSON line.
      def self.error_lines(fail, json)
        if json
          error = {"code" => fail.code, "message" => fail.message, "exit" => fail.exit_code}
          error["hints"] = fail.hints unless fail.hints.empty?
          error["made"] = fail.made.map { |m| {"kind" => m[0], "id" => m[1]} } unless fail.made.empty?
          return [JSON.generate({"error" => error})]
        end
        ["error: #{fail.message} (#{fail.code})", *fail.hints, *fail.made.map { |(kind, id)| "made: #{kind} #{id}" }]
      end
    end
  end
end
