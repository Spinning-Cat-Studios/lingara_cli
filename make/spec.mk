# The schema and its tool (ADR 16.10.26ab D2).

.PHONY: check-schema test-spec help-spec

## The closed shape and every rule of D2, over the schema and
## spec/messages.en.json beside it.
check-schema:
	$(CLI_SPEC) check --spec $(SPEC)

test-spec:
	$(CARGO) test -p cli-spec

help-spec:
	@echo ""
	@echo "Spec:"
	@echo "  make check-schema   - $(SPEC) and messages.en.json hold every rule"
	@echo "  make test-spec      - cli-spec's own tests"

HELP_SECTIONS += spec
