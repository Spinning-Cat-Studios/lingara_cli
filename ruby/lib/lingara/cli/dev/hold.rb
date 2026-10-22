# frozen_string_literal: true

require_relative "dev_api"
require_relative "../supervise/cloudflared"

module Lingara
  module CLI
    # Holding an `apps dev` session (16.10.26ae D7 steps 5–7): bring the
    # tunnel up, point the dev version at it, then renew the lease every
    # `lease_secs / 3` until Ctrl+C, the app's exit or a lost session ends
    # it. A cloudflared that dies is respawned up to five times, 1 s, 2 s,
    # 4 s, 8 s and 16 s apart, and each new URL is pointed at; a sixth death,
    # or a first incarnation that never comes up, is `tunnel_failed`.
    module Hold
      BACKOFF_SECS = [1, 2, 4, 8, 16].freeze
      TICK_SECS = 0.1

      def self.now = Process.clock_gettime(Process::CLOCK_MONOTONIC)

      def self.tunnel_failed(plan, reason, tail)
        fail = Fail.from_message(Exit::TUNNEL_FAILED, "tunnel_failed", name: plan.name, reason: reason)
        tail.split("\n").reject(&:empty?).each { |line| fail.hint(line) }
        fail
      end

      # Step 6: `PATCH …/dev {render_url, session_id, draft_version}`, then
      # the ready line. A `dev_session_held` here means a take-over since
      # the open.
      def self.point(ctx, plan, session, url, run)
        begin
          DevApi.patch_with_retry(ctx, plan.id) { |draft| {"render_url" => url, "session_id" => session.id, "draft_version" => draft} }
        rescue Fail => e
          raise unless e.exit_code == Exit::DEV_SESSION_HELD

          run.lost = true
          raise DevSession.lost(plan.name)
        end
        ctx.out.note(Assets.message("apps_dev_ready", name: plan.name, url: url))
        run.urls << url
      end

      # One incarnation, up: `[:url, url]`, or `[:tail, lines]` when it never was.
      def self.incarnation(plan, binary, run)
        tunnel = Tunnel.spawn(binary, plan.port)
        host = tunnel.hostname(run.stopped)
        if host && tunnel.ready?(host, plan.probe, run.stopped)
          run.tunnel = tunnel
          return [:url, "https://#{host}#{plan.render_path}"]
        end
        tail = tunnel.tail
        tunnel.stop
        [:tail, tail]
      end

      # Steps 5 and 6 for the first incarnation, or the fixed `--render-url`.
      def self.first_url(ctx, plan, session, run)
        kind, value = plan.target
        return point(ctx, plan, session, value, run) if kind == :fixed

        up, detail = incarnation(plan, value, run)
        raise DevSession.interrupted if run.stopped.call
        raise tunnel_failed(plan, "no URL, or no answer through it, within 30 s", detail) if up == :tail

        point(ctx, plan, session, detail, run)
      end

      # Sleeps `secs`, a tick at a time, unless the session is stopped.
      def self.backoff(secs, run)
        deadline = now + secs
        while now < deadline
          raise DevSession.interrupted if run.stopped.call

          sleep TICK_SECS
        end
      end

      # Step 7's respawn: until an incarnation is up and pointed at, or a
      # sixth death.
      def self.respawn(ctx, plan, session, run)
        tail = run.tunnel&.tail.to_s
        run.tunnel&.stop
        run.tunnel = nil
        loop do
          secs = BACKOFF_SECS[run.respawns]
          raise tunnel_failed(plan, "cloudflared died a sixth time", tail) if secs.nil?

          backoff(secs, run)
          run.respawns += 1
          up, detail = incarnation(plan, plan.target[1], run)
          raise DevSession.interrupted if run.stopped.call
          return point(ctx, plan, session, detail, run) if up == :url

          tail = detail
        end
      end

      # One scheduled renew. A refusal that means the session is over ends
      # the run with no `DELETE`; anything else waits for the next scheduled
      # renew (B5: a scheduled request is not a retry).
      def self.renew(ctx, plan, session, run)
        DevApi.renew(ctx, plan.id, session.id)
      rescue Fail => e
        return unless e.exit_code == Exit::DEV_SESSION_LOST

        run.lost = true
        raise DevSession.lost(plan.name)
      end

      # Step 7: the loop, until the first thing that ends the session.
      def self.hold(ctx, plan, session, run)
        period = [1, session.lease_secs.to_i / 3].max
        next_renew = now + period
        loop do
          raise DevSession.interrupted if run.stopped.call
          return DevSession.app_result(plan, run.app) if run.app.done?

          respawn(ctx, plan, session, run) if run.tunnel && !run.tunnel.alive?
          if now >= next_renew
            renew(ctx, plan, session, run)
            next_renew = now + period
          end
          sleep TICK_SECS
        end
      end
    end
  end
end
