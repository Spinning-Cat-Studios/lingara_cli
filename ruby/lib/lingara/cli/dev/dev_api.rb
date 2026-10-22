# frozen_string_literal: true

module Lingara
  module CLI
    # The five `app_dev.*` routes (Backend 16.10.26v §12; 16.10.26ae R1–R3),
    # one function each, plus the read-then-patch every writer of the dev
    # version uses: a `409 version_conflict` re-reads once and retries, and
    # a second one is the server's `refused`.
    module DevApi
      # What an open answers: the session, and the secrets it minted, which
      # live only in this process's memory and the app's environment (D5).
      Session = Struct.new(:id, :lease_secs, :expires_at, :app_secret, :client_secret)

      # `GET …/dev`: the manifest, `render_url`, `draft_version`, the session.
      def self.get(ctx, id) = ctx.fixed("app_dev.get", vars: {"id" => id})
      def self.patch(ctx, id, body) = ctx.fixed("app_dev.patch", vars: {"id" => id}, body: {json: body})

      # Reads `draft_version`, then patches the block's body for it; one
      # re-read and retry on `version_conflict`.
      def self.patch_with_retry(ctx, id, &build)
        draft = get(ctx, id)["draft_version"]
        begin
          patch(ctx, id, build.call(draft))
        rescue Fail => e
          raise unless e.code == "version_conflict"

          patch(ctx, id, build.call(get(ctx, id)["draft_version"]))
        end
      end

      def self.secret_of(value) = (value.is_a?(Hash) && value["secret"].is_a?(String)) ? value["secret"] : nil

      # `POST …/dev/session {take_over, credentials}`.
      def self.open(ctx, id, take_over, client)
        body = {"take_over" => take_over}
        body["credentials"] = ["client"] if client
        answer = ctx.fixed("app_dev.session_open", vars: {"id" => id}, body: {json: body})
        lease = (answer["lease_secs"].is_a?(Numeric) && answer["lease_secs"].positive?) ? answer["lease_secs"] : 300
        Session.new(id: Objects.str(answer["session_id"]), lease_secs: lease, expires_at: Objects.str(answer["expires_at"]),
          app_secret: secret_of(answer["app_secret"]) || "", client_secret: secret_of(answer["client_secret"]))
      end

      def self.renew(ctx, id, session_id) = ctx.fixed("app_dev.session_renew", vars: {"id" => id, "session_id" => session_id})

      # `DELETE …/dev/session/{sid}`: parks, keeps `render_url`, revokes the
      # session's credentials.
      def self.finish(ctx, id, session_id) = ctx.fixed("app_dev.session_end", vars: {"id" => id, "session_id" => session_id})
    end
  end
end
