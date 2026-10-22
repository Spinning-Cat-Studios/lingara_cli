# The aggregates over $(LANGS) (ADR 16.10.26ab D4, D8).
#
# Read after every make/<lang>.mk (see the Makefile): these prerequisite
# lists are expanded when this file is read. A kit never edits this file; it
# appends itself to LANGS, sets TREE_CMD_<lang>, and makes check-tree-<lang>
# depend on its own build target.

.PHONY: check-tree test check help-quality

## Every kit's registered tree matches the schema, both directions.
check-tree: $(addprefix check-tree-,$(LANGS))

## A language in LANGS with no TREE_CMD_<lang> is red, naming it.
check-tree-%:
	@test -n "$(TREE_CMD_$*)" || { echo "✗ $* is in LANGS but sets no TREE_CMD_$*"; exit 1; }
	$(CLI_SPEC) tree-diff --spec $(SPEC) -- $(TREE_CMD_$*)

test: test-spec test-conformance $(addprefix test-,$(LANGS))

# Every shared file a kit reads at run time (ADR 16.10.26ae D12): the five
# spec files tools/kit-assets.sh names and templates/apps/, copied into each
# kit's own assets/ directory and committed, because a published package
# carries only its own directory. One row per kit; 16.10.26af D8 adds five.
# A row is read only once its kit is in LANGS. Go's is a subdirectory of the
# embedding package, because //go:embed needs a .go file beside the files
# it embeds and the check refuses anything beside the shared set.
KIT_ASSETS_rust := rust/assets
KIT_ASSETS_typescript := typescript/assets
KIT_ASSETS_go := go/internal/assets/_shared
KIT_ASSETS_java := java/src/main/resources/lingara
KIT_ASSETS_kotlin := kotlin/src/main/resources/lingara
KIT_ASSETS_ruby := ruby/lib/lingara/cli/assets
KIT_ASSETS_php := php/assets
KIT_ASSETS_MISSING = $(strip $(foreach l,$(LANGS),$(if $(strip $(KIT_ASSETS_$(l))),,$(l))))
KIT_ASSETS_DIRS = $(foreach l,$(LANGS),$(KIT_ASSETS_$(l)))

.PHONY: check-kit-assets-langs sync-kit-assets check-kit-assets check-kit-assets-selftest

## A language in LANGS with no asset row is red, naming it.
check-kit-assets-langs:
	@if [ -n "$(KIT_ASSETS_MISSING)" ]; then \
	  echo "✗ no KIT_ASSETS_<lang> row for: $(KIT_ASSETS_MISSING)"; exit 1; fi

## Copy spec/'s shared files and templates/apps/ into every kit's assets/.
sync-kit-assets: check-kit-assets-langs
	@sh tools/kit-assets.sh sync $(KIT_ASSETS_DIRS)

## Every kit's assets/ is byte-identical to its sources, and holds nothing else.
check-kit-assets: check-kit-assets-langs
	@sh tools/kit-assets.sh check $(KIT_ASSETS_DIRS)

## The check bites: a one-byte change to a scratch copy of each kit's assets
## must turn it red.
check-kit-assets-selftest: check-kit-assets-langs
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && status=0 && \
	for d in $(KIT_ASSETS_DIRS); do \
	  scratch="$$tmp/$$(echo $$d | tr / _)"; cp -R "$$d" "$$scratch"; \
	  printf '#' >> "$$scratch/tools.toml"; \
	  if sh tools/kit-assets.sh check "$$scratch" >/dev/null 2>&1; then \
	    echo "✗ check-kit-assets passed a changed copy of $$d"; status=1; \
	  else echo "✓ check-kit-assets fails a one-byte change to $$d"; fi; \
	done; exit $$status

# `lingara --version` prints exactly `lingara <VERSION> (<kit>)`, <kit> being
# the languages.toml id (ADR 16.10.26af D1, AC7). The schema cannot carry it,
# since the root --version is the framework's. A kit makes check-version-<lang>
# depend on its build, as it does check-tree-<lang>.
KIT_VERSION := $(shell cat VERSION)

.PHONY: check-version

## Every kit's --version line.
check-version: $(addprefix check-version-,$(LANGS))

