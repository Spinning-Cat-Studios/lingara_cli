# frozen_string_literal: true

# The gem `lingara-cli` (ADR 16.10.26af D6): the `lingara` command, Ruby
# kit. It ships ruby/ alone, so the spec files and app templates it reads at
# run time are the committed copies under lib/lingara/cli/assets/ (D8), and
# `files` lists every one of them. The URLs name the public repository only.

require_relative "lib/lingara/cli/version"

Gem::Specification.new do |spec|
  spec.name = "lingara-cli"
  spec.version = Lingara::CLI::VERSION
  spec.authors = ["Spinning Cat Studios"]
  spec.summary = "lingara, the Lingara developer CLI"
  spec.description = "OAuth clients, apps, embeds, webhooks and canvases on the Lingara platform, " \
    "from the command line: the Ruby kit of lingara."
  spec.homepage = "https://github.com/Spinning-Cat-Studios/lingara_cli"
  spec.license = "MIT"
  # The app SDK's floor; 3.1 and 3.2 are past end of life (D6).
  spec.required_ruby_version = ">= 3.3"
  spec.metadata = {
    "source_code_uri" => "https://github.com/Spinning-Cat-Studios/lingara_cli",
    "rubygems_mfa_required" => "true"
  }
  # Every file under lib/ (the assets included: the templates hold no
  # dotfiles, a leading `_` stands for the `.`), the executable and the
  # README. Paths are relative to this file, so `gem build` runs anywhere.
  spec.files = Dir.glob("{lib/**/*,exe/lingara}", base: __dir__).select { |p| File.file?(File.join(__dir__, p)) } +
    %w[README.md]
  spec.bindir = "exe"
  spec.executables = ["lingara"]

  spec.add_dependency "thor", "~> 1.5"
  spec.add_dependency "tomlrb", "~> 3.0"
  spec.add_dependency "json_schemer", "~> 2.5"
end
