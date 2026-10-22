#!/bin/sh
# notes-scan.sh <file> — release notes name no private repository or home
# path (ADR 22.10.26s §3; 16.10.26af AC22). github-release runs it on the
# tagged commit's message body before `gh release create`.
#
# The pattern is tools/public-metadata-scan.sh's LEAKS, read from that file so
# the two cannot part. A separate script because that scan takes a fixed
# file list. Exits 1 naming each matching line. Run from the repository root.
set -eu

file=${1:?usage: notes-scan.sh <file>}
[ -f "$file" ] || { echo "notes-scan.sh: $file is missing" >&2; exit 2; }

leaks=$(sed -n "s/^LEAKS='\(.*\)'\$/\1/p" tools/public-metadata-scan.sh)
[ -n "$leaks" ] || { echo "notes-scan.sh: no LEAKS pattern in tools/public-metadata-scan.sh" >&2; exit 2; }

if grep -nE "$leaks" "$file"; then
    echo "✗ the release notes name a private repository or a home path" >&2
    exit 1
fi
echo "✓ the release notes name nothing private"
