# The Rust kit (ADR 16.10.26ad D9): crate `lingara-cli`, binary `lingara`,
# on clap. Both command lines are absolute, because 16.10.26ae's project
# cases run the CLI from a fixture's temp directory, where a relative path
# resolves to nothing. The binary is the workspace's target/.

LANGS += rust

RUST_KIT_BIN := $(CURDIR)/target/debug/lingara
# tree-diff appends `__tree` itself (make/config.mk).
TREE_CMD_rust = $(RUST_KIT_BIN)
CONFORMANCE_CMD_rust = $(RUST_KIT_BIN)

.PHONY: build-rust test-rust test-rust-supervise conformance-packaged-rust help-rust

build-rust:
	$(CARGO) build -q -p lingara-cli

## clippy with warnings denied, then the kit's unit tests.
test-rust:
	$(CARGO) clippy -q -p lingara-cli --all-targets -- -D warnings
	$(CARGO) test -q -p lingara-cli

## The process-group tests alone: what the Windows runner proves (16.10.26ae AC24).
test-rust-supervise:
	$(CARGO) test -q -p lingara-cli supervise

## The crate as crates.io would ship it (ADR 16.10.26ae D12): packed, unpacked
## and installed into a scratch root, then run from outside the checkout
## through the create and b3 cases. A kit that read ../spec or ../templates
## at run time fails here.
conformance-packaged-rust: build-conformance-fakes
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	$(CARGO) package -q -p lingara-cli --allow-dirty --no-verify --target-dir "$$tmp/target" && \
	mkdir -p "$$tmp/unpacked" && tar -xzf "$$tmp"/target/package/lingara-cli-*.crate -C "$$tmp/unpacked" && \
	$(CARGO) install -q --path "$$(ls -d "$$tmp"/unpacked/lingara-cli-*)" --root "$$tmp/root" --target-dir "$$tmp/target" && \
	$(call packaged-suite,$$tmp/suite) && \
	$(CONFORMANCE_RUNNER) run --lang rust $(call PACKAGED_ARGS,$$tmp/suite) -- "$$tmp/root/bin/lingara"

# Both run the built binary, so they build it first.
check-tree-rust check-version-rust conformance-rust: build-rust

help-rust:
	@echo ""
	@echo "Rust kit:"
	@echo "  make build-rust                 - Build target/debug/lingara"
	@echo "  make test-rust                  - clippy -D warnings, then cargo test"
	@echo "  make test-rust-supervise        - The process-group tests alone (the Windows leg)"
	@echo "  make conformance-rust           - Every conformance case against the built binary"
	@echo "  make conformance-packaged-rust  - The packed crate, installed, from outside the checkout"

HELP_SECTIONS += rust