## One kit's: make check-version-<lang>
check-version-%:
	@test -n "$(CONFORMANCE_CMD_$*)" || { echo "✗ $* is in LANGS but sets no CONFORMANCE_CMD_$*"; exit 1; }
	@out=$$($(CONFORMANCE_CMD_$*) --version 2>&1); \
	if printf '%s\n' "$$out" | grep -Eqx 'lingara $(subst .,\.,$(KIT_VERSION)) \($*\)'; then \
	  echo "✓ $* prints: $$out"; \
	else echo "✗ $* --version printed '$$out', not 'lingara $(KIT_VERSION) ($*)'"; exit 1; fi

# 16.10.26af AC14: the public metadata names lingara_cli and nothing private.
# The poms and the formula are generated first, so the scan reads what
# Central and the tap would receive; goreleaser is mise.toml's pin.
GORELEASER ?= goreleaser
JVM_POMS := java/build/publications/maven/pom-default.xml kotlin/build/publications/maven/pom-default.xml

.PHONY: check-public-metadata

## The install pages, gemspec, composer.json, poms and formula.
check-public-metadata:
	@./gradlew -q :java:generatePomFileForMavenPublication :kotlin:generatePomFileForMavenPublication
	@trap 'rm -rf dist' EXIT && $(GORELEASER) release --snapshot --clean --skip=publish >/dev/null 2>&1 && \
	sh tools/public-metadata-scan.sh dist/homebrew/Formula/lingara.rb $(JVM_POMS)

# ADR 22.10.26s AC3: bump and the --version oracle agree. A scratch copy of
# the tree, without its build outputs, is bumped to BUMP_PROBE_VERSION and
# must pass check-release-tree and every check-version-<lang>, without
# touching the checkout. By hand: it builds all seven kits.
BUMP_PROBE_VERSION := 9.9.9-rc.1

.PHONY: test-bump-version

## A scratch copy bumped to 9.9.9-rc.1 passes check-release-tree and check-version.
test-bump-version: $(RELEASE_MANIFEST)
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	tar --exclude=./target --exclude=node_modules --exclude=build --exclude=dist --exclude=vendor \
	    --exclude=.gradle --exclude=.kotlin --exclude=./.git --exclude=./.tools -cf - . | (cd "$$tmp" && tar -xf -) && \
	(cd "$$tmp" && $(abspath $(RELEASE_MANIFEST)) bump $(BUMP_PROBE_VERSION)) && \
	npm --prefix "$$tmp/typescript" ci --silent && \
	$(MAKE) -C "$$tmp" check-release-tree check-version RELEASE_MANIFEST=$(abspath $(RELEASE_MANIFEST)) && \
	echo "✓ a copy bumped to $(BUMP_PROBE_VERSION) passes check-release-tree and check-version"

## What every commit must pass (conformance coverage since ADR 16.10.26ac;
## languages.toml against the tree, LANGS and release.yml, and the notes
## scan, since ADR 22.10.26s — make/release.mk's lanes). test-bump-version
## is not here: it builds all seven kits in a scratch copy.
check: check-schema check-docs check-kit-assets check-tree check-version check-conformance-coverage \
       check-release-tree check-release-langs check-release-notes-scan

help-quality:
	@echo ""
	@echo "Quality:"
	@echo "  make check-tree         - Every kit's tree matches the schema: $(or $(LANGS),no kits yet)"
	@echo "  make check-tree-<lang>  - One kit's"
	@echo "  make check-version     - Every kit prints lingara <VERSION> (<kit>)"
	@echo "  make check-public-metadata - Install pages, gemspec, composer.json, poms, formula name only lingara_cli"
	@echo "  make test               - cli-spec's and the conformance runner's tests, and every kit's"
	@echo "  make sync-kit-assets    - Copy spec/'s shared files and templates/apps/ into each kit's assets/"
	@echo "  make check-kit-assets   - Every kit's assets/ matches its sources"
	@echo "  make check-kit-assets-selftest - check-kit-assets fails a one-byte change"
	@echo "  make test-bump-version  - A scratch copy bumped to $(BUMP_PROBE_VERSION) passes (slow)"
	@echo "  make check              - Schema, docs, kit assets, trees, conformance coverage and languages.toml"

HELP_SECTIONS += quality
