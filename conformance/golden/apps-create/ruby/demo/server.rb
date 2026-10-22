# frozen_string_literal: true

require "rackup"
require_relative "app"

secret = ENV.fetch("LINGARA_APP_SECRET", "")
abort "LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`." if secret.empty?
port = Integer(ENV.fetch("PORT", "8787"))
puts "demo is listening on http://127.0.0.1:#{port}/"
# The kit's Rack app reads the raw body itself, so mount it where nothing has parsed the JSON first.
Rackup::Server.start(app: Lingara::Apps::RackApp.new(LingaraApp.app(secret)), Host: "127.0.0.1", Port: port, server: "puma")
