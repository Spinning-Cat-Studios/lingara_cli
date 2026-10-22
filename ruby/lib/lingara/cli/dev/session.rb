# frozen_string_literal: true

require_relative "dev_api"
require_relative "hold"
require_relative "../supervise/group"
require_relative "../platform/objects"

module Lingara
  module CLI
    # An `apps dev` session (16.10.26ae D7 steps 3, 4 and 8): open it, start
    # the app with the session's credentials, hand over to `Hold`, then end
    # in order — `DELETE` the session, stop cloudflared, stop the app's
    # group — each step running even if the one before it failed. Under
    # `--json`, stdout carries one value, written here at exit.
    module DevSession
      # What an `apps dev` session needs, decided before the first request
      # (D7 steps 1 and 2). `target` is `[:cloudflared, binary]` or
      # `[:fixed, url]`; `probe` is `LINGARA_TUNNEL_PROBE_URL`, where
      # readiness is probed, never what is pointed at.
      Plan = Struct.new(:name, :id, :dir, :port, :start, :render_path, :target, :take_over, :client, :env,
        :write_env, :show_env, :probe)

      # The children and what the session pointed at, for step 8 and the
      # report. `lost`: the session ended under us (taken over, expired),
      # so there is no `DELETE`.
      Run = Struct.new(:app, :tunnel, :urls, :lost, :respawns, :stopped)

      LISTEN_WAIT_SECS = 60
      STOP_GRACE_SECS = 5
      APP_PREFIX = "app │ "

      def self.interrupted = Fail.new(Exit::INTERRUPTED, "interrupted", "interrupted")
      def self.lost(name) = Fail.from_message(Exit::DEV_SESSION_LOST, "dev_session_lost", name: name)

      # `409 dev_session_held` re-worded with the app's name and the lease.
      def self.held(fail, name)
        return fail unless fail.exit_code == Exit::DEV_SESSION_HELD

        lease = Http.parse(fail.body.to_s)&.dig("lease_expires_at")
        Fail.from_message(Exit::DEV_SESSION_HELD, "dev_session_held", name: name, lease_expires_at: lease.is_a?(String) ? lease : "its lease ends")
      end

      # Ctrl+C and SIGTERM, as a flag the session polls (D9: trapped, so the
      # ordered shutdown below runs before the process returns 130). The
      # block runs with the traps set, and the run's own traps come back
      # after it.
      def self.with_signals
        flag = false
        previous = %w[INT TERM].to_h { |sig| [sig, trap(sig) { flag = true }] }
        yield -> { flag }
      ensure
        previous&.each { |sig, handler| trap(sig, handler || "DEFAULT") }
      end

      # What the app's environment gains (D5, D7 step 4): the row's `env` and
      # `env_file`, the session's credentials, and the port.
      def self.session_vars(plan, session, client_id)
        vars = [*plan.env, ["LINGARA_APP_SECRET", session.app_secret]]
        vars.push(["LINGARA_CLIENT_ID", client_id], ["LINGARA_CLIENT_SECRET", session.client_secret]) if client_id && session.client_secret
        vars.push(["PORT", plan.port.to_s], ["LINGARA_APP_PORT", plan.port.to_s])
      end

      # `--show-env` (names only) and `--write-env` (mode 0600), both on stderr.
      def self.announce(ctx, plan, session, vars)
        vars.each { |(name, _)| ctx.out.note("env: #{name}") } if plan.show_env
        return unless plan.write_env

        File.write(plan.write_env, vars.map { |(k, v)| "#{k}=#{v}\n" }.join, perm: 0o600)
        File.chmod(0o600, plan.write_env)
        ctx.out.note(Assets.message("apps_dev_env_written", path: plan.write_env, expires_at: session.expires_at))
      end

      def self.start_app(plan, env)
        reader, writer = IO.pipe
        app = Supervise::Group.spawn(DevProject.shell_args(plan.start), env: env, chdir: plan.dir, in: File::NULL, out: writer, err: writer)
        writer.close
        Supervise.relay(reader, APP_PREFIX)
        app
      end

      # The app's own exit: nothing on status 0, else `app_exited`.
      def self.app_result(plan, app)
        return if app.exit_code&.zero?

        status = app.exit_code.nil? ? "a signal" : app.exit_code.to_s
        raise Fail.from_message(Exit::APP_EXITED, "app_exited", name: plan.name, status: status)
      end

      # Step 4's wait: the port accepts within 60 s. false when the app
      # already ended the session by exiting 0.
      def self.wait_app(plan, run)
        listening = Supervise.wait_listening(plan.port, LISTEN_WAIT_SECS) { run.stopped.call || run.app.done? }
        raise interrupted if run.stopped.call
        if run.app.done?
          app_result(plan, run.app)
          return false
        end
        raise Fail.from_message(Exit::APP_NOT_LISTENING, "app_not_listening", name: plan.name, port: plan.port.to_s) unless listening

        true
      end

      def self.live(ctx, plan, session, client_id, run)
        vars = session_vars(plan, session, client_id)
        announce(ctx, plan, session, vars)
        run.app = start_app(plan, Supervise.child_env(ctx.env.all, vars))
        return unless wait_app(plan, run)

        Hold.first_url(ctx, plan, session, run)
        Hold.hold(ctx, plan, session, run)
      end

      def self.stop_children(run)
        run.tunnel&.stop
        run.app&.stop(STOP_GRACE_SECS)
      end

      def self.session(ctx, plan)
        with_signals do |stopped|
          client_id = plan.client ? Objects.str(Objects.get(ctx, Objects::APP, plan.id)["client_id"]) : ""
          opened = begin
            DevApi.open(ctx, plan.id, plan.take_over, plan.client)
          rescue Fail => e
            raise held(e, plan.name)
          end
          run = Run.new(urls: [], lost: false, respawns: 0, stopped: stopped)
          error = attempt { live(ctx, plan, opened, client_id.empty? ? nil : client_id, run) }
          finish(ctx, plan, opened, run, error)
        end
      end

      # Runs the block; the refusal it ended with, or nil.
      def self.attempt
        yield
        nil
      rescue Fail => e
        e
      rescue Interrupt
        interrupted
      rescue => e
        Fail.failure(e.message)
      end

      # Step 8, in order, each step even if the one before it failed.
      def self.finish(ctx, plan, opened, run, error)
        attempt { DevApi.finish(ctx, plan.id, opened.id) } unless run.lost
        stop_children(run)
        if ctx.out.json
          exit_code = error ? error.exit_code : Exit::OK
          ctx.out.json_value({"name" => plan.name, "app_id" => plan.id, "session_id" => opened.id, "urls" => run.urls,
                              "exit" => Exit.name_of(exit_code)})
        end
        raise error if error
      end
    end
  end
end
