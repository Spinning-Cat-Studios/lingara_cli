# frozen_string_literal: true

module Lingara
  module CLI
    module Auth
      # Opening and refreshing a run's session (16.10.26ad D3; contract B6
      # rules 1–3): CI client credentials when both are set, else the
      # profile's stored record, else `auth_required` before any request. A
      # stored token within 60 s of expiry refreshes before its first use.
      module Session
        # The bearer and what it was minted for: the CI pair or the stored record.
        State = Struct.new(:ci, :record, :access, :email, :scopes)

        REFRESH_SKEW_SECS = 60

        def self.mint(ctx, pair)
          ctx.token_post("oauth.token", [%w[grant_type client_credentials], ["scope", Tokens::SCOPES]], {basic: pair})
        end

        def self.of(record)
          State.new(record: record, access: record["access_token"], email: record["email"], scopes: record["scopes"])
        end

        def self.open(ctx)
          ci = ctx.ci_pair
          if ci
            t = Tokens.of(mint(ctx, ci))
            return State.new(ci: ci, access: t[:access], scopes: t[:scope] || Tokens::SCOPES)
          end
          record = ctx.app.stores.load(ctx.profile)
          raise Fail.auth_required("not signed in: run `lingara login`") if record.nil?

          ctx.app.session = of(record)
          expires = Tokens.epoch(record["access_expires_at"])
          refresh(ctx) if expires.nil? || expires - ctx.http.clock.now < REFRESH_SKEW_SECS
          ctx.app.session
        end

        # Rule 2's one refresh: the stored refresh token, or a fresh CI mint.
        # A refused refresh never deletes the record (D3), and says "run
        # `lingara login`" unless it is B4's dark signal.
        def self.refresh(ctx)
          session = ctx.app.session
          return session.access = Tokens.of(mint(ctx, session.ci))[:access] if session.ci

          record = session.record.dup
          pairs = [%w[grant_type refresh_token], ["refresh_token", record["refresh_token"]], ["client_id", ctx.app.endpoints["client_id"]]]
          answer = begin
            ctx.token_post("oauth.token", pairs)
          rescue Fail => e
            raise (e.exit_code == Exit::AUTH_REQUIRED) ? e.hint("run `lingara login`") : e
          end
          Tokens.apply(record, Tokens.of(answer), ctx.http.clock.now)
          ctx.save_record(record)
          ctx.app.session = of(record)
        end
      end
    end
  end
end
