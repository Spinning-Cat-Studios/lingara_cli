# frozen_string_literal: true

module Lingara
  module CLI
    module Project
      # 16.10.26ae D4's `platform_toml_secret`: the first value or key, in
      # file order, that looks like a secret. A value is one when it matches
      # `lgr_[a-z]+_[A-Za-z0-9]{8,}` and is not a public client id
      # (`lgr_cid_…`); a key is one when its `_`-separated words include
      # `secret`, `token` or `password`, case-insensitively (`CLIENT_SECRET`,
      # `api_token`; never `TOKENIZER`). The refusal names the key, never the
      # value.
      module Secrets
        SECRET_WORDS = %w[secret token password].freeze
        VALUE = /lgr_([a-z]+)_[A-Za-z0-9]{8,}/

        def self.secret_value?(text) = text.scan(VALUE).any? { |(kind)| kind != "cid" }

        def self.secret_key?(key) = key.split("_").any? { |w| SECRET_WORDS.include?(w.downcase) }

        # The dotted path of the first secret-shaped key or value, if any.
        def self.first(value, at = "")
          case value
          when String then secret_value?(value) ? at : nil
          when Array then value.lazy.map { |item| first(item, at) }.find { |f| !f.nil? }
          when Hash then in_table(value, at)
          end
        end

        def self.in_table(table, at)
          table.each do |k, v|
            path = at.empty? ? k : "#{at}.#{k}"
            found = secret_key?(k) ? path : first(v, path)
            return found if found
          end
          nil
        end
      end
    end
  end
end
