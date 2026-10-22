# The Go kit (ADR 16.10.26af, wave 1): module getlingara.com/cli, binary
# `lingara`, on cobra. Both command lines are absolute, because 16.10.26ae's
# project cases run the CLI from a fixture's temp directory, where a
# relative path resolves to nothing. The binary is go/dist/, gitignored, and
# it needs no pass-env: it is a static binary, not an interpreter.
#
# The version is VERSION, stamped in at build time by -ldflags (D1); the kit
# never reads a file above go/ at run time.
#
# go/internal/assets/_shared/ holds the app templates, and the Go templates
# there are Go files no build of this module may compile (their imports are
# `{{name}}` placeholders). The leading underscore keeps the go tool out of
# that directory, so `./...` and `go mod tidy` both work.

# Listed with its languages.toml entry, once every conformance case passes
# (ADR 16.10.26af D3).
LANGS += go

GO ?= go
GO_DIR := $(CURDIR)/go
GO_KIT_BIN := $(GO_DIR)/dist/lingara
GO_LDFLAGS = -s -w -X getlingara.com/cli/internal/commands.Version=$(shell cat VERSION)
GO_PKGS = ./...
# golangci-lint, pinned and run through `go run`, so no global install is
# needed and the version moves only with this line. v2.8.0 is the newest
# that builds with the Go 1.24 toolchain mise pins (v2.9 needs Go 1.25).
GOLANGCI_LINT := $(GO) run github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.8.0

# tree-diff appends `__tree` itself (make/config.mk).
TREE_CMD_go = $(GO_KIT_BIN)
CONFORMANCE_CMD_go = $(GO_KIT_BIN)

.PHONY: build-go test-go lint-go test-go-supervise conformance-packaged-go help-go

## go/dist/lingara, with VERSION stamped in.
build-go:
	cd $(GO_DIR) && CGO_ENABLED=0 $(GO) build -trimpath -ldflags "$(GO_LDFLAGS)" -o dist/lingara ./cmd/lingara

## go vet (this platform and Windows), golangci-lint at the repo's budgets
## (go/.golangci.yml), then the unit tests.
test-go: lint-go
	cd $(GO_DIR) && $(GO) test $(GO_PKGS)

lint-go:
	cd $(GO_DIR) && $(GO) vet $(GO_PKGS)
	cd $(GO_DIR) && GOOS=windows $(GO) vet $(GO_PKGS)
	cd $(GO_DIR) && $(GOLANGCI_LINT) run ./...

## The process-group and Job Object tests alone: what the Windows runner
## proves (16.10.26ae AC24; ADR 16.10.26af's go-windows-unit job).
test-go-supervise:
	cd $(GO_DIR) && $(GO) test ./internal/supervise/

# 16.10.26af AC15: the kit as goreleaser ships it. Only go/ is copied to a
# scratch directory and built there, so a kit that read ../spec or
# ../templates fails to compile or to scaffold; then the binary runs from
# outside the checkout through the create and b3 cases.
conformance-packaged-go: build-conformance-fakes
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	cp -R $(GO_DIR) "$$tmp/go" && rm -rf "$$tmp/go/dist" && \
	(cd "$$tmp/go" && CGO_ENABLED=0 $(GO) build -trimpath -ldflags "$(GO_LDFLAGS)" -o "$$tmp/bin/lingara" ./cmd/lingara) && \
	$(call packaged-suite,$$tmp/suite) && \
	$(CONFORMANCE_RUNNER) run --lang go $(call PACKAGED_ARGS,$$tmp/suite) -- "$$tmp/bin/lingara"

