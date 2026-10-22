#!/bin/sh
# tools/kit-assets.sh — every shared file a kit reads at run time, copied
# into its own assets/ directory (ADR 16.10.26ae D12). A published package
# carries only its own directory, so a kit that read ../spec or ../templates
# would pass in-repo conformance and fail the first developer who installs
# it. make/quality.mk drives this script; nothing else should.
#
#   kit-assets.sh sync  <assets-dir>...   copy the shared files into each
#   kit-assets.sh check <assets-dir>...   fail on any byte of difference, and
#                                         on any file the source does not have
#
# Run from the repository root. The shared set is the five spec files below
# and the whole of templates/apps/.
set -eu

SPEC_FILES="endpoints.json messages.en.json tools.toml platform-toml.schema.json manifest-warnings.json"

verb=${1:-}
[ $# -ge 2 ] || { echo "usage: kit-assets.sh sync|check <assets-dir>..." >&2; exit 2; }
shift

sync_one() {
  dir=$1
  mkdir -p "$dir"
  for f in $SPEC_FILES; do cp "spec/$f" "$dir/$f"; done
  rm -rf "$dir/templates"
  mkdir -p "$dir/templates"
  cp -R templates/apps "$dir/templates/apps"
  echo "✓ synced $dir"
}

check_one() {
  dir=$1
  status=0
  for f in $SPEC_FILES; do
    cmp -s "spec/$f" "$dir/$f" || { echo "✗ $dir/$f differs from spec/$f: run make sync-kit-assets"; status=1; }
  done
  if ! diff -r templates/apps "$dir/templates/apps" >/dev/null 2>&1; then
    echo "✗ $dir/templates/apps differs from templates/apps: run make sync-kit-assets"
    status=1
  fi
  # Nothing beside the shared set: a stale file would ship and be read.
  for entry in "$dir"/*; do
    name=$(basename "$entry")
    case " $SPEC_FILES templates " in
      *" $name "*) ;;
      *) echo "✗ $dir/$name is not a shared asset: remove it, or add it to tools/kit-assets.sh"; status=1 ;;
    esac
  done
  [ $status -eq 0 ] && echo "✓ $dir matches spec/ and templates/apps/"
  return $status
}

status=0
for dir in "$@"; do
  case $verb in
    sync) sync_one "$dir" ;;
    check) check_one "$dir" || status=1 ;;
    *) echo "usage: kit-assets.sh sync|check <assets-dir>..." >&2; exit 2 ;;
  esac
done
exit $status
