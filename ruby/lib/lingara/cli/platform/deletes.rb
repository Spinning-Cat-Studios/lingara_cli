# frozen_string_literal: true

require_relative "objects"

module Lingara
  module CLI
    # Every object `delete` (16.10.26ad D5): the ritual over one
    # `<word>.get` for the prompt, then the destructive request with the
    # canonical email.
    module Deletes
      # `{client}` is the read's `parent_id`, the client's platform object
      # id, never the public `client_id`.
      def self.parent(obj)
        id = Objects.str(obj["parent_id"])
        id.empty? ? "-" : id
      end

      # The y/n question's facts; `consequence` is the kind's line from the read.
      def self.prompt_for(kind, obj, id, version, consequence)
        name = Objects.str(obj["name"])
        line = version.nil? ? consequence.call(obj) : Assets.message("consequence_version", semver: version)
        Confirm::Prompt.new(kind: kind.label, name: name.empty? ? id : name, id: id, consequence: line,
          pinned_by: obj["pinned_by"].is_a?(Integer) ? obj["pinned_by"] : 0)
      end

      # `parsed`'s id, `--version` and confirm flags, as every delete reads them.
      def self.input(parsed)
        f = parsed.flags
        {id: parsed.args["id"], version: Commands.opt(f["version"]), yes: f["yes"] == true, confirm: Commands.opt(f["confirm"])}
      end

      def self.delete_object(ctx, kind, parsed, &consequence)
        i = input(parsed)
        email = Confirm.destructive(ctx, yes: i[:yes], confirm: i[:confirm]) do
          prompt_for(kind, Objects.get(ctx, kind, i[:id]), i[:id], i[:version], consequence)
        end
        Objects.destroy(ctx, kind, i[:id], i[:version], email)
        ctx.out.nothing
      end
    end
  end
end
