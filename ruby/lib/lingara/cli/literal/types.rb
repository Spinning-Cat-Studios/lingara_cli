# frozen_string_literal: true

module Lingara
  module CLI
    module Literal
      # A line and column, 1-based, counted in code points.
      Pos = Struct.new(:line, :column)

      # Why the file is refused: a code at a position, or a scripted statement.
      class Refusal < StandardError
        attr_reader :code, :pos, :statement

        def initialize(code, pos = nil, statement = nil)
          super(code)
          @code = code
          @pos = pos
          @statement = statement
        end
      end

      def self.at(code, pos) = Refusal.new(code, pos)
      def self.scripted(handle) = Refusal.new("connection_scripted", nil, handle)

      # A value of the subset: `[:str, s]`, `[:num, n]`, `[:bool, b]`,
      # `[:nil]`, `[:arr, [lit…]]`, `[:obj, [Member…], pos]` or `[:ref, handle]`.
      Member = Struct.new(:key, :at, :value)
      Statement = Struct.new(:handle, :kind, :value, :at)

      # As JSON; a reference becomes its handle.
      def self.to_json_value(lit)
        case lit[0]
        when :str, :ref, :num, :bool then lit[1]
        when :nil then nil
        when :arr then lit[1].map { |l| to_json_value(l) }
        else lit[1].to_h { |m| [m.key, to_json_value(m.value)] }
        end
      end
    end
  end
end
