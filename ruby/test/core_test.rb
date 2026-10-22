# frozen_string_literal: true

require_relative "test_helper"
require "open3"
require "rbconfig"

# The Ruby kit's core, end to end through `Main.run` where it can be: the
# confirm ritual, the file-only store, the classifier's B2 rules, every
# canvas plan vector, the B1 table, SIGINT as a returned 130, and D9's Thor
# overrides.
class CoreTest < Minitest::Test
  CLI = Lingara::CLI
  CLIENT = "0b9c4f0e-0000-4000-8000-000000000001"
  VECTORS = JSON.parse(File.read(File.join(Fakes::REPO, "conformance/vectors/canvas-plans.json")))["vectors"]

  # 16.10.26af AC19
  def test_core
    check_confirm_ritual
    check_file_only_store
    check_classifier
    check_plan_vectors
    check_table
    check_thor_overrides
    check_interrupted
  end

  private

  def delete(script, argv, terminal: Fakes::Terminal.new(false))
    Fakes.run(Fakes.app(script, terminal: terminal), ["client", "delete", CLIENT, *argv])
  end

  # B3: refused before any request without a terminal; `-y --confirm`
  # checked before the DELETE; interactive y/n then the typed email, sent
  # canonical in the body; a declined or mismatched answer sends nothing.
  def check_confirm_ritual
    script = Fakes::Script.new
    outcome, err = delete(script, [])
    assert_equal [CLI::Exit::NOT_CONFIRMED, []], [outcome.exit_code, script.log]
    assert_includes err, CLI::Assets.message("confirm_non_tty_refused")

    script = Fakes::Script.new
    outcome, = delete(script, ["-y", "--confirm", "someone@else.invalid"])
    assert_equal [CLI::Exit::NOT_CONFIRMED, []], [outcome.exit_code, script.log]

    script = Fakes::Script.new.then("DELETE /platform/objects/#{CLIENT}", 200, {})
    assert_equal CLI::Exit::OK, delete(script, ["-y", "--confirm", " DEV@conformance.invalid"]).first.exit_code
    assert_equal({"confirm" => Fakes::EMAIL}, JSON.parse(script.log.last.body))

    read = {"id" => CLIENT, "name" => "My server", "children" => [{"kind" => "app"}], "pinned_by" => 2}
    script = Fakes::Script.new.then("GET /platform/objects/#{CLIENT}", 200, read).then("DELETE /platform/objects/#{CLIENT}", 200, {})
    outcome, err = delete(script, [], terminal: Fakes::Terminal.new(true, ["yes", "Dev@Conformance.Invalid "]))
    assert_equal CLI::Exit::OK, outcome.exit_code
    assert_includes err, "Delete OAuth client My server (#{CLIENT})?"
    assert_includes err, CLI::Assets.message("confirm_pinned_by", count: "2")
    assert_includes err, "(d…@conformance.invalid)"

    script = Fakes::Script.new.then("GET /platform/objects/#{CLIENT}", 200, read)
    outcome, = delete(script, [], terminal: Fakes::Terminal.new(true, ["n"]))
    assert_equal [CLI::Exit::NOT_CONFIRMED, ["GET /platform/objects/#{CLIENT}"]], [outcome.exit_code, script.sent]

    script = Fakes::Script.new.then("GET /platform/objects/#{CLIENT}", 200, read)
    outcome, err = delete(script, [], terminal: Fakes::Terminal.new(true, ["y", "other@conformance.invalid"]))
    assert_equal [CLI::Exit::NOT_CONFIRMED, 1], [outcome.exit_code, script.log.size]
    assert_includes err, "(confirm_email_mismatch)"
  end

  # D2: the file is the default; its first write prints `store_file_only`
  # once, `LINGARA_KEYRING=file` prints nothing, and `=keychain` is
  # `auth_required` (3) with that sentence, never a silent file write.
  def check_file_only_store
    Dir.mktmpdir do |dir|
      file = CLI::Store::FileStore.new(dir)
      auto = CLI::Store::Stores.new(:auto, file)
      note = auto.save("default", Fakes.stored_record)
      assert_equal CLI::Assets.message("store_file_only", path: file.path), note
      assert_nil auto.save("default", Fakes.stored_record)
      assert_nil CLI::Store::Stores.new(:file, file).save("other", Fakes.stored_record)
      assert_equal 0o600, File.stat(file.path).mode & 0o777
      assert_equal 0o700, File.stat(dir).mode & 0o777
      assert_equal %w[default other], JSON.parse(File.read(file.path))["profiles"].keys
      assert_equal(:keychain, CLI::Store::Stores.mode_of("keychain"))
      assert_equal(:auto, CLI::Store::Stores.mode_of(nil))
    end

    script = Fakes::Script.new
    outcome, err = Fakes.run(Fakes.app(script, env: {"LINGARA_KEYRING" => "keychain"}), %w[apps list])
    assert_equal [CLI::Exit::AUTH_REQUIRED, "store_file_only", []], [outcome.exit_code, outcome.fail.code, script.log]
    assert_includes err, "this kit has no keychain support"
  end

  def answer(status, body, route: "app.list", bearer: true)
    token = %w[oauth.token oauth.device_authorization].include?(route)
    CLI::Classify::Answer.new(route: route, token_route: token, platform_route: !token, bearer: bearer && !token,
      status: status, body: body.is_a?(String) ? body : JSON.generate(body))
  end

  # B2's eight rules, in order, and B4's three signals first.
  def check_classifier
    c = ->(*args, **kw) { CLI::Classify.classify(answer(*args, **kw)) }
    assert_equal CLI::Exit::PLATFORM_DISABLED, c.call(404, {"code" => "platform_disabled", "error" => "x"})
    assert_equal CLI::Exit::PLATFORM_DISABLED, c.call(403, {"code" => "platform_access_not_enabled", "error" => "x"})
    assert_equal CLI::Exit::PLATFORM_DISABLED, c.call(400, {"error" => "invalid_grant", "lingara_code" => "platform_access_not_enabled"}, route: "oauth.token")
    assert_equal :refresh, c.call(401, "")
    assert_equal CLI::Exit::AUTH_REQUIRED, c.call(400, {"error" => "invalid_grant"}, route: "oauth.token")
    assert_equal CLI::Exit::NOT_FOUND, c.call(404, {"code" => "object_not_found", "error" => "x"})
    assert_equal CLI::Exit::REFUSED, c.call(404, {"code" => "developer_not_found", "error" => "x"}, route: "developer.me")
    assert_equal CLI::Exit::DEV_SESSION_HELD, c.call(409, {"code" => "dev_session_held", "error" => "x"}, route: "app_dev.session_open")
    assert_equal CLI::Exit::DEV_SESSION_LOST, c.call(409, {"code" => "dev_session_expired", "error" => "x"}, route: "app_dev.session_renew")
    assert_equal CLI::Exit::FAILURE, c.call(404, "")
    assert_equal CLI::Exit::REFUSED, c.call(422, {"code" => "delete_confirmation_mismatch", "error" => "x"})
    assert_equal CLI::Exit::UNAVAILABLE, c.call(429, "")
    assert_equal CLI::Exit::UNAVAILABLE, c.call(503, "maintenance")
    assert_equal CLI::Exit::FAILURE, c.call(405, "")
  end

  def live(v) = CLI::Canvas::Plan::Live.new(*%w[oauth_client webhook_out app].map { |k| Array(v[k]).map { |r| CLI::Canvas::Plan.row_of(r) } })

  def word((tag, value)) = {text: value, made: "<#{value}>", manifest: "<#{value}.manifest>"}.fetch(tag)

  def rendered(step)
    records = step.records.is_a?(Array) ? "client_secret" : step.records.to_s
    {"handle" => step.handle, "action" => step.action, "records" => records, "argv" => step.argv.map { |p| word(p) }}
  end

  # B7: every vector in conformance/vectors/canvas-plans.json.
  def check_plan_vectors
    require "lingara/cli/canvas/apply"
    assert_operator VECTORS.size, :>=, 8
    VECTORS.each { |v| check_vector(v) }
    line = CLI::Canvas::Shell.line([[:text, "oauth_client"], [:text, "--name"], [:text, "My server's"], [:made, "n1"]], {})
    assert_equal "lingara oauth_client --name 'My server'\\''s' '<n1>'", line
  end

  def check_vector(v)
    graph = CLI::Canvas::Definition.read(v["definition"])
    rows = live(v["live"])
    kept = CLI::Canvas::Plan.still_live(graph, v["recorded"].transform_values { |r| r["id"] }, rows)
    if v["expect"]["refused"]
      error = assert_raises(CLI::Canvas::Plan::Refusal, v["name"]) { CLI::Canvas::Plan.build(graph, kept) }
      return assert_equal [:script, v["expect"]["refused"]], [error.why, error.handle], v["name"]
    end
    ops = CLI::Canvas::Plan.build(graph, kept)
    assert_equal [v["expect"]["kept"], v["expect"]["ops"]], [kept, ops], v["name"]
    check_steps(v, ops, rows, kept) if v["verdicts"]
  end

  def check_steps(v, ops, rows, kept)
    return assert_equal(v["steps"], CLI::Canvas::Argv.steps(ops, v["verdicts"], rows, kept).map { |s| rendered(s) }, v["name"]) unless v["stop"]

    stop = assert_raises(CLI::Canvas::Argv::Stop) { CLI::Canvas::Argv.steps(ops, v["verdicts"], rows, kept) }
    assert_equal v["stop"], stop.replace ? {"replace" => stop.replace} : {"refused" => stop.refused.map(&:first)}, v["name"]
  end

  # B1: left-aligned, two spaces apart, `-` for empty, no trailing space.
  def check_table
    text = CLI::Output.render(%w[ID NAME SCOPES], [["a", "", "x"], ["bbb", nil, CLI::Output.cell(%w[p q])]])
    assert_equal "ID   NAME  SCOPES\na    -     x\nbbb  -     p,q\n", text
    assert_equal "-", CLI::Output.cell([])
    assert_equal [%w[id 1], %w[tags a,b]], CLI::Output.fields({"id" => "1", "tags" => %w[a b]})
  end

  # D9: an unknown option, a command prefix and a dashed spelling are all
  # `usage` (2), and none sends a request.
  def check_thor_overrides
    [%w[apps list --nope], %w[apps lis], %w[oauth-client list], %w[apps], %w[client get]].each do |argv|
      script = Fakes::Script.new
      outcome, = Fakes.run(Fakes.app(script), argv)
      assert_equal [CLI::Exit::USAGE, []], [outcome.exit_code, script.log], argv.join(" ")
    end
    # The constant, not a literal, so a version bump needs no test edit (ADR 22.10.26s AC7).
    assert_equal "lingara #{CLI::VERSION} (ruby)\n", Fakes.run(Fakes.app(Fakes::Script.new), ["--version"]).first.stdout
  end

  # D9: SIGINT during a wait is `interrupted`, **returned** as 130 after
  # cleanup; the process never dies by the signal.
  def check_interrupted
    Dir.mktmpdir do |dir|
      env = {"LINGARA_CONFIG_DIR" => dir, "LINGARA_KEYRING" => "file", "LINGARA_NO_BROWSER" => "1", "LINGARA_PLATFORM_URL" => "http://127.0.0.1:9"}
      exe = File.expand_path("../exe/lingara", __dir__)
      Open3.popen3(env, RbConfig.ruby, exe, "login") do |stdin, _out, err, waiter|
        stdin.close
        Timeout.timeout(30) { nil until err.gets.to_s.include?("Waiting for the browser") }
        Process.kill("INT", waiter.pid)
        status = waiter.value
        assert_equal [130, false], [status.exitstatus, status.signaled?]
      end
    end
  end
end
