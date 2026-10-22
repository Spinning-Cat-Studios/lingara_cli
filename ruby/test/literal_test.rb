# frozen_string_literal: true

require_relative "test_helper"
require "lingara/cli/literal/connection"
require "lingara/cli/project/platform_toml"

# The literal reader against spec/connection-fixtures/, which every kit's
# reader passes (16.10.26ae D2), and platform.toml's fixed refusal order
# (16.10.26ae D4): syntax, then a secret, then the schema.
class LiteralTest < Minitest::Test
  CLI = Lingara::CLI
  FIXTURES = File.join(Fakes::REPO, "spec/connection-fixtures")

  def test_valid_fixtures_convert_to_their_wire_manifests
    Dir[File.join(FIXTURES, "valid/*.json")].each do |want|
      got = CLI::Literal.app_manifest(File.read(want.sub(/\.json\z/, ".js")))
      assert_equal JSON.parse(File.read(want)), got, want
    end
  end

  def test_refused_fixtures_name_their_code_and_position
    Dir[File.join(FIXTURES, "refused/*.json")].each do |want|
      error = assert_raises(CLI::Literal::Refusal, want) { CLI::Literal.app_manifest(File.read(want.sub(/\.json\z/, ".js"))) }
      expected = JSON.parse(File.read(want))
      got = {"code" => error.code}
      got.merge!("line" => error.pos.line, "column" => error.pos.column) if error.pos
      got["statement"] = error.statement if error.statement
      assert_equal expected, got, want
    end
  end

  def test_platform_toml_refuses_a_secret_before_the_schema
    Dir.mktmpdir do |dir|
      path = File.join(dir, "platform.toml")
      File.write(path, "schema = 2\n[[apps]]\nname = \"a\"\npath = \".\"\n[apps.env]\nAPI_TOKEN = \"x\"\n")
      error = assert_raises(CLI::Fail) { CLI::Project::PlatformToml.load(path, "platform.toml") }
      assert_equal [CLI::Exit::PLATFORM_TOML_SECRET, "platform.toml: apps.env.API_TOKEN looks like"],
        [error.exit_code, error.message[0, 44]]
      File.write(path, "schema = 2\n")
      error = assert_raises(CLI::Fail) { CLI::Project::PlatformToml.load(path, "platform.toml") }
      assert_equal CLI::Exit::PLATFORM_TOML_SCHEMA, error.exit_code
    end
  end
end
