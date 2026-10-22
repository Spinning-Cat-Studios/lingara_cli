# frozen_string_literal: true

require_relative "base"

module Lingara
  module CLI
    module Registry
      # `webhook inbound …` (16.10.26ad D1, D2).
      class WebhookInbound < Base
        def self.words = %w[webhook inbound]

        flag :name, "The webhook's name", required: true
        flag :event, "An inbound event type it accepts; absent, every one", repeatable: true, banner: "type"
        leaf :create, "Create an inbound webhook and its client, and show the client's secret once."

        leaf :list, "List your inbound webhooks."
        leaf :get, "Show one inbound webhook."

        flag :name, "The new name"
        flag :event, "Replaces the event types", repeatable: true, banner: "type"
        leaf :update, "Rename an inbound webhook, or replace its event types."

        delete_flags
        leaf :delete, "Delete an inbound webhook, or one version. Its client stays."
      end

      # `webhook outbound …` (16.10.26ad D1, D2, D5).
      class WebhookOutbound < Base
        def self.words = %w[webhook outbound]

        flag :client, "The OAuth client it belongs to", required: true, banner: "id"
        flag :url, "The endpoint deliveries go to", required: true, banner: "url"
        flag :event, "An event type to deliver", required: true, repeatable: true, banner: "type"
        leaf :create, "Create an outbound webhook, and show its signing secret once."

        leaf :list, "List your outbound webhooks."
        leaf :get, "Show one outbound webhook."

        flag :url, "The new endpoint", banner: "url"
        flag :event, "Replaces the event types", repeatable: true, banner: "type"
        flag :enable, "Resume deliveries", type: :boolean
        flag :disable, "Pause deliveries", type: :boolean
        method_exclusive :enable, :disable
        leaf :update, "Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it."

        flag :url, "The new endpoint; absent, the old one's", banner: "url"
        flag :event, "The new event types; absent, the old one's", repeatable: true, banner: "type"
        confirm_flags
        leaf :replace, "Start again: a new endpoint with a new signing secret, then the old one deleted."

        leaf :test, "Send a test delivery."

        delete_flags
        leaf :delete, "Delete an outbound webhook, or one version."
      end

      # `webhook …`: the two directions.
      class Webhook < Base
        def self.words = %w[webhook]

        desc "inbound", "Events your servers send to Lingara."
        subcommand "inbound", WebhookInbound
        desc "outbound", "Events Lingara delivers to your endpoint."
        subcommand "outbound", WebhookOutbound
      end
    end
  end
end
