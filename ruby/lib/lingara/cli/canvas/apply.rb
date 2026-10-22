# frozen_string_literal: true

require "tmpdir"
require "fileutils"
require_relative "argv"
require_relative "shell"
require_relative "execute"
require_relative "../platform/objects"

module Lingara
  module CLI
    module Canvas
      # `canvas apply <id>`, 16.10.26ad D7 steps 1–3, and the two flags that
      # stop there: `--dry-run` prints the verdicts, `--print-commands` the
      # argvs. Neither writes. Steps 4–7 are `Execute`'s.
      module Apply
        # What one apply works over: the draft's version, its kept record,
        # the live lists and the plan.
        Planned = Struct.new(:canvas_id, :draft_version, :kept, :live, :ops)

        VERDICT_COLUMNS = [%w[HANDLE handle], %w[KIND kind], %w[OP op], %w[VERDICT verdict], %w[CODE code]].freeze

        def self.refused(message) = Fail.new(Exit::REFUSED, "canvas_apply_refused", message)

        def self.get_canvas(ctx, id) = ctx.fixed("canvas.get", vars: {"id" => id})

        # The active record overlaid by the draft's, kept to the draft's nodes.
        def self.recorded(canvas, handles)
          all = {}
          %w[active draft].each do |side|
            objects = (canvas[side].is_a?(Hash) && canvas[side]["objects"].is_a?(Hash)) ? canvas[side]["objects"] : {}
            objects.each { |h, v| all[h] = v["id"] if v.is_a?(Hash) && v["id"].is_a?(String) }
          end
          all.slice(*handles)
        end

        def self.refusal_fail(r)
          case r.why
          when :ephemeral then Fail.from_message(Exit::RENDER_URL_EPHEMERAL, "render_url_ephemeral", file: "node #{r.handle}'s manifest")
          when :script then refused("node #{r.handle} runs a script; apply this canvas in the browser")
          when :fields then refused("node #{r.handle}'s fields are not one the builder can apply: #{r.message}")
          when :no_client then refused("node #{r.handle} needs exactly one client node upstream")
          else refused("the canvas's edges form a cycle")
          end
        end

        # D7 steps 1–2: the canvas and the three lists, every page, then the plan.
        def self.plan(ctx, id)
          canvas = get_canvas(ctx, id)
          draft = canvas["draft"]
          raise refused("nothing to apply: save a draft with `lingara canvas draft`") unless draft.is_a?(Hash)

          live = Plan::Live.new(*[Objects::OAUTH_CLIENT, Objects::WEBHOOK_OUTBOUND, Objects::APP].map { |k| Objects.list(ctx, k).map { |r| Plan.row_of(r) } })
          graph = Definition.read(draft["definition"])
          kept = Plan.still_live(graph, recorded(canvas, graph.nodes.map(&:id)), live)
          ops = Plan.build(graph, kept)
          Planned.new(id, draft["draft_version"].is_a?(Integer) ? draft["draft_version"] : 0, kept, live, ops)
        rescue Plan::Refusal => e
          raise refusal_fail(e)
        end

        def self.stop_fail(stop)
          return refused("node #{stop.replace}: unexpected `would_replace` from the server; nothing was applied") if stop.replace

          fail = refused("the dry-run refused #{stop.refused.size} op(s); nothing was applied")
          stop.refused.each { |(h, code, error)| fail.hint("#{h}: #{error} (#{code})") }
          fail
        end

        # Each app op's manifest, as the file its `--manifest` names.
        def self.write_manifests(ops)
          ops.select { |o| o["kind"] == "app" }.to_h do |op|
            path = File.join(Dir.tmpdir, "lingara-#{Process.pid}-#{op["handle"]}.manifest.json")
            File.write(path, JSON.generate(op.dig("body", "manifest")))
            [op["handle"], path]
          end
        end

        # The matryoshka made visible: what apply would run, as shell lines.
        def self.print_commands(ctx, steps, manifests)
          lines = steps.reject { |s| s.argv.empty? }.map { |s| Shell.line(s.argv, manifests) }
          ctx.out.json ? ctx.out.json_value(lines) : lines.each { |line| ctx.out.print("#{line}\n") }
        end

        def self.dry_run(ctx, answer, verdicts)
          ctx.out.json ? ctx.out.json_value(answer) : ctx.out.list(verdicts, VERDICT_COLUMNS)
          raise refused("the dry-run refused at least one op") if answer["ok"] == false
        end

        def self.apply(ctx, id, dry_run:, print_commands:)
          planned = plan(ctx, id)
          answer = ctx.fixed("canvas.dry_run", body: {json: {"ops" => planned.ops}})
          verdicts = Array(answer["verdicts"])
          return dry_run(ctx, answer, verdicts) if dry_run

          steps = begin
            Argv.steps(planned.ops, verdicts, planned.live, planned.kept)
          rescue Argv::Stop => e
            raise stop_fail(e)
          end
          manifests = write_manifests(planned.ops)
          return print_commands(ctx, steps, manifests) if print_commands

          begin
            Execute.run(ctx, planned, steps, manifests)
          ensure
            manifests.each_value { |path| FileUtils.rm_f(path) }
          end
        end
      end
    end
  end
end
