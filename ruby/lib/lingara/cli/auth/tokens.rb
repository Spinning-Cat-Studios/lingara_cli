# frozen_string_literal: true

require "time"

module Lingara
  module CLI
    module Auth
      # A token response, and the profile record it becomes (ADR 16.10.26ad D4).
      module Tokens
        # The four scopes, in one grant (16.10.26u §3).
        SCOPES = "platform:read platform:write canvas:read canvas:write"

        # RFC 6749 §5.1, read leniently: a missing `expires_in` is an hour.
        def self.of(value)
          text = ->(v) { v.is_a?(String) ? v : nil }
          expires = value["expires_in"]
          {access: text.call(value["access_token"]) || "", refresh: text.call(value["refresh_token"]),
           scope: text.call(value["scope"]), expires_in: expires.is_a?(Numeric) ? expires.to_i : 3600}
        end

        # `YYYY-MM-DDTHH:MM:SSZ`.
        def self.rfc3339(secs) = Time.at(secs).utc.strftime("%Y-%m-%dT%H:%M:%SZ")

        # Seconds since the epoch of an RFC 3339 stamp; nil when unreadable.
        def self.epoch(stamp)
          Time.iso8601(stamp.to_s).to_i
        rescue ArgumentError
          nil
        end

        # The refresh token rotates when one was sent, and `scopes` is
        # rewritten whenever the answer carried `scope`.
        def self.apply(record, tokens, now)
          record["access_token"] = tokens[:access]
          record["refresh_token"] = tokens[:refresh] if tokens[:refresh]
          record["scopes"] = tokens[:scope] if tokens[:scope]
          record["access_expires_at"] = rfc3339(now + tokens[:expires_in])
          record
        end
      end

      # The one line of advice a refusal's code earns (ADR 16.10.26ad D3,
      # D5, D6). Keyed by code, so a reworded server sentence changes nothing.
      module Hints
        PORTAL_LINE = "create your developer account at https://platform.getlingara.com"

        BY_CODE = {
          "developer_not_found" => PORTAL_LINE,
          "developer_account_required" => PORTAL_LINE,
          "developer_terms_outdated" => "accept the updated developer terms at https://platform.getlingara.com",
          "delete_confirmation_mismatch" => "the platform holds a different email for you; run `lingara whoami`",
          "invalid_scope" => "recreate this client with `lingara client create --ci`"
        }.freeze

        # `fail` with its code's line, once.
        def self.advise(fail)
          line = BY_CODE[fail.code]
          line ? fail.hint(line) : fail
        end
      end
    end
  end
end
