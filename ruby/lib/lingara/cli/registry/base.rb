# frozen_string_literal: true

require "thor"
require_relative "table"

module Lingara
  module CLI
    # The command tree is Thor's (ADR 16.10.26af D2). A leaf's method only
    # parses: it returns a `Parsed`, and `Main` runs the handler outside
    # Thor, so exit codes, output and in-process verbs (canvas apply) are the
    # kit's own, as `run(argv) -> Outcome` (16.10.26ad D11).
    module Registry
      Parsed = Struct.new(:path, :args, :flags)

      # The one base class, where D9's overrides live:
      #
      # - `check_unknown_options!`: Thor ignores an unknown option by default;
      # - `exit_on_failure?` is true, and `Main` runs Thor with `debug: true`
      #   so every parse error reaches it as a `Thor::Error`, mapped there to
      #   `usage` (2) rather than Thor's own exit 1;
      # - command lookup is exact: Thor accepts an unambiguous prefix
      #   (`apps lis`) and a dashed spelling (`oauth-client`), neither of
      #   which is in the schema;
      # - a bare group (`lingara apps`) is a usage error, not Thor's help;
      # - Thor's own `tree` command is removed: it is not in the schema.
      class Base < ::Thor
        check_unknown_options!
        remove_command :tree

        class_option :json, type: :boolean, desc: "Machine-readable output on stdout"
        class_option :profile, type: :string, banner: "name", desc: "The stored credentials to use"

        def self.exit_on_failure? = true

        # The words that lead to this class's commands: `%w[apps versions]`.
        def self.words = []

        def self.normalize_command_name(meth)
          raise ::Thor::InvocationError, "#{(["lingara"] + words).join(" ")} needs a command" if meth.nil?

          name = meth.to_s
          name = map[name].to_s if map.key?(name)
          return name if all_commands.key?(name)

          raise ::Thor::UndefinedCommandError.new(name, [], nil)
        end

        # One flag, its Thor type and `enum:` read from the table.
        def self.flag(name, desc, **opts)
          d2 = Table.flag_type(name.to_s, opts[:type])
          type = Table::THOR_TYPES.fetch(d2, opts.fetch(:type, :string))
          enum = Table::ENUMS[name.to_s]
          method_option(name, **opts, type: type, desc: desc, **(enum ? {enum: enum} : {}))
        end

        # `-y` and `--confirm`, the group every dangerous command carries
        # (16.10.26ad D5). Neither flag reads the environment.
        def self.confirm_flags
          flag :yes, "Answer the y/n question; needs --confirm too", type: :boolean, aliases: "-y"
          flag :confirm, "Your developer email, typed back; needs -y too", banner: "email"
        end

        # Every object `delete`: an optional version, then the ritual.
        def self.delete_flags
          flag :version, "Delete only this version", banner: "semver"
          confirm_flags
        end

        def self.config_flag
          flag :config, "The platform.toml to read; default the nearest at or above this directory", banner: "path"
        end

        # A command whose usage, arity and capture come from the table.
        def self.leaf(name, summary, hide: false)
          path = [*words, name.to_s].join(" ")
          spelled = Table.args(path).map { |(n, required)| required ? n.upcase : "[#{n.upcase}]" }
          desc [name.to_s, *spelled].join(" "), summary, hide: hide
          define_method(name) { |*given| capture(path, given) }
        end

        no_commands do
          # The leaf's argv as a `Parsed`, after the table's arity and the
          # `int` range Thor's `:numeric` does not check.
          def capture(path, given)
            table = Table.args(path)
            unless (table.count { |(_, required)| required }..table.size).cover?(given.size)
              raise ::Thor::InvocationError, "lingara #{path} takes #{table.map(&:first).join(", ")}; got #{given.size} argument(s)"
            end

            flags = options.to_h
            check_ints(flags)
            Parsed.new(path, table.each_with_index.to_h { |(n, _), i| [n, given[i]] }, flags)
          end

          def check_ints(flags)
            flags.each do |name, value|
              next unless Table.flag_type(name, nil) == "int"
              next if value.is_a?(Integer) && (1..65_535).cover?(value)

              raise ::Thor::MalformattedArgumentError, "--#{name} must be a whole number from 1 to 65535"
            end
          end
        end
      end
    end
  end
end
