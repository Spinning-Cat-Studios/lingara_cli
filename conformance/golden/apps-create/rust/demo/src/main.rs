use lingara_apps::BoxError;

#[tokio::main]
async fn main() -> Result<(), BoxError> {
    let secret = std::env::var("LINGARA_APP_SECRET").map_err(|_| "LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`")?;
    let app = lingara_app::app(secret.split(',').map(str::to_string).collect())?;
    let port: u16 = std::env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8787);
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await?;
    println!("demo is listening on http://127.0.0.1:{port}/");
    // The adapter reads the raw body, verifies, dispatches and replies.
    axum::serve(listener, lingara_apps::axum::router(app)).await?;
    Ok(())
}
