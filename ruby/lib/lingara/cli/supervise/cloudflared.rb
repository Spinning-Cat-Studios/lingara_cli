# frozen_string_literal: true

require "json"
require "socket"
require_relative "group"
require_relative "lookup"

module Lingara
  module CLI
    # One cloudflared incarnation (16.10.26ae D8; finding the binary is
    # `Cloudflared`'s). **URL**: the CLI picks a free loopback port for
    # `--metrics` and polls its `/quicktunnel`, falling back to the
    # `https://…trycloudflare.com` line on stderr. **Ready**: the probe URL
    # (`LINGARA_TUNNEL_PROBE_URL`, default `https://{hostname}/`) answers
    # anything but Cloudflare's `530`. Each wait is 30 s.
    class Tunnel
      WAIT_SECS = 30
      POLL_SECS = 0.25
      KEPT_LINES = 20
      DEFAULT_PROBE = "https://{hostname}/"

      # `cloudflared tunnel --url http://127.0.0.1:<port> --metrics 127.0.0.1:<free>`.
      def self.spawn(binary, port)
        metrics = "127.0.0.1:#{Cloudflared.free_port}"
        reader, writer = IO.pipe
        group = Supervise::Group.spawn([binary, "tunnel", "--url", "http://127.0.0.1:#{port}", "--metrics", metrics],
          in: File::NULL, out: File::NULL, err: writer)
        writer.close
        new(group, metrics, reader)
      end

      attr_reader :metrics

      def initialize(group, metrics, reader)
        @group = group
        @metrics = metrics
        @lines = []
        @logged_host = nil
        @mutex = Mutex.new
        @reader = Thread.new do
          reader.each_line { |line| keep(line.chomp) }
        rescue IOError
          nil
        end
      end

      def alive? = !@group.done?

      # The last lines cloudflared wrote, for `tunnel_failed`'s reason.
      def tail = @mutex.synchronize { @lines.join("\n") }

      # The quick tunnel's hostname: metrics first, the log line second.
      def hostname(stopped)
        deadline = now + WAIT_SECS
        while now < deadline && alive? && !stopped.call
          host = quicktunnel || @mutex.synchronize { @logged_host }
          return host if host

          sleep POLL_SECS
        end
        nil
      end

      # Whether the probe reached the app through the tunnel: any answer but
      # a `530`, once a second, for at most 30 s.
      def ready?(host, template, stopped)
        url = (template || DEFAULT_PROBE).gsub("{hostname}", host).gsub("{metrics}", metrics)
        deadline = now + WAIT_SECS
        while now < deadline && alive? && !stopped.call
          answer = Cloudflared.probe(url)
          return true if answer && answer.code.to_i != 530

          sleep 1
        end
        false
      end

      def stop = @group.stop(5)

      private

      def now = Process.clock_gettime(Process::CLOCK_MONOTONIC)

      def keep(line)
        @mutex.synchronize do
          @logged_host ||= Cloudflared.hostname_in(line)
          @lines << line
          @lines.shift if @lines.size > KEPT_LINES
        end
      end

      def quicktunnel
        answer = Cloudflared.probe("http://#{metrics}/quicktunnel")
        host = answer && JSON.parse(answer.body.to_s)["hostname"]
        (host.is_a?(String) && !host.empty?) ? host : nil
      rescue JSON::ParserError, TypeError
        nil
      end
    end
  end
end
