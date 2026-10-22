# The Ruby kit (ADR 16.10.26af D2, D6): gem `lingara-cli`, module
# `Lingara::CLI`, executable `lingara`, on Thor. Both command lines name the
# interpreter's own path (RbConfig.ruby, whatever version manager put `ruby`
# on PATH), never a shim, because the runner drops the real HOME (ADR
# 16.10.26ac D5); and an absolute script path, because 16.10.26ae's project
# cases run the CLI from a fixture's temp directory. `-rbundler/setup` pins
# the Gemfile.lock's gems; BUNDLE_GEMFILE names that Gemfile from any
# directory, so every ruby lane exports it and conformance passes it through
# with the two variables a gem install may have set.

# Listed with its languages.toml entry, once every conformance case passes
# (ADR 16.10.26af D3).
LANGS += ruby

RUBY ?= ruby
RUBY_BIN := $(shell $(RUBY) -e 'print RbConfig.ruby' 2>/dev/null)
# bundle and gem run by path, never `-S`: -S searches PATH, where
# mise-action's binary shims come first; both are default gems, installed
# beside the interpreter in RbConfig::CONFIG["bindir"] (ADR 22.10.26d D2).
RUBY_BINDIR := $(dir $(RUBY_BIN))
RUBY_KIT_GEMFILE := $(CURDIR)/ruby/Gemfile
RUBY_KIT_BIN := $(CURDIR)/ruby/exe/lingara
# tree-diff appends `__tree` itself (make/config.mk).
TREE_CMD_ruby = $(RUBY_BIN) -rbundler/setup $(RUBY_KIT_BIN)
CONFORMANCE_CMD_ruby = $(RUBY_BIN) -rbundler/setup $(RUBY_KIT_BIN)
CONFORMANCE_PASS_ENV_ruby = GEM_HOME GEM_PATH BUNDLE_GEMFILE

.PHONY: build-ruby test-ruby package-ruby conformance-packaged-ruby help-ruby

# Every ruby lane resolves the kit's own Gemfile, wherever it runs from.
build-ruby test-ruby package-ruby check-tree-ruby check-version-ruby conformance-ruby: export BUNDLE_GEMFILE := $(RUBY_KIT_GEMFILE)

## Install the Gemfile.lock's gems (Thor, tomlrb, json_schemer; minitest and
## standard for the tests).
build-ruby:
	cd ruby && $(RUBY_BIN) $(RUBY_BINDIR)bundle install --quiet

## The unit tests, then RuboCop at the budgets (.rubocop.yml), then the
## size budgets RuboCop has no cop for: 300 lines per source file, 15
## methods per file, 15 files per directory (.code-health/thresholds.toml).
test-ruby: build-ruby
	cd ruby && $(RUBY_BIN) $(RUBY_BINDIR)bundle exec ruby -Ilib -Itest -e 'Dir["test/**/*_test.rb"].sort.each { |f| require_relative f }'
	cd ruby && $(RUBY_BIN) $(RUBY_BINDIR)bundle exec rubocop
	@sh ruby/scripts/budgets.sh ruby/lib

## 16.10.26af AC11: `gem build` succeeds, its executables are exactly
## `lingara`, and its files hold every asset under lib/lingara/cli/assets/.
package-ruby: build-ruby
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	(cd ruby && $(RUBY_BIN) $(RUBY_BINDIR)gem build lingara-cli.gemspec --silent -o "$$tmp/lingara-cli.gem") && \
	$(RUBY_BIN) ruby/scripts/check_gem.rb "$$tmp/lingara-cli.gem" ruby/lib/lingara/cli/assets

## The gem as RubyGems would ship it (16.10.26af D8, AC15): built, installed
## with its dependencies into a scratch GEM_HOME, and its `lingara` run from
## outside the checkout through the create and b3 cases. A kit that read
## ../spec or ../templates at run time fails here.
# 16.10.26af AC15
conformance-packaged-ruby: package-ruby build-conformance-fakes
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	(cd ruby && $(RUBY_BIN) $(RUBY_BINDIR)gem build lingara-cli.gemspec --silent -o "$$tmp/lingara-cli.gem") && \
	env -u BUNDLE_GEMFILE GEM_HOME="$$tmp/gems" GEM_PATH="$$tmp/gems" \
	  $(RUBY_BIN) $(RUBY_BINDIR)gem install --silent --no-document --install-dir "$$tmp/gems" "$$tmp/lingara-cli.gem" && \
	$(call packaged-suite,$$tmp/suite) && \
	env -u BUNDLE_GEMFILE GEM_HOME="$$tmp/gems" GEM_PATH="$$tmp/gems" \
	  $(CONFORMANCE_RUNNER) run --lang ruby $(call PACKAGED_ARGS,$$tmp/suite) --pass-env GEM_HOME --pass-env GEM_PATH -- \
	  $(RUBY_BIN) "$$tmp/gems/bin/lingara"

# Both run the kit, so its gems are installed first. check-tree-ruby is
# 16.10.26af AC6 (the tree from Thor and Registry::Table) and
# check-version-ruby AC7 (`lingara 0.0.0 (ruby)`), both make/quality.mk's
# pattern rules; conformance-ruby is AC4.
check-tree-ruby check-version-ruby conformance-ruby: build-ruby

help-ruby:
	@echo ""
	@echo "Ruby kit:"
	@echo "  make build-ruby                 - bundle install (ruby/Gemfile.lock)"
	@echo "  make test-ruby                  - minitest, then RuboCop and the size budgets"
	@echo "  make package-ruby               - gem build, and check its executables and assets"
	@echo "  make conformance-ruby           - Every conformance case against ruby/exe/lingara"
	@echo "  make conformance-packaged-ruby  - The built gem, installed, from outside the checkout"

HELP_SECTIONS += ruby
