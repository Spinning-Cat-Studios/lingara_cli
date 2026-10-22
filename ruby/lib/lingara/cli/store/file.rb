# frozen_string_literal: true

require "json"
require "fileutils"

module Lingara
  module CLI
    module Store
      # A profile record's members, in C3 D4's order.
      RECORD_KEYS = %w[developer_id email scopes refresh_token access_token access_expires_at].freeze

      # `<config dir>/credentials.json`, mode `0600` in a `0700` directory,
      # shaped `{"schema": 1, "profiles": {"<profile>": <record>}}`: C3 D4's
      # file, byte for byte the shape the Rust kit writes (16.10.26af D2:
      # this kit's one store).
      class FileStore
        attr_reader :path

        def initialize(dir)
          @path = File.join(dir, "credentials.json")
        end

        def has?(profile) = read["profiles"].key?(profile)

        def load(profile) = read["profiles"][profile]

        def save(profile, record)
          file = read
          file["profiles"][profile] = RECORD_KEYS.to_h { |k| [k, record[k].to_s] }
          write(file)
        end

        # Removes the profile; the file goes when it held only that one.
        def delete(profile)
          file = read
          return unless file["profiles"].key?(profile)

          file["profiles"].delete(profile)
          file["profiles"].empty? ? FileUtils.rm_f(path) : write(file)
        end

        private

        def read
          parsed = JSON.parse(File.read(path, encoding: "UTF-8"))
          {"schema" => 1, "profiles" => parsed["profiles"].is_a?(Hash) ? parsed["profiles"] : {}}
        rescue Errno::ENOENT
          {"schema" => 1, "profiles" => {}}
        rescue JSON::ParserError, SystemCallError => e
          raise Fail.failure("the credential store could not be read: #{path}: #{e.message}")
        end

        def write(file)
          dir = File.dirname(path)
          FileUtils.mkdir_p(dir, mode: 0o700)
          File.chmod(0o700, dir)
          File.write(path, "#{JSON.pretty_generate(file)}\n", perm: 0o600)
          File.chmod(0o600, path)
        end
      end
    end
  end
end
