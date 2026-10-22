# frozen_string_literal: true

# The unit tests' world: a scripted transport, a terminal, a clock that never
# sleeps and a store holding the conformance fixture's developer. Every seam
# `App` has, faked once (the TypeScript kit's test/fakes.ts, in Ruby).
require "minitest/autorun"
require "json"
require "stringio"
require "tmpdir"
require "uri"
require "timeout"
require_relative "../lib/lingara/cli"

module Fakes
  CLI = Lingara::CLI
  EMAIL = "dev@conformance.invalid"
  REPO = File.expand_path("../..", __dir__)

  def self.stored_record
    {"developer_id" => "0b9c4f0e-0000-4000-8000-0000000000de", "email" => EMAIL,
     "scopes" => "platform:read platform:write canvas:read canvas:write", "refresh_token" => "lgr_rt_conformance_stored",
     "access_token" => "lgr_at_conformance_stored", "access_expires_at" => "2099-01-01T00:00:00Z"}
  end

  # Requests answered in order; each names the `METHOD /path` it expects,
  # anything else is a `599`.
  class Script
    attr_reader :log

    def initialize
      @answers = []
      @log = []
    end

    def then(expect, status, body = nil, headers = {})
      @answers << [expect, CLI::Http::Response.new(status, headers, body.nil? ? "" : JSON.generate(body))]
      self
    end

    def sent = @log.map { |w| "#{w.method} #{URI(w.url).path}" }

    def send_wire(wire)
      @log << wire
      expect, response = @answers.shift
      raise IOError, "connection reset" if expect.nil?

      (expect == "#{wire.method} #{URI(wire.url).path}") ? response : CLI::Http::Response.new(599, {}, "expected #{expect}")
    end
  end

  class Terminal
    def initialize(tty, lines = [])
      @tty = tty
      @lines = lines
    end

    def interactive? = @tty
    def read_line = @lines.shift
  end

  module Clock
    def self.now = 1_760_000_000
    def self.sleep(_secs) = nil
  end

  class Sink
    attr_reader :text

    def initialize = @text = +""
    def write(chunk) = @text << chunk
  end

  # An app signed in as the fixture developer over `script`, its store a
  # `MemoryStore` under `mode`.
  def self.app(script, terminal: Terminal.new(false), env: {}, store: nil)
    store ||= CLI::Store::MemoryStore.new.tap { |s| s.save("default", stored_record) }
    stores = CLI::Store::Stores.new(CLI::Store::Stores.mode_of(env["LINGARA_KEYRING"]), store)
    http = CLI::Http::Client.new(script, Clock, {api: "http://mock", portal: "http://mock"}, "lingara-cli/0.0.0 (ruby; test)", "en-US")
    CLI::App.new(http: http, env: CLI::Env.new({"LINGARA_CONFIG_DIR" => "/nonexistent"}.merge(env)), stores: stores,
      terminal: terminal, endpoints: CLI::Assets.endpoints)
  end

  # One in-process run: its outcome and its stderr.
  def self.run(app, argv)
    err = Sink.new
    [CLI::Main.run(app, argv, err), err.text]
  end
end
