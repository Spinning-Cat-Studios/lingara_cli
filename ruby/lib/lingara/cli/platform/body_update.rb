# frozen_string_literal: true

module Lingara
  module CLI
    module Objects
      # D2's body update: overlay the flags on the one editable version
      # (`version_put` at its `draft_version`), else on the serving (or
      # highest) version as a new one (`version_create`). Always a whole
      # body. The block edits a copy of the body.
      def self.body_update(ctx, kind, id, &overlay)
        current = get(ctx, kind, id)
        versions = Array(current["versions"])
        editable = versions.find { |v| %w[draft changes_requested].include?(v["state"]) }
        answer = editable ? put_editable(ctx, kind, id, str(editable["semver"]), overlay) : new_version(ctx, kind, id, current, overlay)
        publish_if_private(ctx, kind, current, str(answer["semver"])) || answer
      end

      def self.put_editable(ctx, kind, id, semver, overlay)
        version = version_op(ctx, kind, "version_get", id, semver)
        edit = {"body" => overlaid(version["body"], overlay), "draft_version" => version["draft_version"]}
        send_op(ctx, kind, "version_put", {"id" => id, "semver" => semver}, {json: edit})
      end

      def self.new_version(ctx, kind, id, current, overlay)
        base = str(current["serving_version"])
        base = str(Array(current["versions"]).first&.dig("semver")) if base.empty?
        start = base.empty? ? {} : version_op(ctx, kind, "version_get", id, base)["body"]
        send_op(ctx, kind, "version_create", {"id" => id}, {json: {"body" => overlaid(start, overlay)}})
      end

      def self.overlaid(body, overlay)
        out = body.is_a?(Hash) ? body.dup : {}
        overlay.call(out)
        out
      end
    end
  end
end
