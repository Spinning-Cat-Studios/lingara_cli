# frozen_string_literal: true

module Lingara
  module CLI
    # One run's context and every authenticated call (ADR 16.10.26ad D3;
    # contract B2 rule 2). A `401` to a bearer request refreshes once (a
    # fresh `client_credentials` mint under CI) and replays once; a second
    # `401` is `auth_required`, and any other answer to the replay is
    # classified again.
    class Ctx
      attr_reader :app, :out
      attr_accessor :profile, :profile_chosen

      # `profile_chosen`: `--profile` or `LINGARA_PROFILE` chose it, so
      # `platform.toml`'s `[platform] profile` may not (16.10.26ae D4).
      def initialize(app, out, profile, profile_chosen)
        @app = app
        @out = out
        @profile = profile
        @profile_chosen = profile_chosen
      end

      def http = app.http
      def env = app.env

      # The ritual's ports (`Confirm.destructive`).
      def interactive? = app.terminal.interactive?
      def read_line = app.terminal.read_line
      def prompt(text) = out.prompt(text)

      # The CI pair, when both halves are set (D3 rule 1).
      def ci_pair
        id = env["LINGARA_PLATFORM_CLIENT_ID"]
        secret = env["LINGARA_PLATFORM_CLIENT_SECRET"]
        (id && secret) ? [id, secret] : nil
      end

      # A token-endpoint form `POST`: no bearer, classified as a token route.
      def token_post(name, pairs, auth = nil)
        route = Routes.fixed(name)
        reply = http.send_request(Http::Request.new(route: route, body: {form: pairs}), auth)
        raise Classify.refusal(route, reply, false) unless ok?(reply.status)

        Http.parse(reply.body) || {}
      end

      # The bearer token, opening the session on first use.
      def bearer
        app.session ||= Auth::Session.open(self)
        app.session.access
      end

      # One authenticated call: send, refresh and replay once on a `401`,
      # classify anything else.
      def call(request)
        reply = http.send_request(request, {bearer: bearer})
        return body_json(reply, request.route) if ok?(reply.status)
        raise Classify.refusal(request.route, reply, true) unless Classify.classify(Classify.answer(request.route, reply, true)) == :refresh

        Auth::Session.refresh(self)
        replay = http.send_request(request, {bearer: bearer})
        return body_json(replay, request.route) if ok?(replay.status)
        raise Fail.auth_required("the platform refused the credentials after a refresh: run `lingara login`") if replay.status == 401

        raise Classify.refusal(request.route, replay, true)
      end

      # Shorthand: a fixed route with `vars`, `query` and `body`.
      def fixed(name, vars: {}, query: [], body: nil)
        call(Http::Request.new(route: Routes.fixed(name), vars: vars, query: query, body: body))
      end

      # The email `--confirm` and the typed line are compared against.
      def email
        bearer
        known = app.session.email
        return known if known

        me = fixed("developer.me")
        app.session.email = me["email"].is_a?(String) ? me["email"] : ""
      end

      # Writes the profile record, printing the store's one first-write note.
      def save_record(record)
        note = app.stores.save(profile, record)
        out.note(note) if note
      end

      private

      def ok?(status) = (200..299).cover?(status)

      def body_json(reply, route)
        return {} if reply.body.strip.empty?

        JSON.parse(reply.body)
      rescue JSON::ParserError
        raise Fail.failure("unexpected response from #{route.name}")
      end
    end
  end
end
