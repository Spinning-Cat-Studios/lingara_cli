#!/bin/sh
# probe.sh <id> <tag> — is this version already out? (ADR 22.10.26s §3; the
# app SDK's tools/release/probe.sh skeleton, from its ADR 30.9.26am D8, itself
# the clients' ADR 29.9.26v D6.)
#
# Prints `published` when every URL `release-manifest probe` names answers 200,
# `absent` when any answers 404, and fails on any other status, so a registry
# fault never reads as "publish". crates.io's API refuses a request with no
# User-Agent (403), so one is always sent.
#
# Go is the exception. proxy.golang.org cannot resolve getlingara.com/cli
# (16.10.26af D4), so Go's "already out" is the tap's Formula/lingara.rb
# naming the version, read through the GitHub contents API (GH_TOKEN, if set,
# lifts the anonymous rate limit).
set -eu

id=${1:?usage: probe.sh <id> <tag>}
tag=${2:?usage: probe.sh <id> <tag>}
agent='lingara-release (https://getlingara.com)'

if [ "$id" = go ]; then
    formula=https://api.github.com/repos/Spinning-Cat-Studios/homebrew-tap/contents/Formula/lingara.rb
    body=$(mktemp)
    trap 'rm -f "$body"' EXIT
    auth=
    [ -z "${GH_TOKEN:-}" ] || auth="Authorization: Bearer $GH_TOKEN"
    status=$(curl -s -o "$body" -w '%{http_code}' -A "$agent" -H 'Accept: application/vnd.github.raw' \
        ${auth:+-H "$auth"} "$formula")
    case "$status" in
        200) if grep -qF "/download/$tag/" "$body"; then echo published; else echo absent; fi ;;
        404) echo absent ;;
        *) echo "probe: $formula answered $status; neither published (200) nor absent (404)" >&2; exit 1 ;;
    esac
    exit 0
fi

if command -v release-manifest >/dev/null 2>&1; then
    urls=$(release-manifest probe "$id" "$tag")
else
    echo "probe.sh: release-manifest is not on PATH (make install-release-manifest)" >&2; exit 2
fi

state=published
for url in $urls; do
    status=$(curl -s -o /dev/null -w '%{http_code}' -A "$agent" "$url")
    case "$status" in
        200) ;;
        404) state=absent ;;
        *) echo "probe: $url answered $status; neither published (200) nor absent (404)" >&2; exit 1 ;;
    esac
done
echo "$state"
