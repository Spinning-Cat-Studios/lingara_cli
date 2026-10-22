# frozen_string_literal: true

module Lingara
  module CLI
    # The exit codes (ADR 16.10.26ab D3, rows 0–8 and 130; 16.10.26ae D10,
    # rows 9–18) and the one refusal every command raises, which `Output`
    # prints as contract B2's line. A handler never exits by itself: `Main`
    # turns a `Fail` into the code, so in-process verbs (canvas apply) see it.
    module Exit
      OK = 0
      FAILURE = 1
      USAGE = 2
      AUTH_REQUIRED = 3
      NOT_FOUND = 4
      REFUSED = 5
      NOT_CONFIRMED = 6
      UNAVAILABLE = 7
      PLATFORM_DISABLED = 8
      TOOL_MISSING = 9
      TUNNEL_FAILED = 10
      DEV_SESSION_HELD = 11
      DEV_SESSION_LOST = 12
      APP_EXITED = 13
      CONNECTION_SCRIPTED = 14
      RENDER_URL_EPHEMERAL = 15
      PLATFORM_TOML_SECRET = 16
      PLATFORM_TOML_SCHEMA = 17
      APP_NOT_LISTENING = 18
      INTERRUPTED = 130

      # Each row's name, as `apps dev --json` reports how it ended.
      NAMES = {
        0 => "ok", 1 => "failure", 2 => "usage", 3 => "auth_required", 4 => "not_found", 5 => "refused",
        6 => "not_confirmed", 7 => "unavailable", 8 => "platform_disabled", 9 => "tool_missing",
        10 => "tunnel_failed", 11 => "dev_session_held", 12 => "dev_session_lost", 13 => "app_exited",
        14 => "connection_scripted", 15 => "render_url_ephemeral", 16 => "platform_toml_secret",
        17 => "platform_toml_schema", 18 => "app_not_listening", 130 => "interrupted"
      }.freeze

      def self.name_of(code) = NAMES.fetch(code, "failure")
    end

    # A refusal: its exit, its machine code, its sentence, and the advice
    # lines and made objects B2's line is followed by (16.10.26ad D2).
    class Fail < StandardError
      attr_reader :exit_code, :code, :hints
      attr_accessor :made, :body

      def initialize(exit_code, code, message)
        super(message)
        @exit_code = exit_code
        @code = code
        @hints = []
        @made = []
      end

      # Adds one advice line, once; returns self so a raise can chain it.
      def hint(line)
        @hints << line unless @hints.include?(line)
        self
      end

      def self.usage(message) = new(Exit::USAGE, "usage", message)
      def self.auth_required(message) = new(Exit::AUTH_REQUIRED, "auth_required", message)
      def self.failure(message) = new(Exit::FAILURE, "failure", message)
      def self.refused(code, message) = new(Exit::REFUSED, code, message)

      # A refusal with a `messages.en.json` text: code and message id are one
      # word, as every 16.10.26ae row's are.
      def self.from_message(exit_code, id, vars = {})
        new(exit_code, id, Assets.message(id, vars))
      end
    end
  end
end