# 16.10.26af AC8: the release artefacts from .goreleaser.yaml, built twice.
# Six archives, SHA256SUMS over them, a formula whose sha256 values are
# those archives', binaries that name getlingara.com/cli and no private
# path, no CHANGELOG, and a second build of the same commit with a
# byte-identical SHA256SUMS. goreleaser is mise.toml's pin.
#
# ADR 22.10.26s AC5: the archive names are languages.toml's. The snapshot's
# tag is read from dist/metadata.json, and every name `release-manifest
# artefact go <that tag>` prints must be in dist/, so a drift between
# .goreleaser.yaml's name_template and `uploaded` fails here rather than in
# release.yml's build leg. The prerequisite is the phony target, not
# $(RELEASE_MANIFEST): this file is read before make/release.mk sets it.
#
# goreleaser resolves through mise as typescript.mk's node does, so the lane
# also runs from a shell whose PATH carries another repo's mise tools (a
# batch driver's), not only from one activated here.
GORELEASER ?= $(or $(shell mise which goreleaser 2>/dev/null),goreleaser)
GO_SNAPSHOT = $(GORELEASER) release --snapshot --clean --skip=publish >/dev/null

.PHONY: check-release-snapshot-go
check-release-snapshot-go: install-release-manifest
	@grep -A1 -E '^(release|changelog):' .goreleaser.yaml | grep -q 'disable: true' || { echo "✗ .goreleaser.yaml must disable release and changelog"; exit 1; }
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp" dist' EXIT && \
	$(GO_SNAPSHOT) && cp dist/SHA256SUMS "$$tmp/first" && \
	test "$$(ls dist/lingara_*_*_*.tar.gz dist/lingara_*_*_*.zip | wc -l | tr -d ' ')" = 6 || { echo "✗ not six archives"; exit 1; }; \
	test "$$(wc -l < dist/SHA256SUMS | tr -d ' ')" = 6 || { echo "✗ SHA256SUMS does not cover six archives"; exit 1; }; \
	for sum in $$(sed -n 's/.*sha256 "\(.*\)"/\1/p' dist/homebrew/Formula/lingara.rb); do \
	  grep -q "^$$sum " dist/SHA256SUMS || { echo "✗ the formula's sha256 $$sum is no archive's"; exit 1; }; done; \
	test -z "$$(find dist -name 'CHANGELOG*')" || { echo "✗ dist/ holds a CHANGELOG"; exit 1; }; \
	tag=$$(sed -n 's/.*"tag": *"\([^"]*\)".*/\1/p' dist/metadata.json | head -n 1); \
	test -n "$$tag" || { echo "✗ dist/metadata.json names no tag"; exit 1; }; \
	names=$$($(RELEASE_MANIFEST) artefact go "$$tag" | cut -f2); \
	test -n "$$names" || { echo "✗ languages.toml uploads nothing for go"; exit 1; }; \
	for name in $$names; do \
	  test -f "dist/$$name" || { echo "✗ languages.toml uploads $$name, which goreleaser did not write"; exit 1; }; done; \
	for bin in dist/lingara_*/lingara*; do \
	  info=$$($(GO) version -m "$$bin"); \
	  echo "$$info" | grep -q 'getlingara.com/cli' || { echo "✗ $$bin does not name getlingara.com/cli"; exit 1; }; \
	  ! echo "$$info" | grep -qE '_private|_staging' || { echo "✗ $$bin names a private path"; exit 1; }; done; \
	$(GO_SNAPSHOT) && cmp -s "$$tmp/first" dist/SHA256SUMS || { echo "✗ a second build's SHA256SUMS differs"; exit 1; }; \
	echo "✓ six archives named as languages.toml uploads them, SHA256SUMS, the formula and a byte-identical rebuild"

# 16.10.26af AC6, AC7: both run the built binary, so they build it first.
check-tree-go check-version-go conformance-go: build-go

help-go:
	@echo ""
	@echo "Go kit:"
	@echo "  make build-go                 - Build go/dist/lingara"
	@echo "  make test-go                  - go vet (+ Windows), golangci-lint, then go test"
	@echo "  make test-go-supervise        - The process-group tests alone (the Windows leg)"
	@echo "  make conformance-go           - Every conformance case against the built binary"
	@echo "  make conformance-packaged-go  - go/ alone, built in a scratch dir, from outside the checkout"
	@echo "  make check-release-snapshot-go - goreleaser --snapshot twice: archives, SHA256SUMS, formula, rebuild"

HELP_SECTIONS += go
