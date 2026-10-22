#!/bin/sh
# tools/jvm-dry-run.sh — the Java and Kotlin kits' packaging checks (ADR
# 16.10.26af D5). make/java.mk drives this script; nothing else should.
#
#   jvm-dry-run.sh staging <version>   16.10.26af AC9: publish both artefacts
#       into build/staging-repo and require, for each, the jar, -sources,
#       -javadoc, -all and the pom, each with its .asc. With no signing key in
#       the environment it signs with a throwaway key generated here, so the
#       real key never leaves lingara_cli's release environment.
#   jvm-dry-run.sh shaded <version>    16.10.26af AC10: each -all jar prints
#       its --version with an empty local Maven repository, and both main jars
#       carry Main-Class (jbang runs the main jar).
#
# Run from the repository root, with JAVA_HOME at the JDK the build uses.
set -eu

KITS="java kotlin"
verb=${1:-}
version=${2:-}
[ -n "$version" ] || { echo "usage: jvm-dry-run.sh staging|shaded <version>" >&2; exit 2; }

throwaway_key() {
  home=$(mktemp -d)
  GNUPGHOME=$home gpg --batch --passphrase '' --quick-gen-key 'lingara-cli dry run <dry-run@getlingara.com>' ed25519 sign never 2>/dev/null
  ORG_GRADLE_PROJECT_signingInMemoryKey=$(GNUPGHOME=$home gpg --batch --pinentry-mode loopback --passphrase '' --armor --export-secret-keys)
  ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=''
  export ORG_GRADLE_PROJECT_signingInMemoryKey ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
  rm -rf "$home"
}

staging() {
  [ -n "${ORG_GRADLE_PROJECT_signingInMemoryKey:-}" ] || throwaway_key
  rm -rf build/staging-repo
  ./gradlew -q :java:publishAllPublicationsToStagingRepository :kotlin:publishAllPublicationsToStagingRepository
  status=0
  for kit in $KITS; do
    dir=build/staging-repo/com/getlingara/lingara-cli-$kit/$version
    base=$dir/lingara-cli-$kit-$version
    for f in "$base.jar" "$base-sources.jar" "$base-javadoc.jar" "$base-all.jar" "$base.pom"; do
      [ -f "$f" ] || { echo "✗ $f is missing"; status=1; continue; }
      [ -f "$f.asc" ] || { echo "✗ $f.asc is missing"; status=1; }
    done
  done
  [ $status -eq 0 ] && echo "✓ both artefacts: jar, -sources, -javadoc, -all, the pom, each signed"
  return $status
}

shaded() {
  status=0
  repo=$(mktemp -d)
  for kit in $KITS; do
    libs=$kit/build/libs
    out=$("$JAVA_HOME/bin/java" -Dmaven.repo.local="$repo" -jar "$libs/lingara-cli-$kit-$version-all.jar" --version)
    [ "$out" = "lingara $version ($kit)" ] || { echo "✗ the $kit -all jar printed '$out'"; status=1; }
    for jar in "$libs/lingara-cli-$kit-$version.jar" "$libs/lingara-cli-$kit-$version-all.jar"; do
      [ -f "$jar" ] || { echo "✗ $jar is missing"; status=1; continue; }
      unzip -p "$jar" META-INF/MANIFEST.MF | grep -q '^Main-Class: ' || { echo "✗ $jar has no Main-Class"; status=1; }
    done
  done
  rm -rf "$repo"
  [ $status -eq 0 ] && echo "✓ both -all jars run alone, and all four jars carry Main-Class"
  return $status
}

case $verb in
  staging) staging ;;
  shaded) shaded ;;
  *) echo "usage: jvm-dry-run.sh staging|shaded <version>" >&2; exit 2 ;;
esac
