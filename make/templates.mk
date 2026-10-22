# The app templates (ADR 16.10.26ae D2, D3): templates/apps/<lang>/{default,bare}/,
# which every kit scaffolds byte for byte (spec/templates.md) and carries in
# its own assets/ (D12).
#
#   make templates-smoke LANG=<id>   scaffold both variants with the Rust kit
#                                    into a temp dir and run each one's own
#                                    offline test against the pinned app kit
#   make check-template-pins         advisory: templates/VERSIONS.toml against
#                                    the app SDK's newest release tag
#
# LANG is the app-kit id here, never a locale: every command below resets the
# locale it runs under.

TEMPLATE_LANGS := typescript rust go java kotlin ruby php
APP_SDK_REPO ?= https://github.com/Spinning-Cat-Studios/lingara_app_framework_sdk.git

# One template's own offline test, run in its scaffolded project.
SMOKE_typescript = npm install --silent && npm test
SMOKE_rust = cargo test -q
SMOKE_go = go mod tidy && go test ./...
SMOKE_java = gradle --quiet test
SMOKE_kotlin = gradle --quiet test
SMOKE_ruby = bundle install --quiet && bundle exec ruby -Itest test/render_test.rb
SMOKE_php = composer install --quiet && composer test

.PHONY: templates-smoke check-template-pins help-templates

## Both variants of one language's template, scaffolded and tested offline.
templates-smoke: build-rust
	@test -n "$(SMOKE_$(LANG))" || { echo "✗ LANG must be one of: $(TEMPLATE_LANGS)"; exit 1; }
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	for variant in default bare; do \
	  flag=$$([ $$variant = bare ] && echo --bare); \
	  LANG=C.UTF-8 $(RUST_KIT_BIN) apps create demo --lang $(LANG) $$flag --no-register --dir "$$tmp/$$variant/demo" >/dev/null || exit 1; \
	  (cd "$$tmp/$$variant/demo" && LANG=C.UTF-8 $(SMOKE_$(LANG))) || { echo "✗ the $(LANG) $$variant template's own test failed"; exit 1; }; \
	  echo "✓ the $(LANG) $$variant template scaffolds and passes its own test"; \
	done

## Advisory: the pinned app-kit version against the app SDK's newest tag.
check-template-pins:
	@pinned=$$(sed -n 's/^app_kit = "\(.*\)"/\1/p' templates/VERSIONS.toml); \
	newest=$$(git ls-remote --tags --refs $(APP_SDK_REPO) 2>/dev/null | grep -E 'refs/tags/v[0-9]' | sed 's|.*refs/tags/v||' | sort -V | tail -1); \
	if [ -z "$$newest" ]; then echo "warning: could not read the app SDK's tags; templates pin $$pinned"; \
	elif [ "$$newest" = "$$pinned" ]; then echo "✓ templates pin the app SDK's newest release, $$pinned"; \
	else echo "warning: templates pin $$pinned; the app SDK's newest release is $$newest (a newer release wants a pin bump, a pin ahead of the releases waits for one)"; fi

help-templates:
	@echo ""
	@echo "Templates:"
	@echo "  make templates-smoke LANG=<id> - Scaffold both variants and run their own offline tests: $(TEMPLATE_LANGS)"
	@echo "  make check-template-pins       - Advisory: the pinned app-kit release against the newest"

HELP_SECTIONS += templates
