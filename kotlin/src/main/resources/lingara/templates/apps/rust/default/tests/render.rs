use axum::body::{Body, to_bytes};
use axum::http::Request;
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use tower::ServiceExt;

/// A visibly fake key: the secret is lgr_whsec_ + its base64.
const KEY: &[u8] = b"template-offline-test-secret-01!";

/// Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>.
fn signature(id: &str, timestamp: &str, body: &str) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(KEY).expect("any key length");
    mac.update(format!("{id}.{timestamp}.{body}").as_bytes());
    format!("v1,{}", STANDARD.encode(mac.finalize().into_bytes()))
}

#[tokio::test]
async fn a_signed_render_returns_a_card() {
    let app = lingara_app::app(vec![format!("lgr_whsec_{}", STANDARD.encode(KEY))]).expect("the app builds");
    let id = "lgr_msg_0123456789abcdef0123456789abcdef";
    let timestamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("after 1970").as_secs().to_string();
    let body = format!(
        r#"{{"type":"app.render","id":"{id}","install_id":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f","subject":"lgr_sub_template","slot":"home.side","locale":"zh-Hans","context":[]}}"#
    );
    let request = Request::post("/")
        .header("content-type", "application/json")
        .header("webhook-id", id)
        .header("webhook-timestamp", &timestamp)
        .header("webhook-signature", signature(id, &timestamp, &body))
        .body(Body::from(body))
        .expect("a request");
    let response = lingara_apps::axum::router(app).oneshot(request).await.expect("an answer");
    assert_eq!(response.status(), 200);
    let reply = to_bytes(response.into_body(), usize::MAX).await.expect("a body");
    assert!(String::from_utf8_lossy(&reply).contains(r#""card""#), "the reply carries a card");
}
