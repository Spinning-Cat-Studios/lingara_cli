# frozen_string_literal: true

require "securerandom"
require "digest"
require "socket"
require "uri"

module Lingara
  module CLI
    module Auth
      # `login`'s default: PKCE over a loopback redirect (RFC 8252,
      # 16.10.26u §6). The kit listens on `127.0.0.1:0`, opens the portal's
      # authorize page, and accepts exactly one callback whose `state`
      # matches and whose `iss` is the portal base (RFC 9207). The listener
      # gives up after 5 minutes. A raw TCPServer answers the one GET, since
      # Ruby 3.3 ships no HTTP server.
      module Pkce
        LISTEN_FOR_SECS = 300
        CLOSE_TAB = "<!doctype html><title>Lingara</title><p>You can close this tab.</p>"

        def self.random_text = SecureRandom.urlsafe_base64(32, false)
        def self.challenge(verifier) = [Digest::SHA256.digest(verifier)].pack("m0").tr("+/", "-_").delete("=")

        # The token response for the code the browser brought back.
        def self.login(ctx)
          verifier = random_text
          state = random_text
          issuer = ctx.http.bases[:portal].sub(%r{/+\z}, "")
          server = TCPServer.new("127.0.0.1", 0)
          redirect = "http://127.0.0.1:#{server.addr[1]}/callback"
          code = await_code(ctx, server, authorize_url(ctx, redirect, verifier, state), state, issuer)
          pairs = [%w[grant_type authorization_code], ["code", code], ["redirect_uri", redirect],
            ["code_verifier", verifier], ["client_id", ctx.app.endpoints["client_id"]]]
          ctx.token_post("oauth.token", pairs)
        ensure
          server&.close
        end

        def self.authorize_url(ctx, redirect, verifier, state)
          query = [%w[response_type code], ["client_id", ctx.app.endpoints["client_id"]], ["redirect_uri", redirect],
            ["code_challenge", challenge(verifier)], %w[code_challenge_method S256], ["state", state], ["scope", Tokens::SCOPES]]
          ctx.http.url(Routes.fixed("oauth.authorize"), {}, query)
        end

        def self.await_code(ctx, server, url, state, issuer)
          ctx.out.note(url)
          ctx.out.note(Assets.message("login_browser_wait"))
          ctx.app.browser(url)
          deadline = Time.now + LISTEN_FOR_SECS
          loop do
            params = accept_one(server, deadline, state, issuer)
            next if params.nil?
            raise Fail.new(Exit::AUTH_REQUIRED, "access_denied", "sign-in was declined") unless params["code"]

            return params["code"]
          end
        end

        # One connection: the callback's query when it is ours, else nil.
        def self.accept_one(server, deadline, state, issuer)
          left = deadline - Time.now
          ready = left.positive? && server.wait_readable(left)
          raise Fail.auth_required("no sign-in arrived within 5 minutes; run `lingara login` again") unless ready

          client = server.accept
          params = callback_params(request_line(client))
          ours = params && params["state"] == state && params["iss"].to_s.sub(%r{/+\z}, "") == issuer
          respond(client, ours ? "200 OK" : "400 Bad Request")
          ours ? params : nil
        end

        # The request line; the headers are read and dropped, so closing the
        # socket never resets a browser still sending them.
        def self.request_line(client)
          line = client.gets.to_s
          while (header = client.gets) && !header.strip.empty?
          end
          line
        end

        def self.callback_params(request_line)
          target = request_line.split(" ")[1].to_s
          path, query = target.split("?", 2)
          return nil unless path == "/callback"

          URI.decode_www_form(query.to_s).to_h
        rescue ArgumentError
          nil
        end

        def self.respond(client, status)
          client.write("HTTP/1.1 #{status}\r\ncontent-type: text/html\r\nconnection: close\r\n" \
            "content-length: #{CLOSE_TAB.bytesize}\r\n\r\n#{CLOSE_TAB}")
        ensure
          client.close
        end
      end
    end
  end
end
