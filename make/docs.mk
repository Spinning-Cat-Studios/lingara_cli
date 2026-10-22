# The reference docs (ADR 16.10.26ab D5).
#
# docs/generated/ is rendered from the schema alone and committed; the site
# reads it from a release tag. `check-docs` renders to a temporary directory
# and fails on any byte of difference, so the reference cannot drift from
# the schema.

OUT ?= docs/generated

.PHONY: gen-docs check-docs help-docs

gen-docs:
	$(CLI_SPEC) docs --spec $(SPEC) --out $(OUT)

check-docs:
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	  $(CLI_SPEC) docs --spec $(SPEC) --out "$$tmp" >/dev/null && \
	  if diff -r docs/generated "$$tmp"; then \
	    echo "✓ docs/generated is current"; \
	  else \
	    echo "✗ docs/generated differs from $(SPEC): run make gen-docs"; exit 1; \
	  fi

help-docs:
	@echo ""
	@echo "Docs:"
	@echo "  make gen-docs [OUT=<dir>] - Render the reference pages (default docs/generated)"
	@echo "  make check-docs           - Fail when docs/generated differs from the schema"

HELP_SECTIONS += docs
