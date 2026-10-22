# frozen_string_literal: true

require_relative "../platform/objects"

module Lingara
  module CLI
    module Commands
      # `login`, `logout`, `whoami` (16.10.26ad D3) and the hidden `__tree`.
      module Session
        def self.ci_not_needed = Fail.new(Exit::USAGE, "login_ci_not_needed", Assets.message("login_ci_not_needed"))

        # PKCE loopback by default, `--device` for a machine with no
        # browser. Under CI credentials it is `usage`.
        def self.login(ctx, parsed)
          raise ci_not_needed if ctx.ci_pair

          answer = token_answer(ctx, parsed.flags["device"] == true)
          record = Auth::Tokens.apply(Store::RECORD_KEYS.to_h { |k| [k, ""] }, Auth::Tokens.of(answer), ctx.http.clock.now)
          # The new token, for this one read: whose is it?
          ctx.app.session = Auth::Session::State.new(record: record.dup, access: record["access_token"], scopes: record["scopes"])
          me = ctx.fixed("developer.me")
          record["developer_id"] = Objects.str(me["developer_id"])
          record["email"] = Objects.str(me["email"])
          ctx.save_record(record)
          ctx.out.note(Assets.message("login_signed_in", email: record["email"]))
          ctx.out.nothing
        end

        # The token response, from the device flow or the browser's.
        def self.token_answer(ctx, device)
          if device
            require_relative "../auth/device"
            Auth::Device.login(ctx)
          else
            require_relative "../auth/pkce"
            Auth::Pkce.login(ctx)
          end
        end

        # Revokes the refresh token (RFC 7009) and forgets the profile. A
        # revoke that fails, the network being down say, still forgets it
        # and warns.
        def self.logout(ctx, _parsed)
          raise ci_not_needed if ctx.ci_pair

          record = ctx.app.stores.load(ctx.profile)
          raise Fail.auth_required("not signed in: nothing to sign out of") if record.nil?

          why = revoke(ctx, record)
          ctx.out.note("warning: the refresh token could not be revoked (#{why}); forgetting it here anyway") if why
          ctx.app.stores.delete(ctx.profile)
          ctx.app.session = nil
          ctx.out.nothing
        end

        def self.revoke(ctx, record)
          pairs = [["token", record["refresh_token"]], %w[token_type_hint refresh_token], ["client_id", ctx.app.endpoints["client_id"]]]
          reply = ctx.http.send_request(Http::Request.new(route: Routes.fixed("oauth.revoke"), body: {form: pairs}), {bearer: record["access_token"]})
          (200..299).cover?(reply.status) ? nil : "HTTP #{reply.status}"
        rescue Fail => e
          e.message
        end

        # `EMAIL  DEVELOPER  SCOPES  PROFILE`, the scopes from the token response.
        def self.whoami(ctx, _parsed)
          me = ctx.fixed("developer.me")
          return ctx.out.json_value(me) if ctx.out.json

          row = [Output.cell(me["email"]), Output.cell(me["developer_id"]), ctx.app.session&.scopes.to_s, ctx.profile]
          ctx.out.print(Output.render(%w[EMAIL DEVELOPER SCOPES PROFILE], [row]))
        end

        # The registered tree as JSON, for `make check-tree-ruby`.
        def self.tree(ctx, _parsed)
          require_relative "../tree"
          ctx.out.json_value(Tree.tree)
        end
      end
    end
  end
end
