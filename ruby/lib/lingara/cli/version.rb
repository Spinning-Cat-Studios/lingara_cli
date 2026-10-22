# frozen_string_literal: true

module Lingara
  module CLI
    # The repository's VERSION, committed here because a gem carries only
    # ruby/ (ADR 16.10.26af D1, D8). `make check-version-ruby` holds the two
    # equal: `lingara --version` must print `lingara <VERSION> (ruby)`.
    VERSION = "0.1.0-alpha.1"
  end
end
