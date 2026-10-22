# frozen_string_literal: true

require "json"

module Lingara
  module CLI
    module Canvas
      # 16.10.26ad D7 steps 4–7. Each step's argv is dispatched
      # **in-process** through the app's `run`, `Main`'s own entry point,
      # with `--json`, so no credential crosses a process boundary and no
      # second token exchange happens. Apply records each object's
      # `runtime_id` (a secret's id), never its platform object id, then
      # activates `complete`, or `partial` at the first failure.
      class Execute
        # A `runtime_id` still `null` is re-read once a second, ten times (D7).
        PUBLISH_POLLS = 10

        # The inner verbs' stderr goes nowhere: their refusal comes back as
        # the outcome's `fail`, and apply prints its own.
        NULL_SINK = Object.new.tap { |o| o.define_singleton_method(:write) { |_| nil } }

        attr_reader :recorded

        def self.run(ctx, planned, steps, manifests) = new(ctx, planned, manifests).run(steps)

        def initialize(ctx, planned, manifests)
          @ctx = ctx
          @planned = planned
          @manifests = manifests
          @recorded = planned.kept.dup
          @made = {}
          @minted = {}
          @secrets = []
          @rows = []
        end

        def run(steps)
          steps.each_with_index do |step, done|
            perform(step)
          rescue => e
            fail = e.is_a?(Fail) ? e : Fail.failure(e.message)
            partial(fail, done, steps.size)
          end
          activate("complete")
          report("complete")
        end

        private

        def partial(fail, done, total)
          saved = begin
            activate("partial")
            nil
          rescue Fail => e
            e
          end
          report("partial")
          fail.hint("the partial record was not saved: #{saved.message} (#{saved.code})") if saved
          raise fail.hint(Assets.message("canvas_apply_partial", done: done.to_s, total: total.to_s, id: @planned.canvas_id))
        end

        def kind_of(node_kind)
          {"client" => Objects::OAUTH_CLIENT, "webhook" => Objects::WEBHOOK_OUTBOUND}.fetch(node_kind, Objects::APP)
        end

        def resolve(argv)
          argv.map do |(tag, value)|
            word = case tag
            when :text then value
            when :made then @made[value]
            else @manifests[value]
            end
            raise Fail.failure("#{value if tag == :made} was not made before it was needed") if word.nil?

            word
          end
        end

        # One inner verb; its JSON, any secret it revealed kept even when it
        # failed part-way.
        def invoke(handle, argv)
          outcome = @ctx.app.run([*argv, "--json", "--profile", @ctx.profile], NULL_SINK)
          answer = begin
            JSON.parse(outcome.stdout)
          rescue JSON::ParserError
            {} # A failed verb may print nothing; its fail says why.
          end
          keep_secret(handle, answer)
          raise outcome.fail if outcome.fail

          answer
        end

        def keep_secret(handle, answer)
          s = answer["secret"]
          secret = if s.is_a?(Hash) then {"handle" => handle, "id" => s["id"], "value" => s["value"]}
          elsif answer["secret_id"].is_a?(String) then {"handle" => handle, "id" => answer["secret_id"], "value" => answer["secret"]}
          end
          return unless secret

          @minted[handle] = Objects.str(secret["id"])
          @secrets << secret
        end

        def perform(step)
          r = step.records
          if r.is_a?(Array)
            secret_from_client(step, r[1])
          elsif !step.argv.empty?
            answer = invoke(step.handle, resolve(step.argv))
            record(step, r, answer)
          end
          @rows << [step.handle, step.kind, step.action, @recorded[step.handle] || "-"]
        end

        def secret_from_client(step, client_handle)
          id = @minted[client_handle]
          raise Fail.failure("#{client_handle} minted no secret") if id.nil?

          @secrets.each { |s| s["handle"] = step.handle if s["handle"] == client_handle }
          @recorded[step.handle] = id
        end

        def record(step, records, answer)
          if records == :runtime_id
            @made[step.handle] = Objects.str(answer["id"])
            @recorded[step.handle] = runtime_id(kind_of(step.kind), Objects.str(answer["id"]), answer)
          elsif records == :secret_id
            @recorded[step.handle] = Objects.str(answer["secret_id"])
          end
        end

        # The object's `runtime_id`, set once publishing has projected (16.10.26v §5).
        def runtime_id(kind, platform_id, answer)
          current = answer
          PUBLISH_POLLS.times do
            return current["runtime_id"] if current["runtime_id"].is_a?(String)

            @ctx.http.clock.sleep(1)
            current = Objects.get(@ctx, kind, platform_id)
          end
          return current["runtime_id"] if current["runtime_id"].is_a?(String)

          raise Fail.new(Exit::UNAVAILABLE, "object_projection_pending", "#{kind.word} #{platform_id} is still publishing; nothing after it was applied")
        end

        # `{draft_version, outcome, objects}`: runtime ids only (16.10.26x D4).
        def activate(outcome)
          objects = @recorded.transform_values { |id| {"id" => id} }
          body = {json: {"draft_version" => @planned.draft_version, "outcome" => outcome, "objects" => objects}}
          @ctx.fixed("canvas.activate", vars: {"id" => @planned.canvas_id}, body: body)
        rescue Fail => e
          e.hint("the canvas changed while applying; run `lingara canvas get #{@planned.canvas_id}`") if e.code == "canvas_conflict"
          raise
        end

        # D7 step 7: `HANDLE  KIND  ACTION  ID`, then every new secret, once.
        def report(status)
          out = @ctx.out
          if out.json
            out.json_value({"objects" => @recorded.transform_values { |id| {"id" => id} }, "secrets" => @secrets, "status" => status})
          else
            text = Output.render(%w[HANDLE KIND ACTION ID], @rows)
            unless @secrets.empty?
              text += "\n#{Output.render(%w[HANDLE SECRET_ID SECRET], @secrets.map { |s| s.values_at("handle", "id", "value").map { |v| Objects.str(v) } })}"
            end
            out.print(text)
          end
          out.note("Each secret is shown once: store them now. They cannot be read again.") unless @secrets.empty?
        end
      end
    end
  end
end
