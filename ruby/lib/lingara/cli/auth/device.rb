# frozen_string_literal: true

module Lingara
  module CLI
    module Auth
      # `login --device` (RFC 8628, 16.10.26u §7): for SSH sessions and
      # machines without a browser. `authorization_pending` keeps polling,
      # `slow_down` adds 5 s, `expired_token` and `access_denied` are
      # `auth_required`, and any answer carrying B4's `lingara_code` is
      # `platform_disabled` (16.10.26ad D8).
      module Device
        GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        WAITING = %w[authorization_pending slow_down].freeze

        # The token response once the developer has approved the code.
        def self.login(ctx)
          client_id = ctx.app.endpoints["client_id"]
          start = ctx.token_post("oauth.device_authorization", [["client_id", client_id], ["scope", Tokens::SCOPES]])
          ctx.out.note(Assets.message("login_device_code", verification_uri: start["verification_uri"].to_s, user_code: start["user_code"].to_s))
          ctx.out.note(start["verification_uri_complete"]) if start["verification_uri_complete"].is_a?(String)
          interval = start["interval"].is_a?(Numeric) ? start["interval"] : 5
          expires_in = start["expires_in"].is_a?(Numeric) ? start["expires_in"] : 900
          poll(ctx, start["device_code"].to_s, interval, expires_in)
        end

        # What one refused poll means: keep polling, poll slower, or stop
        # with a refusal; nil when it is none of RFC 8628's.
        def self.verdict(error, live)
          return error.to_sym if WAITING.include?(error) && live
          if WAITING.include?(error) || error == "expired_token"
            return Fail.auth_required("the code expired before it was approved; run `lingara login --device` again")
          end

          (error == "access_denied") ? Fail.new(Exit::AUTH_REQUIRED, "access_denied", "sign-in was declined") : nil
        end

        def self.poll(ctx, device_code, interval, expires_in)
          route = Routes.fixed("oauth.token")
          pairs = [["grant_type", GRANT], ["device_code", device_code], ["client_id", ctx.app.endpoints["client_id"]]]
          waited = 0
          loop do
            ctx.http.clock.sleep(interval)
            waited += interval
            reply = ctx.http.send_request(Http::Request.new(route: route, body: {form: pairs}), nil)
            return Http.parse(reply.body) || {} if (200..299).cover?(reply.status)

            interval = next_interval(route, reply, interval, waited < expires_in)
          end
        end

        def self.next_interval(route, reply, interval, live)
          body = Http.parse(reply.body) || {}
          # B4's `lingara_code` outranks any RFC 8628 error beside it (D8).
          v = verdict(body.key?("lingara_code") ? nil : body["error"], live)
          raise v if v.is_a?(Fail)
          raise Classify.refusal(route, reply, false) if v.nil?

          (v == :slow_down) ? interval + 5 : interval
        end
      end
    end
  end
end
