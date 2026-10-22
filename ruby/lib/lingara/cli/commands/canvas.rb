# frozen_string_literal: true

require_relative "../platform/deletes"

module Lingara
  module CLI
    module Commands
      # `canvas …` (16.10.26ad D1, D5, D7). The builder's routes are
      # `/v1/canvases`; a delete goes through `/platform/objects` with the
      # typed email, as for every other kind.
      module Canvas
        COLUMNS = [%w[ID id], %w[NAME name], %w[DRAFT has_draft], %w[ACTIVE active_revision], %w[UPDATED updated_at]].freeze

        def self.definition_file(path)
          JSON.parse(File.read(path, encoding: "UTF-8"))
        rescue SystemCallError, JSON::ParserError => e
          raise Fail.usage("#{path}: #{e.message}")
        end

        # A `/v1/canvases` 404 while the builder flag is off (16.10.26x D3),
        # where no id could be wrong.
        def self.builder_hint
          yield
        rescue Fail => e
          e.hint("the canvas builder may not be enabled for this account") if e.exit_code == Exit::NOT_FOUND
          raise
        end

        # `createCanvas` answers the canvas itself, so there is no final read.
        def self.create(ctx, parsed)
          file = Commands.opt(parsed.flags["file"])
          definition = file ? definition_file(file) : {"schema" => 2, "nodes" => [], "edges" => []}
          name = Commands.opt(parsed.flags["name"])
          body = {json: (name ? {"name" => name} : {}).merge("definition" => definition)}
          ctx.out.value(builder_hint { ctx.fixed("canvas.create", body: body) })
        end

        def self.list(ctx, _parsed)
          items = builder_hint { Objects.pages { |cursor| ctx.fixed("canvas.list", query: cursor ? [["cursor", cursor]] : []) } }
          ctx.out.list(items, COLUMNS)
        end

        def self.get(ctx, parsed) = ctx.out.value(ctx.fixed("canvas.get", vars: {"id" => parsed.args["id"]}))

        def self.update(ctx, parsed)
          body = {json: {"name" => parsed.flags["name"]}}
          ctx.out.value(ctx.fixed("canvas.rename", vars: {"id" => parsed.args["id"]}, body: body))
        end

        # Saves the definition at the draft's `draft_version` (`0`: a new
        # draft). It creates nothing else; that is `apply`'s alone (B7).
        def self.draft(ctx, parsed)
          id = parsed.args["id"]
          definition = definition_file(parsed.flags["file"])
          draft = ctx.fixed("canvas.get", vars: {"id" => id})["draft"]
          version = (draft.is_a?(Hash) && draft["draft_version"].is_a?(Integer)) ? draft["draft_version"] : 0
          body = {json: {"definition" => definition, "draft_version" => version}}
          ctx.out.value(ctx.fixed("canvas.put_draft", vars: {"id" => id}, body: body))
        end

        def self.apply(ctx, parsed)
          require_relative "../canvas/apply"
          CLI::Canvas::Apply.apply(ctx, parsed.args["id"], dry_run: parsed.flags["dry-run"] == true,
            print_commands: parsed.flags["print-commands"] == true)
        end

        # `canvas.lookup` turns the builder's id into the platform object's
        # (16.10.26v §8); the prompt reads that object.
        def self.lookup(ctx, canvas_id)
          page = ctx.fixed("canvas.lookup", query: [%w[kind canvas], ["runtime_id", canvas_id]])
          found = Objects.str(Array(page["items"]).first&.dig("id"))
          raise Fail.new(Exit::NOT_FOUND, "object_not_found", "no canvas #{canvas_id}") if found.empty?

          found
        end

        def self.delete(ctx, parsed)
          i = Deletes.input(parsed)
          platform_id = nil
          email = Confirm.destructive(ctx, yes: i[:yes], confirm: i[:confirm]) do
            platform_id = lookup(ctx, i[:id])
            obj = ctx.call(Http::Request.new(route: Routes.object_named("canvas.object_get", "get"), vars: {"id" => platform_id}))
            Deletes.prompt_for(Objects::CANVAS, obj, i[:id], i[:version], ->(_) { Assets.message("consequence_canvas") })
          end
          Objects.destroy(ctx, Objects::CANVAS, platform_id || lookup(ctx, i[:id]), i[:version], email)
          ctx.out.nothing
        end
      end
    end
  end
end
