# frozen_string_literal: true

require "stringio"

module Lingara
  module CLI
    # What one run ends with: the exit code, the buffered stdout, and the
    # refusal when there was one (canvas apply reads it in-process).
    Outcome = Struct.new(:exit_code, :stdout, :fail)

    # One run: parse argv with Thor, pick the profile and the API, run the
    # handler, and turn the result into an exit code. `run` is the one entry
    # point, used by `exe/lingara` and, in-process, by canvas apply's inner
    # verbs (16.10.26ad D7).
    module Main
      # Thor's help, written into a string rather than to `$stdout`, so
      # `Main` writes stdout once whatever the command.
      class CaptureShell < ::Thor::Shell::Basic
        def stdout = @stdout ||= StringIO.new
      end

      def self.main(argv)
        # D9: a signal never kills the kit. SIGINT and SIGTERM both raise
        # Interrupt, which `run` turns into `interrupted` (130), returned
        # after every `ensure` has cleaned up; it is never re-raised.
        trap("INT") { raise Interrupt }
        trap("TERM") { raise Interrupt }
        outcome = run(App.production, argv, $stderr)
        $stdout.write(outcome.stdout)
        $stdout.flush
        outcome.exit_code
      end

      def self.run(app, argv, err)
        dispatch(app, argv, err)
      rescue Fail => e
        failed(e, argv.include?("--json"), err, "")
      rescue Interrupt
        failed(Fail.new(Exit::INTERRUPTED, "interrupted", "interrupted"), argv.include?("--json"), err, "")
      end

      def self.dispatch(app, argv, err)
        return Outcome.new(Exit::OK, "lingara #{VERSION} (ruby)\n") if [["--version"], ["-V"]].include?(argv)
        return help(argv) if argv.empty? || argv.include?("--help") || argv.include?("-h")

        shell = CaptureShell.new
        parsed = parse(argv, shell)
        return Outcome.new(Exit::OK, shell.stdout.string) unless parsed.is_a?(Registry::Parsed)

        execute(app, parsed, err)
      end

      # Thor's parse; every `Thor::Error` is `usage` (D9: Thor's own exit
      # would be 1), and an argv that fails it never reaches the server.
      def self.parse(argv, shell)
        Registry::Root.start(argv, debug: true, shell: shell)
      rescue ::Thor::Error => e
        raise Fail.usage(e.message.lines.first.to_s.sub(/\AERROR: /, "").strip)
      end

      def self.execute(app, parsed, err)
        profile, chosen, json = profile_of(app, parsed.flags)
        out = Output.new(err, json)
        begin
          Commands.run(Ctx.new(app, out, profile, chosen), parsed)
          Outcome.new(Exit::OK, out.stdout)
        rescue Fail => e
          failed(e, out.json, err, out.stdout)
        rescue Interrupt
          failed(Fail.new(Exit::INTERRUPTED, "interrupted", "interrupted"), out.json, err, out.stdout)
        rescue => e
          failed(Fail.failure(e.message), out.json, err, out.stdout)
        end
      end

      # The profile, and 16.10.26ad D3's API: `LINGARA_API_URL`, then
      # `platform.toml`'s `[platform] api` (applied when an `apps` command
      # opens the file), then the profile's, then `endpoints.json`'s.
      def self.profile_of(app, flags)
        chosen = flags["profile"] || app.env["LINGARA_PROFILE"]
        profile = chosen || "default"
        settings = app.env.profile_config(profile)
        app.http.bases[:api] = app.env["LINGARA_API_URL"] || settings["api_url"] || app.endpoints["api_url"]
        [profile, !chosen.nil?, flags["json"] == true || settings["output"] == "json"]
      end

      def self.failed(fail, json, err, stdout)
        Output.error_lines(fail, json).each { |line| err.write("#{line}\n") }
        Outcome.new(fail.exit_code, stdout, fail)
      end

      # `--help` / `-h` anywhere, or no argv: Thor's help for the deepest
      # class or command the leading words name.
      def self.help(argv)
        words = argv.reject { |a| a.start_with?("-") }
        shell = CaptureShell.new
        klass, leaf = help_target(words)
        leaf ? klass.command_help(shell, leaf) : klass.help(shell)
        Outcome.new(Exit::OK, shell.stdout.string)
      end

      # The class the words lead to, and the command they end on, if any.
      def self.help_target(words)
        klass = Registry::Root
        words.each_with_index do |word, i|
          word = klass.map[word].to_s if klass.map.key?(word)
          return [klass, word] if klass.all_commands.key?(word) && !klass.subcommand_classes.key?(word)
          raise Fail.usage("#{words[0..i].join(" ")} is not a lingara command") unless klass.subcommand_classes.key?(word)

          klass = klass.subcommand_classes[word]
        end
        [klass, nil]
      end
    end
  end
end
