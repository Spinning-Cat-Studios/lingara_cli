# frozen_string_literal: true

require_relative "types"

module Lingara
  module CLI
    module Literal
      # The literal subset's tokens (spec/connection-literal.md; 16.10.26ae
      # D2): identifiers, JSON-escaped strings in either quote, finite JSON
      # numbers and punctuation, each with its 1-based line and column,
      # counted in code points. A comment, a template literal or a character
      # the subset does not know stops the scan. A token is `[type, value,
      # pos]`, the type `:ident`, `:str`, `:num` or `:punct`.
      class Lexer
        PUNCT = "{}[](),:=."
        SIMPLE = {'"' => '"', "\\" => "\\", "/" => "/", "b" => "\b", "f" => "\f", "n" => "\n", "r" => "\r", "t" => "\t"}.freeze
        RAW = ["\n", "\r", "\t"].freeze
        NUMBER = /\A-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?\z/

        # Every token from `text`, which starts at line `first_line`.
        def self.tokens(text, first_line) = new(text, first_line).tokens

        def initialize(text, line)
          @chars = text.chars
          @i = 0
          @line = line
          @col = 1
        end

        def tokens
          out = []
          until (c = peek).nil?
            if [" ", "\t", "\r", "\n"].include?(c)
              bump
              next
            end
            start = Pos.new(@line, @col)
            out << [*one(c, start), start]
          end
          out
        end

        private

        def peek(ahead = 0) = @chars[@i + ahead]

        def bump
          c = @chars[@i]
          return nil if c.nil?

          @i += 1
          if c == "\n"
            @line += 1
            @col = 1
          else
            @col += 1
          end
          c
        end

        def take_while
          out = +""
          out << bump while (c = peek) && yield(c)
          out
        end

        def one(c, start)
          raise Literal.at("comment", start) if c == "/" && ["/", "*"].include?(peek(1))
          raise Literal.at("text", start) if c == "`"
          return [:str, string(start)] if ['"', "'"].include?(c)

          scalar(c, start)
        end

        # A number, an identifier or punctuation.
        def scalar(c, start)
          return [:num, number(start)] if c == "-" || c.match?(/[0-9]/)
          return [:ident, take_while { |x| x.match?(/[A-Za-z0-9_$]/) }] if c.match?(/[A-Za-z_$]/)
          raise Literal.at("syntax", start) unless PUNCT.include?(c)

          bump
          [:punct, c]
        end

        # A finite JSON number. As JavaScript reads one, an integral value is
        # an integer, so `1e3` becomes `1000` on the wire, not `1000.0`.
        def number(start)
          text = take_while { |x| x.match?(/[A-Za-z0-9.+-]/) }
          raise Literal.at("number", start) unless text.match?(NUMBER)

          n = Float(text)
          raise Literal.at("number", start) unless n.finite?

          (n == n.truncate && n.abs < 2**53) ? n.to_i : n
        end

        def string(start)
          quote = bump
          out = +""
          while (c = string_char(quote, start))
            out << c
          end
          out
        end

        # One character of a string, unescaped; nil at its closing quote.
        def string_char(quote, start)
          c = bump
          raise Literal.at("text", start) if c.nil? || RAW.include?(c)
          return nil if c == quote
          raise Literal.at("text", start) if c == "$" && peek == "{"
          return c unless c == "\\"

          escape(quote) || raise(Literal.at("text", start))
        end

        # JSON's escapes, and `\'` inside single quotes.
        def escape(quote)
          c = bump
          return SIMPLE[c] if SIMPLE.key?(c)
          return "'" if c == "'" && quote == "'"
          return nil unless c == "u"

          hex = Array.new(4) { bump }.join
          return nil unless hex.match?(/\A[0-9A-Fa-f]{4}\z/)

          hex.to_i(16).chr(Encoding::UTF_8)
        rescue RangeError
          nil
        end
      end
    end
  end
end
