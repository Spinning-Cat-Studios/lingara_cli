# frozen_string_literal: true

require "socket"

module Lingara
  module CLI
    # The children `apps dev` runs (16.10.26ae D5, D7 steps 4 and 8;
    # 16.10.26af D2): `Process.spawn` with `pgroup: true`, so each child
    # leads its own process group and a terminal's Ctrl+C reaches only the
    # CLI; and a stop that signals the whole group with `Process.kill`,
    # SIGTERM then SIGKILL after a grace period. The app's environment is
    # the parent's with the CLI's own platform credentials scrubbed.
    module Supervise
      # The CLI's own credentials, never the app's (D5): `LINGARA_PLATFORM_*`.
      SCRUBBED_PREFIX = "LINGARA_PLATFORM_"

      # The parent's environment, minus the CLI's credentials, plus `extra`
      # (later entries win).
      def self.child_env(parent, extra)
        env = parent.reject { |k, _| k.start_with?(SCRUBBED_PREFIX) }
        extra.each { |(k, v)| env[k] = v }
        env
      end

      # A running child that leads its own process group, reaped by a thread.
      class Group
        attr_reader :pid, :status

        # `options` are `Process.spawn`'s; the child's environment replaces
        # the parent's when `env` is given.
        def self.spawn(argv, env: nil, **options)
          spawn_args = env ? [env, *argv] : argv
          new(Process.spawn(*spawn_args, pgroup: true, unsetenv_others: !env.nil?, **options))
        end

        def initialize(pid)
          @pid = pid
          @status = nil
          @waiter = Process.detach(pid)
        end

        def done?
          return true if @status

          @status = @waiter.value unless @waiter.alive?
          !@status.nil?
        end

        # The exit status, or nil when a signal ended it.
        def exit_code = done? ? @status.exitstatus : nil

        # Signals the whole group: SIGTERM, then SIGKILL once `grace` seconds
        # pass. The group may outlive its leader, so SIGKILL always follows.
        def stop(grace)
          signal("TERM")
          @waiter.join(grace)
          signal("KILL")
          @waiter.join(1)
          done?
        end

        private

        def signal(name)
          Process.kill(name, -pid)
        rescue Errno::ESRCH, Errno::EPERM
          nil # The group is already gone.
        end
      end

      # Copies `io` to `$stderr` line by line, each line after `prefix`, on
      # a thread of its own.
      def self.relay(io, prefix)
        Thread.new do
          io.each_line { |line| $stderr.write("#{prefix}#{line.chomp}\n") }
        rescue IOError
          nil
        end
      end

      def self.accepts?(port)
        Socket.tcp("127.0.0.1", port, connect_timeout: 0.25).close
        true
      rescue SystemCallError, SocketError, IOError
        false
      end

      # Whether `127.0.0.1:port` accepts a connection before `timeout`
      # seconds, giving up early when the block says so.
      def self.wait_listening(port, timeout)
        deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout
        while Process.clock_gettime(Process::CLOCK_MONOTONIC) < deadline
          return true if accepts?(port)
          return false if yield

          sleep 0.1
        end
        false
      end
    end
  end
end
