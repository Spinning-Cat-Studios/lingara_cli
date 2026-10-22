# frozen_string_literal: true

require "base64"
require "json"
require "minitest/autorun"
require "openssl"
require "rack/test"
require_relative "../app"

class RenderTest < Minitest::Test
  include Rack::Test::Methods

  # A visibly fake key: the secret is lgr_whsec_ + its base64.
  KEY = "template-offline-test-secret-01!"

  def app
    Lingara::Apps::RackApp.new(LingaraApp.app("lgr_whsec_#{Base64.strict_encode64(KEY)}"))
  end

  def test_a_signed_render_returns_a_card
    id = "lgr_msg_0123456789abcdef0123456789abcdef"
    body = JSON.generate(type: "app.render", id: id, install_id: "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
      subject: "lgr_sub_template", slot: "home.side", locale: "zh-Hans", context: [])
    timestamp = Time.now.to_i.to_s
    # Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>.
    signature = Base64.strict_encode64(OpenSSL::HMAC.digest("SHA256", KEY, "#{id}.#{timestamp}.#{body}"))
    header "webhook-id", id
    header "webhook-timestamp", timestamp
    header "webhook-signature", "v1,#{signature}"
    post "/", body, "CONTENT_TYPE" => "application/json"
    assert_equal 200, last_response.status
    assert JSON.parse(last_response.body).key?("card"), "the reply carries a card"
  end
end
