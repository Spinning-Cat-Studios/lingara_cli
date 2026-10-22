# frozen_string_literal: true

require_relative "store/file"

module Lingara
  module CLI
    # Token storage (ADR 16.10.26ad D4, as 16.10.26af D2 applies it to this
    # kit): one store interface with a file adapter and an in-memory fake,
    # and the one rule that picks between them. There is **no keychain
    # adapter**: no maintained cross-platform keyring gem exists, so the
    # file is this kit's default rather than a fallback (roadmap D4: the file
    # store is first-class). Hence:
    #
    # - unset `LINGARA_KEYRING`: the file, and the first write for a profile
    #   prints `store_file_only` once, never C3's fallback warning, which
    #   would recommend a keychain this kit cannot use;
    # - `LINGARA_KEYRING=file` (which conformance sets): the file, silently;
    # - `LINGARA_KEYRING=keychain`: `auth_required` with `store_file_only`'s
    #   sentence, never a silent file write, as C3's "keychain unavailable".
    module Store
      # The unit tests' fake: D1's third adapter.
      class MemoryStore
        attr_reader :records

        def initialize = @records = {}
        def has?(profile) = @records.key?(profile)
        def load(profile) = @records[profile]
        def save(profile, record) = @records[profile] = record.dup
        def delete(profile) = @records.delete(profile)
        def path = "(memory)"
      end

      # The store a run uses, chosen by `LINGARA_KEYRING`.
      class Stores
        attr_reader :mode, :file

        # `:auto`, `:file` or `:keychain`, from the variable's value.
        def self.mode_of(value) = %w[file keychain].include?(value) ? value.to_sym : :auto

        def initialize(mode, file)
          @mode = mode
          @file = file
        end

        def load(profile)
          refuse_keychain
          file.load(profile)
        end

        # Saves `record`; the note to print when this was the profile's first
        # write under an unset `LINGARA_KEYRING`.
        def save(profile, record)
          refuse_keychain
          first = !file.has?(profile)
          file.save(profile, record)
          (first && mode == :auto) ? Assets.message("store_file_only", path: file.path) : nil
        end

        def delete(profile)
          refuse_keychain
          file.delete(profile)
        end

        private

        def refuse_keychain
          return unless mode == :keychain

          raise Fail.from_message(Exit::AUTH_REQUIRED, "store_file_only", path: file.path)
        end
      end
    end
  end
end
