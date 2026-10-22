use axum::http::{HeaderMap, HeaderValue};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, Mac};
use sha2::Sha256;

/// A visibly fake key: the secret is lgr_whsec_ + its base64.
const KEY: &[u8] = b"template-offline-test-secret-01!";

/// Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>.
fn signed(id: &str, body: &str) -> HeaderMap {
    let timestamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("after 1970").as_secs().to_string();
    let mut mac = Hmac::<Sha256>::new_from_slice(KEY).expect("any key length");
    mac.update(format!("{id}.{timestamp}.{body}").as_bytes());
    let mut headers = HeaderMap::new();
    headers.insert("webhook-id", HeaderValue::from_str(id).expect("ascii"));
    headers.insert("webhook-timestamp", HeaderValue::from_str(&timestamp).expect("ascii"));
    let signature = format!("v1,{}", STANDARD.encode(mac.finalize().into_bytes()));
    headers.insert("webhook-signature", HeaderValue::from_str(&signature).expect("ascii"));
    headers
}

#[tokio::test]
async fn a_signed_render_returns_a_card() {
    let app = lingara_app::app(vec![format!("lgr_whsec_{}", STANDARD.encode(KEY))]).expect("the app builds");
    let id = "lgr_msg_0123456789abcdef0123456789abcdef";
    let body = format!(
        r#"{{"type":"app.render","id":"{id}","install_id":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f","subject":"lgr_sub_template","slot":"home.side","locale":"zh-Hans","context":[]}}"#
    );
    let reply = app.handle(&signed(id, &body), body.as_bytes()).await;
    assert_eq!(reply.status, 200);
    assert!(String::from_utf8_lossy(&reply.body).contains(r#""card""#), "the reply carries a card");
}
