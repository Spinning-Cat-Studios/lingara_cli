# frozen_string_literal: true

require "json"

module Lingara
  module CLI
    # The one response classifier (ADR 16.10.26ad D8; contract B2). Its eight
    # rules are **data**: an ordered list of `[predicate, verdict]` entries,
    # the first match winning and rule 8 the catch-all. 16.10.26ae D10's rule
    # 4a is one more entry, after rule 4.
    module Classify
      # `conformance/routes.toml`'s two codes, as this kit keys them.
      PLATFORM_DISABLED_CODE = "platform_disabled"
      PLATFORM_ACCESS_CODE = "platform_access_not_enabled"

      # A non-2xx answer, with what the rules read about the request behind
      # it: `bearer` is whether the request carried `Authorization: Bearer`.
      Answer = Struct.new(:route, :token_route, :platform_route, :bearer, :status, :body, :retry_after) do
        def object
          @object ||= begin
            v = JSON.parse(body.to_s)
            v.is_a?(Hash) ? v : {}
          rescue JSON::ParserError
            {}
          end
        end

        def member(key)
          v = object[key]
          v.is_a?(String) ? v : nil
        end

        def coded? = !member("code").nil? && !member("error").nil?
        def oauth_envelope? = !member("error").nil? && member("code").nil?
      end

      # Rule 4a (16.10.26ae D10): a dev-session `409` is a row of its own,
      # keyed on `(route, body code)`, because a script branches on "someone
      # else is developing this app" where it would not on any other refusal.
      DEV_SESSION_CODES = [
        ["app_dev.session_open", "dev_session_held", Exit::DEV_SESSION_HELD],
        ["app_dev.patch", "dev_session_held", Exit::DEV_SESSION_HELD],
        ["app_dev.session_renew", "dev_session_not_live", Exit::DEV_SESSION_LOST],
        ["app_dev.session_renew", "dev_session_expired", Exit::DEV_SESSION_LOST]
      ].freeze

      def self.platform_disabled?(a)
        code = a.member("code")
        dark = a.status == 404 && a.platform_route && code == PLATFORM_DISABLED_CODE
        door = a.status == 403 && code == PLATFORM_ACCESS_CODE
        token = a.token_route && a.member("lingara_code") == PLATFORM_ACCESS_CODE
        dark || door || token
      end

      def self.dev_session_row(a)
        return nil unless a.status == 409

        DEV_SESSION_CODES.find { |(route, code)| route == a.route && code == a.member("code") }&.last
      end

      # Rule 4's one exception is keyed on route **and** code.
      def self.coded_not_found(a)
        (a.route == "developer.me" && a.member("code") == "developer_not_found") ? Exit::REFUSED : Exit::NOT_FOUND
      end

      RULES = [
        [->(a) { platform_disabled?(a) }, ->(_) { Exit::PLATFORM_DISABLED }],
        [->(a) { a.status == 401 && a.bearer && !a.token_route }, ->(_) { :refresh }],
        [->(a) { a.token_route && (400..499).cover?(a.status) && a.status != 429 && a.oauth_envelope? }, ->(_) { Exit::AUTH_REQUIRED }],
        [->(a) { a.status == 404 && a.coded? }, ->(a) { coded_not_found(a) }],
        [->(a) { !dev_session_row(a).nil? }, ->(a) { dev_session_row(a) }],
        [->(a) { a.status == 404 && a.body.to_s.strip.empty? }, ->(_) { Exit::FAILURE }],
        [->(a) { [400, 403, 409, 422].include?(a.status) }, ->(_) { Exit::REFUSED }],
        [->(a) { a.status == 429 || a.status >= 500 }, ->(_) { Exit::UNAVAILABLE }],
        [->(_) { true }, ->(_) { Exit::FAILURE }]
      ].freeze

      # An exit code, or `:refresh` for rule 2.
      def self.classify(answer)
        RULES.find { |(matches, _)| matches.call(answer) }.last.call(answer)
      end

      # The code and text an exit prints: the server's own where it sent them.
      def self.spelled(a, exit_code)
        case exit_code
        when Exit::PLATFORM_DISABLED then [PLATFORM_DISABLED_CODE, Assets.message("platform_disabled")]
        when Exit::FAILURE then ["unexpected_response", "unexpected response from #{a.route}"]
        when Exit::AUTH_REQUIRED
          [a.member("error") || "auth_required", a.member("error_description") || "#{a.route} refused the credentials"]
        else
          fallback = (exit_code == Exit::UNAVAILABLE) ? "unavailable" : "refused"
          [a.member("code") || fallback, a.member("error") || "#{a.route} answered HTTP #{a.status}"]
        end
      end

      # The answer the rules read, from a route and its reply.
      def self.answer(route, reply, bearer)
        retry_after = Integer(reply.headers["retry-after"].to_s, exception: false)
        Answer.new(route: route.name, token_route: route.token?, platform_route: route.platform?,
          bearer: bearer, status: reply.status, body: reply.body, retry_after: retry_after)
      end

      # The refusal for a non-2xx reply that is not rule 2's, with its
      # code's advice line (16.10.26ad D3).
      def self.refusal(route, reply, bearer)
        a = answer(route, reply, bearer)
        verdict = classify(a)
        fail = Auth::Hints.advise(fail_for(a, (verdict == :refresh) ? Exit::AUTH_REQUIRED : verdict))
        (a.member("lingara_code") == "developer_account_required") ? fail.hint(Auth::Hints::PORTAL_LINE) : fail
      end

      # The refusal B2 prints, with the wait a 429 named.
      def self.fail_for(a, exit_code)
        code, text = spelled(a, exit_code)
        wait = (exit_code == Exit::UNAVAILABLE && a.retry_after) ? " (retry after #{a.retry_after} s)" : ""
        fail = Fail.new(exit_code, code, "#{text}#{wait}")
        # A command that re-words a refusal (apps dev's `dev_session_held`)
        # reads the server's other members from here.
        fail.body = a.body
        fail
      end
    end
  end
end
