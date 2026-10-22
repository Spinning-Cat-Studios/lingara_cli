# frozen_string_literal: true

require_relative "lex"
require_relative "parse"
require_relative "manifest"

module Lingara
  module CLI
    # The connection literal subset (16.10.26ae D2; spec/connection-
    # literal.md): `lingara/connection.js` read without evaluating
    # JavaScript. `Lexer` makes the tokens, `Parser` the statements,
    # `ManifestMap` turns the one `lingara.app` statement's `manifest` into
    # the wire manifest.
    module Literal
      # The file's first line, byte for byte.
      HEADER = "// lingara connection v1 (schema 2)"

      WHAT = {
        "header" => "the first line must be `// lingara connection v1 (schema 2)`",
        "comment" => "comments go inside a script; the builder rewrites everything outside them",
        "text" => "write text in quotes, with only the escapes JSON knows, and no raw tab or line break",
        "number" => "write finite numbers in decimal, as JSON does",
        "key" => "write each key once",
        "handle" => "this handle is already declared",
        "reference" => "this names no statement declared above it",
        "syntax" => "not part of the literal subset",
        "app_count" => "the file needs exactly one lingara.app statement",
        "option" => "an app takes only client and manifest",
        "manifest_key" => "not a manifest key lingara reads, or a required one is missing"
      }.freeze

      # As B2 prints it: `connection_scripted`, else `usage` with
      # `<file>:<line>:<column>: <what>`.
      def self.refusal_fail(refusal, file)
        if refusal.statement
          return Fail.from_message(Exit::CONNECTION_SCRIPTED, "connection_scripted", file: file, statement: refusal.statement)
        end

        pos = refusal.pos || Pos.new(1, 1)
        Fail.new(Exit::USAGE, "connection_literal", "#{file}:#{pos.line}:#{pos.column}: #{WHAT.fetch(refusal.code, WHAT["syntax"])} (#{refusal.code})")
      end

      # Every statement in `text`.
      def self.statements(text)
        newline = text.index("\n")
        header = (newline ? text[0...newline] : text).chomp("\r")
        raise at("header", Pos.new(1, 1)) unless header == HEADER

        body = newline ? text[(newline + 1)..] : ""
        lines = [1, text.split("\n", -1).size - (text.end_with?("\n") ? 1 : 0)].max
        Parser.statements(Lexer.tokens(body, 2), Pos.new(lines + 1, 1))
      end

      # The file's one `lingara.app` statement.
      def self.the_app(statements)
        apps = statements.select { |s| s.kind == "app" }
        raise at("app_count", Pos.new(1, 1)) if apps.empty?
        raise at("app_count", apps[1].at) if apps.size > 1
        raise at("syntax", apps[0].at) unless apps[0].value[0] == :obj

        apps[0]
      end

      # The wire manifest of the file's one `lingara.app` statement.
      def self.app_manifest(text)
        _, members, pos = the_app(statements(text)).value
        stray = members.find { |m| !%w[client manifest].include?(m.key) }
        raise at("option", stray.at) if stray

        manifest = members.find { |m| m.key == "manifest" }&.value
        raise at("manifest_key", pos) unless manifest && manifest[0] == :obj

        ManifestMap.convert(manifest[1], manifest[2])
      end
    end
  end
end
