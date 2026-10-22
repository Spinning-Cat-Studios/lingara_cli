# The Java kit (ADR 16.10.26af D2, D5): artifact `com.getlingara:lingara-cli-java`,
# package `com.getlingara.cli.java`, on picocli, built by the Gradle root it
# shares with the Kotlin kit. Conformance runs the shaded `-all` jar, so the
# artefact a developer runs is the one judged (D8). Both command lines name
# the resolved JDK, never a version-manager shim (the runner drops the real
# HOME, ADR 16.10.26ac D5), and an absolute jar path, because 16.10.26ae's
# project cases run the CLI from a fixture's temp directory. JVM start-up
# counts against INTERACT_TIMEOUT and EXIT_TIMEOUT (D1's "may differ").

# Listed with its languages.toml entry, once every conformance case passes
# (ADR 16.10.26af D3).
LANGS += java

# The JDK mise pins (temurin-17), resolved to its real home; else whatever
# JAVA_HOME the caller exported. Exported, so gradle and the runner's
# --pass-env see the same JDK.
JAVA_KIT_HOME := $(or $(shell mise where java@temurin-17 2>/dev/null),$(JAVA_HOME))
export JAVA_HOME := $(JAVA_KIT_HOME)

JAVA_KIT_VERSION := $(shell cat VERSION)
JAVA_KIT_JAR := $(CURDIR)/java/build/libs/lingara-cli-java-$(JAVA_KIT_VERSION)-all.jar
# tree-diff appends `__tree` itself (make/config.mk). 16.10.26af AC6.
TREE_CMD_java = $(JAVA_KIT_HOME)/bin/java -jar $(JAVA_KIT_JAR)
CONFORMANCE_CMD_java = $(JAVA_KIT_HOME)/bin/java -jar $(JAVA_KIT_JAR)
# The JVM's launcher reads its own home; nothing else crosses into the case.
CONFORMANCE_PASS_ENV_java = JAVA_HOME

.PHONY: build-java test-java conformance-packaged-java maven-staging-dry-run check-shaded-jars help-java

## The shaded jar java/build/libs/lingara-cli-java-<VERSION>-all.jar.
build-java:
	./gradlew -q :java:shadowJar

## Checkstyle at the repo's budgets, the 15-files-per-folder check, then JUnit.
test-java:
	./gradlew -q :java:check

## The jar as Central and jbang would ship it (ADR 16.10.26af D8,
## 16.10.26af AC15): the shaded jar copied to a scratch directory outside the
## checkout and run from there through the create and b3 cases. A kit that
## read ../spec or ../templates at run time fails here.
conformance-packaged-java: build-java build-conformance-fakes
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	cp "$(JAVA_KIT_JAR)" "$$tmp/lingara.jar" && \
	$(call packaged-suite,$$tmp/suite) && \
	$(CONFORMANCE_RUNNER) run --lang java $(call PACKAGED_ARGS,$$tmp/suite) --pass-env JAVA_HOME -- \
	  $(JAVA_KIT_HOME)/bin/java -jar "$$tmp/lingara.jar"

## The Central staging publication of BOTH JVM artefacts, which share this
## Gradle build (ADR 16.10.26af D5, 16.10.26af AC9): the jar, -sources,
## -javadoc, -all and the pom in build/staging-repo, each with its .asc,
## signed by a throwaway key when no real one is in the environment.
maven-staging-dry-run:
	@sh tools/jvm-dry-run.sh staging $(JAVA_KIT_VERSION)

## 16.10.26af AC10: both -all jars run with an empty local Maven repository,
## and all four jars carry Main-Class. build-java / build-kotlin make only
## the -all jars, so the plain jars this check reads are built here
## (ADR 22.10.26d D5): a clean runner has no leftover to hide the gap.
check-shaded-jars: build-java build-kotlin
	./gradlew -q :java:jar
	$(KOTLIN_KIT_GRADLE) -q :kotlin:jar
	@sh tools/jvm-dry-run.sh shaded $(JAVA_KIT_VERSION)

# Each runs the built jar, so they build it first. 16.10.26af AC7: check-version-java.
check-tree-java check-version-java conformance-java: build-java

help-java:
	@echo ""
	@echo "Java kit:"
	@echo "  make build-java                 - Build the shaded jar java/build/libs/lingara-cli-java-<VERSION>-all.jar"
	@echo "  make test-java                  - Checkstyle budgets, the folder check, then JUnit"
	@echo "  make conformance-java           - Every conformance case against the shaded jar"
	@echo "  make conformance-packaged-java  - The shaded jar, copied outside the checkout, through the create and b3 cases"
	@echo "  make maven-staging-dry-run      - Both JVM artefacts into build/staging-repo, signed (the dry run)"
	@echo "  make check-shaded-jars          - Both -all jars run alone; all four jars carry Main-Class"

HELP_SECTIONS += java
