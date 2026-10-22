# frozen_string_literal: true

module Lingara
  module CLI
    # The handlers, one per schema command, keyed by the command path a
    # `Registry::Parsed` carries. Each lives in `commands/<file>.rb` as
    # `Commands::<File>.<method>(ctx, parsed)` and is required only when its
    # command runs.
    module Commands
      HANDLERS = {
        "login" => "session#login", "logout" => "session#logout", "whoami" => "session#whoami",
        "__tree" => "session#tree",
        "oauth_client create" => "oauth_client#create", "oauth_client list" => "oauth_client#list",
        "oauth_client get" => "oauth_client#get", "oauth_client update" => "oauth_client#update",
        "oauth_client delete" => "oauth_client#delete",
        "oauth_client secret create" => "oauth_client#secret_create",
        "oauth_client secret delete" => "oauth_client#secret_delete",
        "embed create" => "embed#create", "embed list" => "embed#list", "embed get" => "embed#get",
        "embed update" => "embed#update", "embed delete" => "embed#delete",
        "webhook inbound create" => "webhook_inbound#create", "webhook inbound list" => "webhook_inbound#list",
        "webhook inbound get" => "webhook_inbound#get", "webhook inbound update" => "webhook_inbound#update",
        "webhook inbound delete" => "webhook_inbound#delete",
        "webhook outbound create" => "webhook_outbound#create", "webhook outbound list" => "webhook_outbound#list",
        "webhook outbound get" => "webhook_outbound#get", "webhook outbound update" => "webhook_outbound#update",
        "webhook outbound replace" => "webhook_outbound#replace", "webhook outbound test" => "webhook_outbound#test",
        "webhook outbound delete" => "webhook_outbound#delete",
        "canvas create" => "canvas#create", "canvas list" => "canvas#list", "canvas get" => "canvas#get",
        "canvas update" => "canvas#update", "canvas draft" => "canvas#draft", "canvas apply" => "canvas#apply",
        "canvas delete" => "canvas#delete",
        "apps create" => "apps_create#create", "apps update" => "apps_update#update",
        "apps list" => "apps#list", "apps get" => "apps#get", "apps delete" => "apps#delete",
        "apps submit" => "apps#submit", "apps versions list" => "apps#versions_list",
        "apps versions publish" => "apps#versions_publish", "apps versions withdraw" => "apps#versions_withdraw",
        "apps status" => "apps_local#status", "apps pin" => "apps_local#pin", "apps unpin" => "apps_local#unpin",
        "apps dev" => "apps_dev#dev"
      }.freeze

      def self.run(ctx, parsed)
        file, method = HANDLERS.fetch(parsed.path).split("#")
        require_relative "commands/#{file}"
        const_get(file.split("_").map(&:capitalize).join).public_send(method, ctx, parsed)
      end

      # A string flag or argument, or nil.
      def self.opt(value) = value.is_a?(String) ? value : nil

      # A repeatable flag's values, or [].
      def self.many(value) = value.is_a?(Array) ? value.grep(String) : []
    end
  end
end
