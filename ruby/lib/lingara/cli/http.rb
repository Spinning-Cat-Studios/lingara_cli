# frozen_string_literal: true

require "json"

module Lingara
  module CLI
    # Requests: one place spells a route onto a base, adds contract B5's
    # headers, retries what 16.10.26ad D6 lets it retry, and hands back the
    # raw answer. Classifying it is `Classify`'s; credentials are the
    # session's. HTTP is Net::HTTP, the standard library's client (16.10.26af
    # D1), never the `lingara` API library, whose User-Agent and retry policy
    # are not the CLI's.
    module Http
      Wire = Struct.new(:method, :url, :headers, :body)
      Response = Struct.new(:status, :headers, :body)

      # One request on the wire. Any exception is a connection failure:
      # contract B2 rule 7.
      module NetTransport
        def self.send_wire(wire)
          require "net/http"
          uri = URI(wire.url)
          request = Net::HTTPGenericRequest.new(wire.method, !wire.body.nil?, true, uri.request_uri, wire.headers)
          request.body = wire.body if wire.body
          http = Net::HTTP.new(uri.host, uri.port)
          http.use_ssl = uri.scheme == "https"
          response = http.start { |h| h.request(request) }
          headers = response.each_header.to_h { |k, v| [k.downcase, v] }
          Response.new(response.code.to_i, headers, response.body.to_s.dup.force_encoding("UTF-8"))
        end
      end

      # The time a run reads and waits on, so tests never sleep.
      module SystemClock
        def self.now = Time.now.to_i
        def self.sleep(secs) = Kernel.sleep(secs)
      end

      # A route, its `{name}` fillings, its query pairs and its body:
      # `{json: value}` or `{form: [[k, v], …]}`.
      Request = Struct.new(:route, :vars, :query, :body)

      # RFC 3986 unreserved characters pass; everything else is `%XX`.
      def self.encode(text)
        text.to_s.b.gsub(/[^A-Za-z0-9\-._~]/n) { |c| format("%%%02X", c.ord) }
      end

      def self.form(pairs) = pairs.map { |(k, v)| "#{encode(k)}=#{encode(v)}" }.join("&")

      def self.parse(body)
        v = JSON.parse(body.to_s)
        v.is_a?(Hash) ? v : nil
      rescue JSON::ParserError
        nil
      end

      # At most two retries of an idempotent request, waiting at most 30 s
      # in all (16.10.26ad D6).
      RETRIES = 2
      RETRY_BUDGET_SECS = 30

      # The configured client: transport, clock, the two bases, B5's headers.
      class Client
        attr_accessor :bases
        attr_reader :transport, :clock, :user_agent

        def initialize(transport, clock, bases, user_agent, accept_language = nil)
          @transport = transport
          @clock = clock
          @bases = bases
          @user_agent = user_agent
          @accept_language = accept_language
        end

        def url(route, vars = {}, query = [])
          base = ((route.base == :portal) ? bases[:portal] : bases[:api]).sub(%r{/+\z}, "")
          path = (vars || {}).reduce(route.path) { |p, (k, v)| p.gsub("{#{k}}", Http.encode(v)) }
          q = (query.nil? || query.empty?) ? "" : "?#{Http.form(query)}"
          "#{base}#{path}#{q}"
        end

        def wire(request, auth)
          headers = {"user-agent" => user_agent, "accept" => "application/json"}
          headers["accept-language"] = @accept_language if @accept_language
          headers["authorization"] = authorization(auth) if auth
          body = request.body
          text = nil
          if body&.key?(:json)
            headers["content-type"] = "application/json"
            text = JSON.generate(body[:json])
          elsif body&.key?(:form)
            headers["content-type"] = "application/x-www-form-urlencoded"
            text = Http.form(body[:form])
          end
          Wire.new(request.route.method, url(request.route, request.vars, request.query), headers, text)
        end

        # One request with D6's retries; a `POST` is never retried after a
        # response (B5).
        def send_request(request, auth)
          wire = wire(request, auth)
          waited = 0
          (0..).each do |attempt|
            response = deliver(request, wire)
            return Response.new(204, {}, "") if attempt.positive? && replayed_delete_done?(wire, response)

            wait = retry_wait(wire, response)
            return response if wait.nil? || attempt >= RETRIES || waited + wait > RETRY_BUDGET_SECS

            clock.sleep(wait)
            waited += wait
          end
        end

        private

        def deliver(request, wire)
          transport.send_wire(wire)
        rescue => e
          raise Fail.new(Exit::UNAVAILABLE, "unavailable", "#{request.route.name} could not be reached: #{e.message}")
        end

        def authorization(auth)
          return "Bearer #{auth[:bearer]}" if auth.key?(:bearer)

          id, secret = auth[:basic]
          # `pack("m0")` is strict Base64 without a dependency on the base64
          # gem, which Ruby 3.4 unbundles.
          "Basic #{["#{Http.encode(id)}:#{Http.encode(secret)}"].pack("m0")}"
        end

        def retry_wait(wire, response)
          idempotent = %w[GET PUT PATCH DELETE].include?(wire.method)
          return nil unless idempotent && [429, 503].include?(response.status)

          Integer(response.headers["retry-after"].to_s, exception: false)
        end

        # A `DELETE` replayed after it succeeded answers a coded 404 (D6).
        def replayed_delete_done?(wire, response)
          wire.method == "DELETE" && response.status == 404 && Http.parse(response.body)&.dig("code").is_a?(String)
        end
      end
    end
  end
end
