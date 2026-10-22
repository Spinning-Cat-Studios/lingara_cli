# The Kotlin kit (ADR 16.10.26af, wave 2): `com.getlingara:lingara-cli-kotlin`,
# binary `lingara`, on Clikt. It shares one Gradle build with the Java kit
# (settings.gradle.kts, build.gradle.kts at the root), and conformance runs
# the shaded jar, so the artefact a developer runs is the one judged (D5,
# D8). Both command lines name the resolved JDK, never a shim, and an
# absolute jar path, because 16.10.26ae's project cases run the CLI from a
# fixture's temp directory with a fresh HOME.
#
# The version is VERSION, read by the Gradle build into the jar (D1); the
# kit never reads a file above kotlin/ at run time. Its budgets are the
# repo's code-health scanner's, whose roots include kotlin/src/main/kotlin/
# (`make check-code-health`, .code-health/config.toml).

# Listed with its languages.toml entry, once every conformance case passes
# (ADR 16.10.26af D3).
LANGS += kotlin

KOTLIN_KIT_JDK := $(or $(shell mise where java@temurin-17 2>/dev/null),$(JAVA_HOME))
KOTLIN_KIT_GRADLE := JAVA_HOME=$(KOTLIN_KIT_JDK) ./gradlew
KOTLIN_KIT_JAR := $(CURDIR)/kotlin/build/libs/lingara-cli-kotlin-$(shell cat VERSION)-all.jar
# The runner hands the child only what CONFORMANCE_PASS_ENV_kotlin names, so
# the JDK the jar runs on is exported here and passed through.
export JAVA_HOME := $(KOTLIN_KIT_JDK)

# tree-diff appends `__tree` itself (make/config.mk).
TREE_CMD_kotlin = $(KOTLIN_KIT_JDK)/bin/java -jar $(KOTLIN_KIT_JAR)
CONFORMANCE_CMD_kotlin = $(KOTLIN_KIT_JDK)/bin/java -jar $(KOTLIN_KIT_JAR)
CONFORMANCE_PASS_ENV_kotlin = JAVA_HOME

.PHONY: build-kotlin test-kotlin conformance-packaged-kotlin help-kotlin

## kotlin/build/libs/lingara-cli-kotlin-<VERSION>-all.jar, the shaded jar.
build-kotlin:
	$(KOTLIN_KIT_GRADLE) -q :kotlin:shadowJar

## The kit's unit tests (16.10.26af AC18) and Gradle's own checks.
test-kotlin:
	$(KOTLIN_KIT_GRADLE) :kotlin:check

# 16.10.26af AC15: the shaded jar as Central ships it, copied alone to a
# scratch directory and run from there through the create and b3 cases. A
# kit that read ../spec or ../templates at run time fails here.
conformance-packaged-kotlin: build-kotlin build-conformance-fakes
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	cp $(KOTLIN_KIT_JAR) "$$tmp/lingara.jar" && \
	$(call packaged-suite,$$tmp/suite) && \
	$(CONFORMANCE_RUNNER) run --lang kotlin $(call PACKAGED_ARGS,$$tmp/suite) --pass-env JAVA_HOME -- \
	  $(KOTLIN_KIT_JDK)/bin/java -jar "$$tmp/lingara.jar"

# 16.10.26af AC6, AC7: both run the built jar, so they build it first.
check-tree-kotlin check-version-kotlin conformance-kotlin: build-kotlin

help-kotlin:
	@echo ""
	@echo "Kotlin kit:"
	@echo "  make build-kotlin                 - Build the shaded jar, kotlin/build/libs/lingara-cli-kotlin-<VERSION>-all.jar"
	@echo "  make test-kotlin                  - gradle :kotlin:check (the unit tests)"
	@echo "  make conformance-kotlin           - Every conformance case against the shaded jar"
	@echo "  make conformance-packaged-kotlin  - The shaded jar alone, from a scratch dir outside the checkout"

HELP_SECTIONS += kotlin
