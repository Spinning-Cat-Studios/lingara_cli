# The PHP kit (ADR 16.10.26af D2, D6): Packagist `spinningcatstudios/lingara-cli`,
# binary `lingara`, on symfony/console, and lingara.phar built by humbug/box.
# Both command lines name the interpreter's own path (PHP_BINARY), never a
# version-manager shim, and an absolute script path, because 16.10.26ae's
# project cases run the CLI from a fixture's temp directory with a fresh
# HOME. No pass-env: php finds its php.ini beside its own binary.

# Listed with its languages.toml entry, once every conformance case passes
# (ADR 16.10.26af D3).
LANGS += php

PHP ?= php
PHP_BIN := $(shell $(PHP) -r 'echo PHP_BINARY;' 2>/dev/null)
COMPOSER ?= composer
# humbug/box, pinned by version and digest: a PHAR fetched once into
# php/.box/ (gitignored), so it is no Composer dependency of the kit.
BOX_VERSION := 4.7.0
BOX_SHA256 := 3d390eeaec33288098fe83f8a54c60cc575cb6be295f38ff4482b4b4f26f8d52
BOX_PHAR := $(CURDIR)/php/.box/box-$(BOX_VERSION).phar

PHP_KIT_BIN := $(CURDIR)/php/bin/lingara
# tree-diff appends `__tree` itself (make/config.mk).
TREE_CMD_php = $(PHP_BIN) $(PHP_KIT_BIN)
CONFORMANCE_CMD_php = $(PHP_BIN) $(PHP_KIT_BIN)

.PHONY: build-php test-php test-php-supervise conformance-packaged-php package-php phar-php help-php

## Install php/vendor from the committed composer.lock.
build-php:
	cd php && $(COMPOSER) install --quiet --no-interaction --no-progress

## PHPUnit (16.10.26af AC20), PHPStan, and the PHPCS size and complexity budgets.
test-php: build-php
	cd php && $(PHP_BIN) vendor/bin/phpunit
	cd php && $(PHP_BIN) vendor/bin/phpstan analyse --no-progress --memory-limit=1G
	cd php && $(PHP_BIN) vendor/bin/phpcs

## The process-group tests alone: the supervisor and the signal trap.
test-php-supervise: build-php
	cd php && $(PHP_BIN) vendor/bin/phpunit --filter Supervise

$(BOX_PHAR):
	@mkdir -p $(dir $@)
	curl -fsSL -o $@.tmp https://github.com/box-project/box/releases/download/$(BOX_VERSION)/box.phar
	@echo "$(BOX_SHA256)  $@.tmp" | shasum -a 256 -c - >/dev/null || { echo "✗ box.phar $(BOX_VERSION) does not match its pinned digest"; rm -f $@.tmp; exit 1; }
	@mv $@.tmp $@

# The tree a split push of php/ would hold (ADR 16.10.26af D6): every file
# git tracks or would track under php/, so vendor/, the PHAR and box.phar
# (php/.gitignore) stay out. $(1) is the directory to write it to.
define php-split
mkdir -p $(1) && (cd php && git ls-files -co --exclude-standard . | tar -cf - -T -) | tar -xf - -C $(1)
endef

## php/lingara.phar (ADR 16.10.26af D6): the split tree after a --no-dev
## install, compiled by box with every asset inside.
phar-php: $(BOX_PHAR)
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	$(call php-split,$$tmp) && \
	(cd "$$tmp" && $(COMPOSER) install --quiet --no-dev --no-interaction --no-progress --classmap-authoritative) && \
	$(PHP_BIN) -d phar.readonly=0 $(BOX_PHAR) compile --quiet --working-dir "$$tmp" && \
	mv "$$tmp/lingara.phar" php/lingara.phar && echo "✓ php/lingara.phar"

## The split mirror's dry run and the PHAR (16.10.26af AC12): the tree a
## split push of php/ would hold has composer.json's bin and every asset,
## and the PHAR's --version passes AC7's pattern.
package-php: phar-php
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	$(call php-split,$$tmp/split) && \
	grep -q '"bin": \["bin/lingara"\]' "$$tmp/split/composer.json" && \
	sh tools/kit-assets.sh check "$$tmp/split/assets" && \
	out=$$($(PHP_BIN) php/lingara.phar --version) && \
	printf '%s\n' "$$out" | grep -Eqx 'lingara $(subst .,\.,$(KIT_VERSION)) \(php\)' && \
	echo "✓ split tree carries bin/lingara and every asset; lingara.phar prints: $$out"

## The kit as Packagist and the GitHub Release ship it (ADR 16.10.26af D8;
## 16.10.26af AC15): (a) the split tree, php/ alone, `composer install --no-dev` in a
## scratch directory and run from there; (b) lingara.phar copied out and run
## alone. Both through the create and b3 cases, from outside the checkout. A
## kit that read ../spec or ../templates at run time fails here.
conformance-packaged-php: build-conformance-fakes phar-php
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && \
	$(call php-split,$$tmp/split) && \
	(cd "$$tmp/split" && $(COMPOSER) install --quiet --no-dev --no-interaction --no-progress) && \
	cp php/lingara.phar "$$tmp/lingara.phar" && \
	$(call packaged-suite,$$tmp/suite) && \
	$(CONFORMANCE_RUNNER) run --lang php $(call PACKAGED_ARGS,$$tmp/suite) -- $(PHP_BIN) "$$tmp/split/bin/lingara" && \
	$(CONFORMANCE_RUNNER) run --lang php $(call PACKAGED_ARGS,$$tmp/suite) -- $(PHP_BIN) "$$tmp/lingara.phar"

# Each runs the kit through its vendor/, so it is installed first:
# check-tree-php is 16.10.26af AC6, check-version-php 16.10.26af AC7.
check-tree-php check-version-php conformance-php: build-php

help-php:
	@echo ""
	@echo "PHP kit:"
	@echo "  make build-php                 - composer install from php/composer.lock"
	@echo "  make test-php                  - PHPUnit, PHPStan and the PHPCS budgets"
	@echo "  make test-php-supervise        - The supervisor and signal tests alone"
	@echo "  make conformance-php           - Every conformance case against php/bin/lingara"
	@echo "  make phar-php                  - Build php/lingara.phar with humbug/box"
	@echo "  make package-php               - The split mirror's dry run and the PHAR's --version"
	@echo "  make conformance-packaged-php  - The split tree and the PHAR, from outside the checkout"

HELP_SECTIONS += php
