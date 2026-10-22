# frozen_string_literal: true

require_relative "objects"

module Lingara
  module CLI
    # A create that may take several calls (16.10.26ad D1, D2). It ends with
    # one `<word>.get`, printed with any secret it minted. Failing part-way,
    # it still reveals that secret on stdout and names every object it made
    # on stderr, then exits with the failing call's code. Nothing is rolled
    # back.
    class Creation
      SHOWN_ONCE = "The secret is shown once: store it now. It cannot be read again."

      attr_reader :made, :secret

      def initialize
        @made = []
        @secret = nil
      end

      # One object: created, named as made, then published when private.
      def object(ctx, kind, name, parent, body)
        created = Objects.create(ctx, kind, name, parent, body)
        @made << [kind.word, Objects.str(created["id"])]
        version = created["version"].is_a?(Hash) ? created["version"] : {}
        Objects.publish_if_private(ctx, kind, created, Objects.str(version["semver"]))
        created
      end

      # The one-time secret. `409 object_projection_pending`: the client is
      # still publishing (16.10.26v §5).
      def mint(ctx, kind, id)
        minted = Objects.secret_create(ctx, kind, id)
        @secret = {"id" => minted["secret_id"], "value" => minted["secret"]}
      rescue Fail => e
        e.hint("the client is still publishing; run `lingara client secret create #{id}` in a moment") if e.code == "object_projection_pending"
        raise
      end

      # Runs the block (which returns `[kind, id]`), then the final read and
      # its printing, or the failure with what was made.
      def run(ctx)
        begin
          kind, id = yield
          obj = Objects.get(ctx, kind, id)
        rescue Fail => e
          print(ctx, nil)
          e.made = made
          raise
        end
        print(ctx, obj)
        obj
      end

      private

      def print(ctx, obj)
        return if obj.nil? && secret.nil?

        if ctx.out.json
          ctx.out.json_value((obj || {}).merge(secret ? {"secret" => secret} : {}))
        else
          rows = obj.nil? ? [] : Output.fields(obj)
          rows << ["SECRET", Output.cell(secret["value"])] if secret
          ctx.out.print(Output.render(%w[FIELD VALUE], rows))
        end
        ctx.out.note(SHOWN_ONCE) if secret
      end
    end
  end
end
