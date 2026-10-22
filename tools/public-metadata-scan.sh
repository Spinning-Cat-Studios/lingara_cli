#!/bin/sh
# tools/public-metadata-scan.sh — no public artefact or page names the
# private or staging repository or a home path (ADR 16.10.26af AC14, D6).
# make/quality.mk's check-public-metadata drives this script, after it has
# generated the two poms and the Homebrew formula it reads.
#
#   public-metadata-scan.sh <formula> <pom>...
#
# Reads the install pages, the gemspec, composer.json, the poms and the
# formula. Each must match none of Public 16.10.26ag D2's patterns, each
# package's metadata must name the public repository, and the Go page must
# carry no `go install` (D4). Run from the repository root.
set -eu

PUBLIC=Spinning-Cat-Studios/lingara_cli
LEAKS='_private|_staging|/Users/|/home/'

[ $# -ge 2 ] || { echo "usage: public-metadata-scan.sh <formula> <pom>..." >&2; exit 2; }
formula=$1
shift

status=0
for f in docs/install/*.md ruby/lingara-cli.gemspec php/composer.json "$formula" "$@"; do
  [ -f "$f" ] || { echo "✗ $f is missing"; status=1; continue; }
  if grep -nE "$LEAKS" "$f"; then echo "✗ $f names a private repository or a home path"; status=1; fi
done
# Metadata names the public repository; the pages need not.
for f in ruby/lingara-cli.gemspec php/composer.json "$formula" "$@"; do
  [ -f "$f" ] || continue
  grep -q "github.com/$PUBLIC" "$f" || { echo "✗ $f does not name github.com/$PUBLIC"; status=1; }
done
if grep -n 'go install' docs/install/go.md; then echo "✗ docs/install/go.md names go install"; status=1; fi

[ $status -eq 0 ] && echo "✓ install pages, gemspec, composer.json, poms and formula name only $PUBLIC"
exit $status
