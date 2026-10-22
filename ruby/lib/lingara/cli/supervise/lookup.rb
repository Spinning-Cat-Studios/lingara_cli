# frozen_string_literal: true

require "socket"

module Lingara
  module CLI
    # Finding cloudflared, and the two small network probes a tunnel needs
    # (16.10.26ae D8). **Lookup**: `LINGARA_CLOUDFLARED` and then only that
    # path, else `cloudflared` on `PATH`, refused below `tools.toml`'s
    # minimum; both checks run offline, before the first request (D7 step 2).
    module Cloudflared
      # `tool_missing` with D8's hint, the exit row's own message.
      def self.missing = Fail.from_message(Exit::TOOL_MISSING, "tool_missing_cloudflared")

      def self.on_path(env)
        exe = Env.windows? ? "cloudflared.exe" : "cloudflared"
        env["PATH"].to_s.split(File::PATH_SEPARATOR).map { |d| File.join(d, exe) }.find { |p| File.file?(p) }
      end

      # The first `a.b.c` on `cloudflared --version`'s first line.
      def self.version_of(binary)
        out = IO.popen([binary, "--version", {err: File::NULL, in: File::NULL}], &:read).to_s
        word = out.lines.first.to_s.split.find { |w| w.match?(/\A\d+(\.\d+)+\z/) }
        word&.split(".")&.map(&:to_i)
      rescue SystemCallError
        nil
      end

      def self.at_least?(version, min)
        [version.size, min.size].max.times do |i|
          a = version[i] || 0
          b = min[i] || 0
          return a > b unless a == b
        end
        true
      end

      # Lookup and version check, offline (D7 step 2).
      def self.find(env)
        explicit = env["LINGARA_CLOUDFLARED"]
        binary = if explicit
          File.file?(explicit) ? explicit : nil
        else
          on_path(env)
        end
        raise missing if binary.nil?

        version = version_of(binary)
        raise missing if version.nil? || !at_least?(version, Assets.cloudflared_min_version)

        binary
      end

      # A free loopback port, for cloudflared's metrics server.
      def self.free_port
        server = TCPServer.new("127.0.0.1", 0)
        server.addr[1]
      ensure
        server&.close
      end

      # The `https://…trycloudflare.com` hostname on one log line, if any.
      def self.hostname_in(line)
        host = line[%r{https://([A-Za-z0-9.-]+)}, 1]
        host&.end_with?(".trycloudflare.com") ? host : nil
      end

      # `GET url`, answered or nil, within 2 s.
      def self.probe(url)
        require "net/http"
        uri = URI(url)
        Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == "https", open_timeout: 2, read_timeout: 2) do |http|
          http.request(Net::HTTP::Get.new(uri.request_uri))
        end
      rescue
        nil
      end
    end
  end
end
