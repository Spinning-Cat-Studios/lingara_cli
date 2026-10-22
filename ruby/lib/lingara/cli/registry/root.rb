# frozen_string_literal: true

require_relative "base"
require_relative "apps"
require_relative "objects"
require_relative "webhook"
require_relative "canvas"

module Lingara
  module CLI
    module Registry
      # `lingara`: the root of the tree, registered once. `Main` parses with
      # it, and `Tree` reads it back for `__tree`. `client` is the schema's
      # alias for the `oauth_client` topic.
      class Root < Base
        flag :device, "Sign in with a code shown here, for a machine with no browser", type: :boolean
        leaf :login, "Sign in through the browser, or with a device code."
        leaf :logout, "Revoke and forget the stored credentials."
        leaf :whoami, "Show the developer the credentials belong to."
        leaf :__tree, "The registered command tree as JSON.", hide: true

        desc "apps", "Apps: scaffold, register, run in development, publish."
        subcommand "apps", Apps
        desc "embed", "Embeds and their metered clients."
        subcommand "embed", Embed
        desc "oauth_client", "OAuth clients and their secrets."
        subcommand "oauth_client", OauthClient
        desc "webhook", "Inbound and outbound webhooks."
        subcommand "webhook", Webhook
        desc "canvas", "Canvases: drafts, and apply."
        subcommand "canvas", Canvas
        map "client" => "oauth_client"
      end
    end
  end
end
