# frozen_string_literal: true

module Lingara
  module CLI
    module Registry
      # The kit-local arg/type table (ADR 16.10.26af D2): what Thor's model
      # does not record. Thor reads positional arguments from a method's
      # parameters, untyped, and its `:string` covers both `path` and `enum`;
      # its `:numeric` is coarser than `int`. So the arguments and those three
      # types are written here once, and read by **both** registration
      # (`Base.leaf` builds each command's usage, arity and Thor option type
      # from it) and `__tree` (`Tree`), so the projection is never a second
      # hand-written tree.
      module Table
        ID = [["id", true]].freeze

        # Command path → its positional arguments, `[name, required]`, in
        # order. Every positional argument is a `string`.
        ARGS = {
          "apps create" => [["name", true]],
          "apps delete" => ID, "apps get" => ID, "apps submit" => ID,
          "apps dev" => [["name", false]], "apps status" => [["name", false]],
          "apps pin" => [["app", true]], "apps unpin" => [["name", true]],
          "apps update" => [["id", false]],
          "apps versions list" => ID,
          "apps versions publish" => [["id", true], ["semver", true]],
          "apps versions withdraw" => [["id", true], ["semver", true]],
          "canvas apply" => ID, "canvas delete" => ID, "canvas draft" => ID, "canvas get" => ID, "canvas update" => ID,
          "embed delete" => ID, "embed get" => ID, "embed update" => ID,
          "oauth_client delete" => ID, "oauth_client get" => ID, "oauth_client update" => ID,
          "oauth_client secret create" => [["client_id", true]],
          "oauth_client secret delete" => [["client_id", true], ["secret_id", true]],
          "webhook inbound delete" => ID, "webhook inbound get" => ID, "webhook inbound update" => ID,
          "webhook outbound delete" => ID, "webhook outbound get" => ID, "webhook outbound replace" => ID,
          "webhook outbound test" => ID, "webhook outbound update" => ID
        }.freeze

        # A flag's D2 type where Thor's is coarser, by flag name (one name
        # has one type across the whole tree). Absent: Thor's own
        # (`:boolean` is `bool`, `:string` is `string`).
        FLAG_TYPES = {
          "manifest" => "path", "dir" => "path", "config" => "path", "file" => "path", "path" => "path",
          "write-env" => "path", "port" => "int",
          "lang" => "enum", "slot" => "enum", "type" => "enum", "billing-mode" => "enum", "tunnel" => "enum"
        }.freeze

        # An `enum`'s values, which registration hands Thor as `enum:`.
        ENUMS = {
          "lang" => %w[typescript rust go java kotlin ruby php],
          "slot" => %w[home.side plans.empty_detail],
          "type" => %w[confidential public],
          "billing-mode" => %w[allowance metered],
          "tunnel" => %w[cloudflared none]
        }.freeze

        # The Thor option type a D2 type registers as.
        THOR_TYPES = {"path" => :string, "enum" => :string, "int" => :numeric}.freeze

        def self.args(path) = ARGS.fetch(path, [])

        def self.flag_type(name, thor_type)
          FLAG_TYPES.fetch(name.to_s) { (thor_type == :boolean) ? "bool" : "string" }
        end
      end
    end
  end
end
