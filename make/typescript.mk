# The TypeScript kit (ADR 16.10.26ad D10): package `@lingara/cli`, binary
# `lingara`, on oclif. Both command lines name the resolved interpreter, not
# the mise shim (the runner drops the real HOME, ADR 16.10.26ac D5), and an
# absolute script path, because 16.10.26ae's project cases run the CLI from a
# fixture's temp directory.

LANGS += typescript

TS_KIT_NODE := $(shell mise which node 2>/dev/null || command -v node)
TS_KIT_BIN := $(CURDIR)/typescript/bin/run.js
# tree-diff appends `__tree` itself (make/config.mk).
TREE_CMD_typescript = $(TS_KIT_NODE) $(TS_KIT_BIN)
CONFORMANCE_CMD_typescript = $(TS_KIT_NODE) $(TS_KIT_BIN)

.PHONY: build-typescript test-typescript test-typescript-supervise conformance-packaged-typescript help-typescript

## Compile typescript/src to typescript/dist, which bin/run.js loads.
build-typescript:
	npm --prefix typescript run build

## A clean install, then typecheck, vitest and the ESLint budgets.
test-typescript:
	npm --prefix typescript ci
	npm --prefix typescript test

## The process-group tests alone: what the Windows runner proves (16.10.26ae AC24).
test-typescript-supervise:
	npm --prefix typescript exec -- vitest run src/__tests__/supervise.test.ts

## The package as npm would ship it (ADR 16.10.26ae D12): `npm pack`, installed
## into a scratch prefix, then run from outside the checkout through the
## create and b3 cases.
conformance-packaged-typescript: build-typescript build-conformance-fakes
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	npm pack ./typescript --silent --pack-destination "$$tmp" >/dev/null && \
	npm install --silent --global --prefix "$$tmp/prefix" "$$tmp"/lingara-cli-*.tgz && \
	$(call packaged-suite,$$tmp/suite) && \
	$(CONFORMANCE_RUNNER) run --lang typescript $(call PACKAGED_ARGS,$$tmp/suite) -- \
	  $(TS_KIT_NODE) "$$tmp/prefix/lib/node_modules/@lingara/cli/bin/run.js"

# Both run the built kit, so they build it first.
check-tree-typescript check-version-typescript conformance-typescript: build-typescript

help-typescript:
	@echo ""
	@echo "TypeScript kit:"
	@echo "  make build-typescript                - Compile typescript/dist"
	@echo "  make test-typescript                 - npm ci, then typecheck, vitest and the lint budgets"
	@echo "  make test-typescript-supervise       - The process-group tests alone (the Windows leg)"
	@echo "  make conformance-typescript          - Every conformance case against the built kit"
	@echo "  make conformance-packaged-typescript - The npm pack tarball, installed, from outside the checkout"

HELP_SECTIONS += typescript
