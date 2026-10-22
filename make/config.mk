# Shared variables (ADR 16.10.26ab D8).
#
# LANGS starts empty. Each make/<lang>.mk appends `LANGS += <lang>`, sets
# TREE_CMD_<lang> (the kit's command line, which tree-diff runs with
# `__tree` appended), and defines test-<lang>; the aggregates in
# make/quality.mk run over whatever has joined.

LANGS :=
HELP_SECTIONS :=

CARGO ?= cargo

# The command tree: the one source the docs, every kit and conformance are
# checked against (ADR 16.10.26ab D2).
SPEC := spec/cli.schema.json

CLI_SPEC := $(CARGO) run -q -p cli-spec --
