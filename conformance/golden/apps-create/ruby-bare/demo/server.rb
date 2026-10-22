# frozen_string_literal: true

require "rackup"
require_relative "app"

secret = ENV.fetch("LINGARA_APP_SECRET", "")
abort "LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`." if secret.empty?
app = LingaraApp.app(secret)
port = Integer(ENV.fetch("PORT", "8787"))

# On the kit's primitive: App#handle does the method, size, signature, decode,
# dispatch and validation; this Rack lambda only moves bytes.
rack = lambda do |env|
  input = env["rack.input"]
  status, headers, body = app.handle(env["REQUEST_METHOD"], env, ->(limit) { input&.read(limit).to_s })
  [status, headers.dup, body.empty? ? [] : [body]]
end

puts "demo is listening on http://127.0.0.1:#{port}/"
Rackup::Server.start(app: rack, Host: "127.0.0.1", Port: port, server: "puma")
