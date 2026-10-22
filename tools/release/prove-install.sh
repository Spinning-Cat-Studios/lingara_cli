#!/bin/sh
# prove-install.sh <id> <tag> [--deadline 15m] (ADR 22.10.26s §3; the app
# SDK's tools/release/prove-install.sh skeleton, from its ADR 30.9.26am D8,
# itself the clients' ADR 29.9.26v D8.)
#
# The app SDK proves a library; this proves a command. Each attempt runs the
# install line the site shows (`release-manifest install-line`, from
# snippets/<id>/install.*) into a scratch prefix outside the checkout, then
# runs the entry's binary there and requires exactly `lingara <semver> (<id>)`,
# the line check-version-<id> holds every build to. Registries propagate at
# different speeds, so it retries every 60 seconds until its deadline, then
# exits 1 with `install proof failed: <id> <line>`.
#
# Every attempt starts cold: a fresh scratch directory holding the prefix and
# every tool's cache, because each tool remembers a failed lookup. Go's line
# is Homebrew's, which has no scratch prefix: it installs, runs and then
# uninstalls the formula (release.yml's prove-go, on macOS).
set -eu

usage() { echo "usage: prove-install.sh <id> <tag> [--deadline <N>m|<N>s]" >&2; exit 2; }

id=${1:-}
tag=${2:-}
[ -n "$id" ] && [ -n "$tag" ] || usage
shift 2
deadline=15m
while [ $# -gt 0 ]; do
    case "$1" in
        --deadline) [ $# -ge 2 ] || usage; deadline=$2; shift 2 ;;
        *) usage ;;
    esac
done
case "$deadline" in
    *m) seconds=$(( ${deadline%m} * 60 )) ;;
    *s) seconds=${deadline%s} ;;
    *) seconds=$deadline ;;
esac

command -v release-manifest >/dev/null 2>&1 ||
    { echo "prove-install.sh: release-manifest is not on PATH (make install-release-manifest)" >&2; exit 2; }
line=$(release-manifest install-line "$id" "$tag")
# The publish jobs are not matrix jobs, so the binary comes from the matrix
# row itself; every CLI entry sets it (22.10.26r W9).
binary=$(release-manifest matrix "$tag" | sed -n 's/^matrix=//p' |
    jq -r --arg id "$id" '.include[] | select(.id == $id) | .binary // empty')
[ -n "$binary" ] || { echo "prove-install.sh: languages.toml sets no binary for $id" >&2; exit 2; }
want="lingara ${tag#v} ($id)"

# One attempt in scratch directory $1: install, then print the binary's
# --version. It runs as an `if` condition, where `set -e` does not apply, so
# every step is chained with `&&`. The line itself runs under `sh -e`.
attempt() {
    scratch=$1
    cd "$scratch" || return 1
    case "$id" in
        rust)
            CARGO_HOME="$scratch/cargo" CARGO_INSTALL_ROOT="$scratch/root" sh -ec "$line" &&
            "$scratch/root/bin/$binary" --version ;;
        typescript)
            npm_config_cache="$scratch/npm" npm_config_prefix="$scratch/root" npm_config_prefer_online=true sh -ec "$line" &&
            "$scratch/root/bin/$binary" --version ;;
        ruby)
            GEM_HOME="$scratch/gems" GEM_PATH="$scratch/gems" sh -ec "$line" &&
            GEM_HOME="$scratch/gems" GEM_PATH="$scratch/gems" "$scratch/gems/bin/$binary" --version ;;
        php)
            COMPOSER_HOME="$scratch/composer" COMPOSER_CACHE_DIR="$scratch/composer-cache" sh -ec "$line" &&
            "$scratch/composer/vendor/bin/$binary" --version ;;
        java|kotlin)
            JBANG_DIR="$scratch/jbang" JBANG_REPO="$scratch/m2" sh -ec "$line" &&
            JBANG_DIR="$scratch/jbang" JBANG_REPO="$scratch/m2" "$scratch/jbang/bin/$binary" --version ;;
        go)
            HOMEBREW_NO_AUTO_UPDATE=1 sh -ec "$line" &&
            "$(brew --prefix)/bin/$binary" --version &&
            brew uninstall --formula "$binary" >/dev/null ;;
        *)
            echo "prove-install.sh: no proof for $id" >&2; exit 2 ;;
    esac
}

end=$(( $(date +%s) + seconds ))
while :; do
    scratch=$(mktemp -d "${TMPDIR:-/tmp}/prove-install.XXXXXX")
    if out=$(attempt "$scratch" 2>"$scratch.err") && [ "$(printf '%s\n' "$out" | tail -n 1)" = "$want" ]; then
        rm -rf "$scratch" "$scratch.err"
        echo "✓ install proof: $id $line → $want"
        exit 0
    fi
    echo "prove-install.sh: $id attempt printed '${out:-}', not '$want'" >&2
    tail -n 20 "$scratch.err" >&2 || true
    rm -rf "$scratch" "$scratch.err"
    if [ $(( $(date +%s) + 60 )) -gt "$end" ]; then
        echo "install proof failed: $id $line" >&2
        exit 1
    fi
    echo "prove-install.sh: $id not installable yet; retrying in 60s" >&2
    sleep 60
done
