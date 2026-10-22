#!/bin/sh
# The size budgets RuboCop has no cop for (ADR 16.10.26af; the numbers are
# .code-health/thresholds.toml's, as the TypeScript kit's eslint.config.js
# and tools/check-web-folders.sh-style guards carry them): at most 300 lines
# per source file, 15 methods (`def`) per file and 15 files per directory.
# The assets are committed copies of spec/ and templates/, never the kit's.
#
#   budgets.sh <dir>
set -eu
root=${1:?usage: budgets.sh <dir>}
status=0
for f in $(find "$root" -name '*.rb' -not -path '*/assets/*'); do
  lines=$(wc -l < "$f" | tr -d ' ')
  defs=$(grep -cE '^[[:space:]]*def ' "$f" || true)
  [ "$lines" -le 300 ] || { echo "✗ $f: $lines lines (budget 300)"; status=1; }
  [ "$defs" -le 15 ] || { echo "✗ $f: $defs methods (budget 15)"; status=1; }
done
for d in $(find "$root" -type d -not -path '*/assets*'); do
  n=$(find "$d" -maxdepth 1 -type f | wc -l | tr -d ' ')
  [ "$n" -le 15 ] || { echo "✗ $d: $n files (budget 15)"; status=1; }
done
[ $status -eq 0 ] && echo "✓ $root within 300 lines and 15 methods per file, 15 files per directory"
exit $status
