# frozen_string_literal: true

module Lingara
  module CLI
    # The `/platform/objects` family every kind shares (16.10.26v §8;
    # 16.10.26ad D2): list every page, read, rename, create and publish, the
    # body update that respects the one editable version, delete with the
    # typed email, and the one-time secret mint.
    module Objects
      # A CLI word, the 16.10.26v kind behind it, and `{kind}` in `confirm_yes_no`.
      Kind = Struct.new(:word, :kind, :label)

      OAUTH_CLIENT = Kind.new("oauth_client", "oauth_client", "OAuth client")
      APP = Kind.new("app", "app", "app")
      EMBED = Kind.new("embed", "embed", "embed")
      WEBHOOK_INBOUND = Kind.new("webhook_inbound", "webhook_in", "inbound webhook")
      WEBHOOK_OUTBOUND = Kind.new("webhook_outbound", "webhook_out", "outbound webhook")
      CANVAS = Kind.new("canvas", "canvas", "canvas")

      def self.str(value) = value.is_a?(String) ? value : ""

      def self.send_op(ctx, kind, op, vars, body = nil)
        ctx.call(Http::Request.new(route: Routes.object(kind.word, op), vars: vars, body: body))
      end

      # Every item of every page, followed by `next_cursor` (B1). The block
      # fetches one page given the cursor (nil first).
      def self.pages
        items = []
        cursor = nil
        loop do
          page = yield cursor
          items.concat(Array(page["items"]))
          return items unless page["next_cursor"].is_a?(String)

          cursor = page["next_cursor"]
        end
      end

      def self.list(ctx, kind)
        route = Routes.object(kind.word, "list")
        pages do |cursor|
          query = [["kind", kind.kind]]
          query << ["cursor", cursor] if cursor
          ctx.call(Http::Request.new(route: route, query: query))
        end
      end

      def self.get(ctx, kind, id) = send_op(ctx, kind, "get", {"id" => id})
      def self.rename(ctx, kind, id, name) = send_op(ctx, kind, "rename", {"id" => id}, {json: {"name" => name}})
      def self.version_op(ctx, kind, op, id, semver) = send_op(ctx, kind, op, {"id" => id, "semver" => semver})
      def self.secret_create(ctx, kind, id) = send_op(ctx, kind, "secret_create", {"id" => id})

      # The object and its first draft: `{kind, name, parent_id?, body}`.
      def self.create(ctx, kind, name, parent, body)
        request = {"kind" => kind.kind, "name" => name, "body" => body}
        request["parent_id"] = parent if parent
        send_op(ctx, kind, "create", {}, {json: request})
      end

      # A private object skips review, so a version is published at once
      # (16.10.26v §4).
      def self.publish_if_private(ctx, kind, obj, semver)
        return nil if obj["visibility"] == "public" || semver.empty?

        version_op(ctx, kind, "publish", str(obj["id"]), semver)
      end

      # The destructive request: the canonical email in its body (B3.6).
      def self.destroy(ctx, kind, id, version, email)
        body = {json: {"confirm" => email}}
        return send_op(ctx, kind, "delete", {"id" => id}, body) if version.nil?

        send_op(ctx, kind, "delete_version", {"id" => id, "semver" => version}, body)
      end

      def self.print_list(ctx, kind, columns) = ctx.out.list(list(ctx, kind), columns)
      def self.print_get(ctx, kind, id) = ctx.out.value(get(ctx, kind, id))
    end
  end
end

require_relative "body_update"
