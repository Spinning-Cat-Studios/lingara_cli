# frozen_string_literal: true

module Lingara
  module CLI
    # stdin, a line at a time, and B3.1's test: interactive only when stdin
    # **and** stderr are terminals (16.10.26af D2).
    class Terminal
      def interactive? = $stdin.tty? && $stderr.tty?

      def read_line = $stdin.gets&.chomp
    end

    # One process's world: the HTTP client, the environment, the store, the
    # terminal, the endpoints and the signed-in session, which in-process
    # verbs share (16.10.26ad D7), so canvas apply makes no second token
    # exchange.
    class App
      attr_accessor :session
      attr_reader :http, :env, :stores, :terminal, :endpoints

      def initialize(http:, env:, stores:, terminal:, endpoints:)
        @http = http
        @env = env
        @stores = stores
        @terminal = terminal
        @endpoints = endpoints
      end

      # The in-process entry point canvas apply's inner verbs go through.
      def run(argv, err) = Main.run(self, argv, err)

      # Opens `url` in the browser unless `LINGARA_NO_BROWSER=1`; true when
      # a browser was started.
      def browser(url)
        return false if env["LINGARA_NO_BROWSER"] == "1"

        command = if Env.windows? then ["cmd", "/c", "start", "", url]
        elsif RUBY_PLATFORM.include?("darwin") then ["open", url]
        else ["xdg-open", url]
        end
        Process.detach(Process.spawn(*command, out: File::NULL, err: File::NULL))
        true
      rescue SystemCallError
        false
      end

      # `lingara-cli/<semver> (ruby; <os>)`, contract B5.
      def self.user_agent
        host = RbConfig::CONFIG["host_os"]
        os = case host
        when /darwin/ then "macos"
        when /mswin|mingw|cygwin/ then "windows"
        when /linux/ then "linux"
        else host.downcase.gsub(/[^a-z0-9_]/, "_")
        end
        "lingara-cli/#{VERSION} (ruby; #{os})"
      end

      def self.production
        env = Env.new(ENV)
        endpoints = Assets.endpoints
        stores = Store::Stores.new(Store::Stores.mode_of(env["LINGARA_KEYRING"]), Store::FileStore.new(env.config_dir))
        bases = {api: endpoints["api_url"], portal: env["LINGARA_PLATFORM_URL"] || endpoints["platform_url"]}
        http = Http::Client.new(Http::NetTransport, Http::SystemClock, bases, user_agent, env.accept_language)
        new(http: http, env: env, stores: stores, terminal: Terminal.new, endpoints: endpoints)
      end
    end
  end
end
