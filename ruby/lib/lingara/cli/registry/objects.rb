# frozen_string_literal: true

require_relative "base"

module Lingara
  module CLI
    module Registry
      # `oauth_client secret …` (16.10.26ad D1).
      class OauthClientSecret < Base
        def self.words = %w[oauth_client secret]

        leaf :create, "Mint a new secret, shown once. The old one stays live."

        confirm_flags
        leaf :delete, "Revoke a secret."
      end

      # `oauth_client …`, also spelled `client …` (16.10.26ad D1).
      class OauthClient < Base
        def self.words = %w[oauth_client]

        flag :name, "The client's name", required: true
        flag :scope, "A scope the client may ask for", repeatable: true, banner: "scope"
        # No Thor `default:` on these two: Thor counts a defaulted option as
        # given, so `--ci` alone would conflict with `--type`. The handler
        # applies the defaults (confidential, allowance).
        flag :type, "Whether the client keeps a secret; default confidential"
        flag :"billing-mode", "How its usage is billed; default allowance", banner: "mode"
        flag :"redirect-uri", "A redirect URI it may use", repeatable: true, banner: "uri"
        flag :ci, "A CI client: confidential, holding the four platform and canvas scopes", type: :boolean
        method_exclusive :ci, :type
        method_exclusive :ci, :scope
        leaf :create, "Create an OAuth client; a confidential one's secret is shown once."

        leaf :list, "List your OAuth clients."
        leaf :get, "Show one OAuth client."

        flag :name, "The new name"
        flag :scope, "Replaces the scopes", repeatable: true, banner: "scope"
        flag :"api-version", "The API version it is pinned to", banner: "version"
        flag :"redirect-uri", "Replaces the redirect URIs", repeatable: true, banner: "uri"
        leaf :update, "Rename an OAuth client, or replace its scopes, API version or redirect URIs."

        delete_flags
        leaf :delete, "Delete an OAuth client and everything under it, or one version."

        desc "secret", "Rotate a confidential client's secret."
        subcommand "secret", OauthClientSecret
      end

      # `embed …` (16.10.26ad D1, D2).
      class Embed < Base
        def self.words = %w[embed]

        flag :name, "The embed's name", required: true
        flag :origin, "A web origin allowed to use it (up to 10)", repeatable: true, banner: "origin"
        flag :scope, "A scope beside embed:mint", repeatable: true, banner: "scope"
        leaf :create, "Create an embed and its metered OAuth client, and show the client's secret once."

        leaf :list, "List your embeds."
        leaf :get, "Show one embed."

        flag :name, "The new name"
        flag :origin, "Replaces the allowed origins", repeatable: true, banner: "origin"
        leaf :update, "Rename an embed, or replace its origins."

        delete_flags
        leaf :delete, "Delete an embed and its OAuth client, or one version."
      end
    end
  end
end
