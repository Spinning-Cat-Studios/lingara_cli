# Release (ADR 22.10.26s; the clients' process, as the app SDK ported it in
# its ADR 30.9.26am D8). Ships with the repository.
#
# `languages.toml` is what a release publishes. tools/release-manifest is not
# copied here: it is installed from the public lingara_api_clients repository
# at a pinned tag, into .tools/ (gitignored), the first time a target needs it.
# The CLI has no codegen, so bump-version stamps and nothing else.

# The first clients tag carrying ADR 22.10.26r's W8–W10 (its AC10). Confirmed
# 2026-10-22: v0.2.0-alpha.2 was never published, so alpha.3 is the first.
RELEASE_MANIFEST_TAG := v0.2.0-alpha.3
RELEASE_MANIFEST_REPO := https://github.com/Spinning-Cat-Studios/lingara_api_clients
RELEASE_MANIFEST_ROOT := .tools/release-manifest-$(RELEASE_MANIFEST_TAG)
RELEASE_MANIFEST := $(RELEASE_MANIFEST_ROOT)/bin/release-manifest

.PHONY: install-release-manifest bump-version release-matrix release-build release-conformance check-release-tree check-release-langs check-release-notes-scan help-release

$(RELEASE_MANIFEST):
	$(CARGO) install --git $(RELEASE_MANIFEST_REPO) --tag $(RELEASE_MANIFEST_TAG) --locked --root $(RELEASE_MANIFEST_ROOT) release-manifest

## The pinned release-manifest; CI passes RELEASE_MANIFEST_ROOT to choose
## where, and puts its bin/ on PATH for tools/release/*.sh.
install-release-manifest: $(RELEASE_MANIFEST)

## Write V to VERSION, every version file and every `since = "next"`, then
## let Cargo.lock's lingara-cli row follow rust/Cargo.toml.
bump-version: $(RELEASE_MANIFEST)
	@test -n "$(V)" || { echo "Usage: make bump-version V=X.Y.Z[-pre.N]" >&2; exit 2; }
	$(RELEASE_MANIFEST) bump $(V)
	$(CARGO) update --workspace --offline

## The GitHub Actions matrix release.yml fans out over, for TAG.
release-matrix: $(RELEASE_MANIFEST)
	@test -n "$(TAG)" || { echo "Usage: make release-matrix TAG=vX.Y.Z[-pre.N]" >&2; exit 2; }
	@$(RELEASE_MANIFEST) matrix $(TAG)

## One kit's release build, ID being its languages.toml id: the kit's own
## targets, so the release build is the local build.
release-build:
	@test -n "$(ID)" || { echo "Usage: make release-build ID=<language id>" >&2; exit 2; }
	$(MAKE) build-$(ID) test-$(ID) check-version-$(ID)

## One kit against every conformance case, then as its package ships (every
## kit has a conformance-packaged-<id>, 16.10.26af AC15).
release-conformance:
	@test -n "$(ID)" || { echo "Usage: make release-conformance ID=<language id>" >&2; exit 2; }
	$(MAKE) conformance-$(ID) conformance-packaged-$(ID)

## languages.toml agrees with the tree and with release.yml.
check-release-tree: $(RELEASE_MANIFEST)
	$(RELEASE_MANIFEST) check

## The ids languages.toml lists are $(LANGS), as a set (ADR 16.10.26ab D6).
check-release-langs:
	@listed=$$(grep -E '^id = "[a-z]+"$$' languages.toml | sed 's/^id = "\(.*\)"$$/\1/' | sort | tr '\n' ' '); \
	wanted=$$(printf '%s\n' $(LANGS) | sort | tr '\n' ' '); \
	test "$$listed" = "$$wanted" || { echo "✗ languages.toml lists [ $$listed] but LANGS is [ $$wanted]"; exit 1; }; \
	echo "✓ languages.toml lists exactly LANGS: $$listed"

## The scan github-release runs on the notes (16.10.26af AC22): it refuses a
## body naming a private repository and passes a clean one. The name is
## assembled at run time, so this shipped file never spells it.
check-release-notes-scan:
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	printf 'Fixed\n\n- built from lingara_cli%s\n' _private > "$$tmp/leak.md" && \
	printf 'Added\n\n- The lingara CLI in seven languages\n' > "$$tmp/clean.md" && \
	if sh tools/release/notes-scan.sh "$$tmp/leak.md" >/dev/null 2>&1; then \
	  echo "✗ notes-scan.sh passed a body naming a private repository"; exit 1; fi && \
	sh tools/release/notes-scan.sh "$$tmp/clean.md" && \
	echo "✓ notes-scan.sh refuses a leak and passes a clean body"

help-release:
	@echo ""
	@echo "Release:"
	@echo "  make install-release-manifest    - The pinned release-manifest, into .tools/"
	@echo "  make bump-version V=<ver>        - Write the version everywhere; Cargo.lock follows"
	@echo "  make release-matrix TAG=<t>      - The release matrix for a tag"
	@echo "  make release-build ID=<id>       - One kit's build, tests and --version"
	@echo "  make release-conformance ID=<id> - One kit's conformance suite, then packaged"
	@echo "  make check-release-tree          - languages.toml agrees with the tree"
	@echo "  make check-release-langs         - languages.toml's ids are LANGS"
	@echo "  make check-release-notes-scan    - The release-notes leak scan bites"

HELP_SECTIONS += release
