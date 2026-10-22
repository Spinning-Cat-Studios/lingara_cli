# frozen_string_literal: true

# `lingara`, the Ruby kit (ADR 16.10.26af). Only what every run needs is
# required here; each command's handler, and the gems only some commands use
# (tomlrb, json_schemer), load when that command runs, so a run starts fast.
require_relative "cli/version"
require_relative "cli/fail"
require_relative "cli/assets"
require_relative "cli/output"
require_relative "cli/env"
require_relative "cli/routes"
require_relative "cli/classify"
require_relative "cli/http"
require_relative "cli/store"
require_relative "cli/confirm"
require_relative "cli/auth/tokens"
require_relative "cli/auth/session"
require_relative "cli/ctx"
require_relative "cli/app"
require_relative "cli/registry/root"
require_relative "cli/main"
require_relative "cli/commands"
