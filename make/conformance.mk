# Conformance (ADR 16.10.26ac D8): one runner drives every kit's built
# binary through conformance/cases against a mock platform.
#
# A kit joins by setting, in its own make/<lang>.mk, CONFORMANCE_CMD_<lang>
# to the command line that runs its built CLI (absolute, and naming the
# resolved interpreter rather than a version-manager shim, because the child
# runs in each case's temp dir with a fresh HOME), and optionally
# CONFORMANCE_PASS_ENV_<lang> to the toolchain variables its launcher needs.
#
# $(CARGO), $(SPEC) and $(LANGS) come from make/config.mk. The runner and its
# arguments are `?=` so the runner's own tests can point them at the built
# binary and a fixture schema instead of a nested `cargo run`.

CONFORMANCE_RUNNER ?= $(CARGO) run -q -p lingara-cli-conformance --
CONFORMANCE_ARGS ?= --cases conformance/cases --schema $(SPEC) --routes conformance/routes.toml
CONFORMANCE_MISSING = $(strip $(foreach l,$(LANGS),$(if $(strip $(CONFORMANCE_CMD_$(l))),,$(l))))

.PHONY: conformance build-conformance-fakes check-conformance-langs check-conformance-coverage test-conformance help-conformance

## Every kit, every case.
conformance: $(addprefix conformance-,$(LANGS))

## The `apps` group's fake cloudflared and fake app (ADR 16.10.26ae D11),
## which the runner finds beside its own binary.
build-conformance-fakes:
	$(CARGO) build -q -p lingara-cli-conformance --bins

## One kit: make conformance-<lang>
conformance-%: build-conformance-fakes
	$(CONFORMANCE_RUNNER) run --lang $* $(CONFORMANCE_ARGS) \
	  $(addprefix --pass-env ,$(CONFORMANCE_PASS_ENV_$*)) -- $(CONFORMANCE_CMD_$*)

## Every language in LANGS has a command. Its own target, run first, so a
## missing command is reported even when the Rust half also fails.
check-conformance-langs:
	@if [ -n "$(CONFORMANCE_MISSING)" ]; then \
	  echo "✗ no CONFORMANCE_CMD_<lang> for: $(CONFORMANCE_MISSING)"; exit 1; fi
	@echo "✓ every language in LANGS has a CONFORMANCE_CMD: $(or $(LANGS),no kits yet)"

## Then D7's rules 1–6; the per-command rules only once a kit exists.
check-conformance-coverage: check-conformance-langs
	$(CONFORMANCE_RUNNER) check-coverage $(CONFORMANCE_ARGS) $(if $(strip $(LANGS)),--strict)

## The runner's own tests, against its scriptable fake CLI.
test-conformance:
	$(CARGO) test -p lingara-cli-conformance

# The packaged lanes' suite (ADR 16.10.26ae D12): the `apps` group's create
# cases and the b3 cases, beside copies of the fixtures and goldens they
# read, so the runner is pointed at a published package's binary and nothing
# in the checkout. $(1) is the directory to assemble it in.
define packaged-suite
mkdir -p $(1)/cases/apps $(1)/cases/b3 && cp conformance/cases/apps/create-*.yaml $(1)/cases/apps/ && \
cp conformance/cases/b3/*.yaml $(1)/cases/b3/ && cp -R conformance/fixtures $(1)/fixtures && cp -R conformance/golden $(1)/golden
endef
PACKAGED_ARGS = --cases $(1)/cases --schema $(SPEC) --routes conformance/routes.toml

help-conformance:
	@echo ""
	@echo "Conformance:"
	@echo "  make conformance                 - Every kit through every case: $(or $(LANGS),no kits yet)"
	@echo "  make conformance-<lang>          - One kit"
	@echo "  make check-conformance-coverage  - Every case parses; every command and ritual has one"
	@echo "  make test-conformance            - The runner's own tests"
	@echo "  make conformance-packaged-<lang> - The packed kit, installed outside the checkout, through the create and b3 cases"
	@echo "  LINGARA_CONFORMANCE_ONLY=<ids>   - Narrow a local run (refused under CI)"

HELP_SECTIONS += conformance
