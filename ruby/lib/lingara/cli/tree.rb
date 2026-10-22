# frozen_string_literal: true

require_relative "registry/root"

module Lingara
  module CLI
    # `lingara __tree` (ADR 16.10.26ab D4; 16.10.26af D2): the registered
    # tree, read back from Thor's model rather than written twice. Commands,
    # topics, the `client` alias (Thor's `map`) and option names, shorts,
    # `required` and `repeatable` come from Thor; positional arguments and
    # the `path` / `int` / `enum` types from `Registry::Table`, the same
    # table registration read. A command is dangerous exactly when it
    # carries the confirm group, the schema's own rule (16.10.26ab D2.4).
    module Tree
      # Thor's own commands, which the schema calls builtins.
      BUILTIN = %w[help].freeze

      def self.tree
        root = node(Registry::Root, "lingara", [])
        root.merge("global_flags" => Registry::Base.class_options.values.map { |o| param(o) })
      end

      def self.node(klass, name, aliases)
        commands = klass.commands.values.reject { |c| BUILTIN.include?(c.name) || c.hidden? || klass.subcommands.include?(c.name) }
        topics = klass.subcommand_classes.map { |word, sub| node(sub, word, aliases_of(klass, word)) }
        {"name" => name, "aliases" => aliases, "commands" => commands.map { |c| command(klass, c) }, "topics" => topics}
      end

      # The words `map` sends to `word`: `client` for `oauth_client`.
      def self.aliases_of(klass, word) = klass.map.select { |_, to| to.to_s == word }.keys.map(&:to_s)

      def self.command(klass, cmd)
        path = [*klass.words, cmd.name].join(" ")
        args = Registry::Table.args(path).map { |(n, required)| {"name" => n, "type" => "string", "required" => required, "repeatable" => false} }
        flags = cmd.options.values.map { |o| param(o) }
        {"name" => cmd.name, "aliases" => [], "dangerous" => cmd.options.key?(:confirm), "args" => args, "flags" => flags}
      end

      def self.param(option)
        name = option.name.to_s
        out = {"name" => name, "type" => Registry::Table.flag_type(name, option.type),
               "required" => option.required? || false, "repeatable" => option.repeatable ? true : false}
        short = option.aliases.find { |a| a.match?(/\A-[A-Za-z]\z/) }
        out["short"] = short.delete_prefix("-") if short
        out["values"] = option.enum if option.enum
        out
      end
    end
  end
end
