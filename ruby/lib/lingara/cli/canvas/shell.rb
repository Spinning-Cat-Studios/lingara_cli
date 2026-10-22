# frozen_string_literal: true

module Lingara
  module CLI
    module Canvas
      # `--print-commands`' spelling of an argv (16.10.26ad D7 step 7): the
      # matryoshka made visible, each word quoted only where a POSIX shell
      # would need it.
      module Shell
        # POSIX single quotes, only where a word needs them.
        def self.quote(word)
          (!word.empty? && word.match?(%r{\A[A-Za-z0-9\-_./:@=,+]+\z})) ? word : "'#{word.gsub("'", "'\\\\''")}'"
        end

        # One argv as a shell line, ids not yet made as `<handle>`.
        def self.line(argv, manifests)
          words = argv.map do |(tag, value)|
            case tag
            when :text then quote(value)
            when :made then quote("<#{value}>")
            else quote(manifests.fetch(value, ""))
            end
          end
          "lingara #{words.join(" ")}"
        end
      end
    end
  end
end
