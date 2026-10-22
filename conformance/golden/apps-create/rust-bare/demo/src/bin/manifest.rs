//! The manifest as code. `lingara apps update` runs `cargo run --quiet --bin
//! manifest` and uploads what this prints, so print nothing else.

use lingara_apps::{AppSlotName, BoxError, ContextSliceKind, manifest};

fn main() -> Result<(), BoxError> {
    let slots: Vec<AppSlotName> = serde_json::from_value(serde_json::json!(["home.side"]))?;
    let context: Vec<ContextSliceKind> = serde_json::from_value(serde_json::json!([]))?;
    let manifest = manifest()
        .default_locale("en")
        .name([("en", "Demo"), ("zh-Hans", "Demo")])
        .description([("en", "A Lingara app."), ("zh-Hans", "A Lingara app.")])
        // `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
        .render_url("https://dev.invalid/")
        .slots(slots)
        .context(context)
        .build()?; // a ManifestError names the rule an upload would refuse
    println!("{}", manifest.to_json());
    Ok(())
}
