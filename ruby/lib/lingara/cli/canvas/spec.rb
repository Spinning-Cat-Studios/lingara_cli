# frozen_string_literal: true

module Lingara
  module CLI
    module Canvas
      # A field node's op body: the web's `specFromFields` then `specBody`
      # (`testRun.ts`, `plan.ts`), over the node's saved fields. A create's
      # client is always `billing_mode: allowance`; no body carries
      # `enabled`, `api_version` or `client_type`. The app manifest is
      # `lingara.v1`'s `manifest()` wire form, with the defaults it fills.
      # A field of the wrong shape raises `TypeError`, which the planner
      # turns into a refusal naming the node.
      module Spec
        def self.text(value, what)
          raise TypeError, "#{what} must be a string" unless value.is_a?(String)

          value
        end

        def self.texts(value, what)
          raise TypeError, "#{what} must be an array of strings" unless value.is_a?(Array) && value.all?(String)

          value.dup
        end

        def self.optional_texts(value, what) = value.nil? ? nil : texts(value, what)

        # A plain string is `{locale: s}`; a map must hold strings only.
        def self.locale_text(value, locale, what)
          return {locale => value} if value.is_a?(String)
          return value.dup if value.is_a?(Hash) && value.values.all?(String)

          raise TypeError, "#{what} must be a string or an object of strings"
        end

        def self.client_body(fields, create)
          body = {"name" => text(fields["name"], "name"), "scopes" => texts(fields["scopes"], "scopes")}
          uris = optional_texts(fields["redirectUris"], "redirectUris")
          body["redirect_uris"] = uris if uris
          body["billing_mode"] = "allowance" if create
          body
        end

        def self.webhook_body(fields) = {"url" => text(fields["url"], "url"), "event_types" => texts(fields["events"], "events")}

        def self.app_body(fields) = {"manifest" => manifest(fields["manifest"])}

        # `lingara.v1`'s `manifest()`, in the order it writes the members.
        def self.manifest(value)
          raise TypeError, "manifest must be an object" unless value.is_a?(Hash)

          locale = value["defaultLocale"].nil? ? "en" : text(value["defaultLocale"], "defaultLocale")
          {"manifest_version" => 1, "default_locale" => locale,
           "name" => locale_text(value["name"], locale, "name"),
           "description" => locale_text(value["description"], locale, "description"),
           "render_url" => text(value["renderUrl"], "renderUrl"),
           "slots" => texts(value["slots"], "slots"),
           "context" => optional_texts(value["context"], "context") || [],
           "scopes" => optional_texts(value["scopes"], "scopes") || [],
           "tutor_note" => value["tutorNote"] == true}.merge(listing(value, locale))
        end

        # The listing's three members, each written only when its option is set.
        def self.listing(o, locale)
          out = {}
          out["long_description"] = locale_text(o["longDescription"], locale, "longDescription") unless o["longDescription"].nil?
          out["developer"] = developer(o["developer"]) if o["developer"].is_a?(Hash)
          if o["images"].is_a?(Array)
            out["images"] = o["images"].map do |i|
              {"sha256" => text(i["sha256"], "images[].sha256"), "role" => text(i["role"], "images[].role"),
               "alt" => locale_text(i["alt"], locale, "images[].alt")}
            end
          end
          out
        end

        def self.developer(d)
          out = {"name" => text(d["name"], "developer.name")}
          out["url"] = text(d["url"], "url") unless d["url"].nil?
          out["support_url"] = text(d["supportUrl"], "supportUrl") unless d["supportUrl"].nil?
          out
        end

        # The name an app op registers under: the manifest's in its default locale.
        def self.app_name(body, fallback)
          m = body["manifest"].is_a?(Hash) ? body["manifest"] : {}
          names = m["name"].is_a?(Hash) ? m["name"] : {}
          named = names[m["default_locale"].is_a?(String) ? m["default_locale"] : "en"] || names.values.find { |v| v.is_a?(String) }
          named.is_a?(String) ? named : fallback
        end
      end
    end
  end
end
