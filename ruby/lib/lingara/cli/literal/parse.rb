# frozen_string_literal: true

require_relative "types"

module Lingara
  module CLI
    module Literal
      # Statements and values over the tokens (spec/connection-literal.md):
      # `const <handle> = lingara.<kind>(<value>)`, one after another. A
      # second call argument is a script, which refuses the file there and
      # then.
      class Parser
        KINDS = %w[client secret webhook app].freeze

        # Every statement of the body, which ends at `finish`.
        def self.statements(toks, finish)
          p = new(toks, finish)
          out = []
          out << p.statement until p.peek.nil?
          out
        end

        def initialize(toks, finish)
          @toks = toks
          @i = 0
          @finish = finish
          @handles = {}
        end

        def peek = @toks[@i]

        def statement
          pos = word("const")
          handle, handle_at = ident
          raise Literal.at("handle", handle_at) if @handles.key?(handle)

          punct("=")
          word("lingara")
          punct(".")
          kind, kind_at = ident
          raise Literal.at("syntax", kind_at) unless KINDS.include?(kind)

          punct("(")
          value = self.value
          raise Literal.scripted(handle) if punct?(",")

          punct(")")
          # Declared only now: a value may name the statements above it, never its own.
          @handles[handle] = true
          Statement.new(handle, kind, value, pos)
        end

        def value
          type, v, pos = take
          case type
          when :str then [:str, v]
          when :num then [:num, v]
          when :ident then word_value(v, pos)
          else punct_value(v, pos)
          end
        end

        private

        def punct?(c) = peek && peek[0] == :punct && peek[1] == c

        def take
          t = peek
          raise Literal.at("syntax", @finish) if t.nil?

          @i += 1
          t
        end

        def punct(c)
          type, v, pos = take
          raise Literal.at("syntax", pos) unless type == :punct && v == c

          pos
        end

        def ident
          type, v, pos = take
          raise Literal.at("syntax", pos) unless type == :ident

          [v, pos]
        end

        def word(want)
          w, pos = ident
          raise Literal.at("syntax", pos) unless w == want

          pos
        end

        def punct_value(v, pos)
          return [:arr, items("]") { value }] if v == "["
          return [:obj, object, pos] if v == "{"

          raise Literal.at("syntax", pos)
        end

        def word_value(w, pos)
          return [:bool, w == "true"] if %w[true false].include?(w)
          return [:nil] if w == "null"
          return [:ref, w] if @handles.key?(w)

          raise Literal.at("reference", pos)
        end

        # `item {, item} [,] close`, the opener already read.
        def items(close)
          out = []
          loop do
            if punct?(close)
              @i += 1
              return out
            end
            out << yield
            type, v, pos = take
            return out if type == :punct && v == close
            raise Literal.at("syntax", pos) unless type == :punct && v == ","
          end
        end

        def object
          seen = {}
          items("}") do
            type, key, pos = take
            raise Literal.at("syntax", pos) unless %i[ident str].include?(type)
            raise Literal.at("key", pos) if seen.key?(key)

            seen[key] = true
            punct(":")
            Member.new(key, pos, value)
          end
        end
      end
    end
  end
end
