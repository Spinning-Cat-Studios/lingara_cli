//! The server, on the kit's primitives: `App::check_method` and `App::handle`
//! do the method, size, signature, decode, dispatch and validation; this file
//! only moves bytes.

use std::sync::Arc;

use axum::body::{Body, to_bytes};
use axum::extract::Request;
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::Response;
use lingara_apps::{App, BoxError};

/// Lingara never sends a render over 64 KiB; the core refuses one with a 413.
const BODY_LIMIT: usize = 65_537;

async fn answer(app: Arc<App>, request: Request) -> Response {
    let reply = match App::check_method(request.method().as_str()) {
        Err(refused) => refused,
        Ok(()) => {
            let (parts, body) = request.into_parts();
            let body = to_bytes(body, BODY_LIMIT).await.unwrap_or_default();
            app.handle(&parts.headers, &body).await
        }
    };
    let mut response = Response::new(Body::from(reply.body));
    *response.status_mut() = StatusCode::from_u16(reply.status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    if let Some(content_type) = reply.content_type {
        response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    }
    response
}

#[tokio::main]
async fn main() -> Result<(), BoxError> {
    let secret = std::env::var("LINGARA_APP_SECRET").map_err(|_| "LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`")?;
    let app = Arc::new(lingara_app::app(secret.split(',').map(str::to_string).collect())?);
    let routes = axum::Router::new().route("/", axum::routing::any(move |request: Request| answer(Arc::clone(&app), request)));
    let port: u16 = std::env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8787);
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await?;
    println!("demo is listening on http://127.0.0.1:{port}/");
    axum::serve(listener, routes).await?;
    Ok(())
}
