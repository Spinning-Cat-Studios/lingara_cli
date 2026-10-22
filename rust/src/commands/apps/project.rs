//! A project's manifest and its registration (ADR 16.10.26ae D1 step 2, D6
//! steps 1, 4 and 5): read from `lingara/connection.js` by the literal
//! reader, or from `manifest_command`'s stdout; checked; its warnings
//! printed; and, for a row with no id, registered as a private app.

use std::path::Path;
use std::process::Stdio;

use serde_json::{Value, json};

use super::manifest::{self, warning_text};
use crate::app::Ctx;
use crate::assets::message;
use crate::commands::creates::Creation;
use crate::commands::objects::{APP, OAUTH_CLIENT};
use crate::connection_literal;
use crate::exit::Fail;
use crate::platform_toml::{PlatformToml, Row, write};
use crate::supervise;

/// The manifest's source as messages name it, relative to `cwd`.
pub fn source(toml: &PlatformToml, row: &Row, cwd: &Path) -> String {
    let project = toml.project_dir(row);
    let file = project.join(row.manifest.as_deref().unwrap_or("lingara/connection.js"));
    match &row.manifest_command {
        Some(command) => command.clone(),
        None => file.strip_prefix(cwd).unwrap_or(&file).display().to_string(),
    }
}

/// The wire manifest the project describes, checked before any request.
pub fn read(toml: &PlatformToml, row: &Row, cwd: &Path) -> Result<Value, Fail> {
    let project = toml.project_dir(row);
    let shown = source(toml, row, cwd);
    let manifest = match &row.manifest_command {
        Some(command) => run_command(command, &project)?,
        None => {
            let file = project.join(row.manifest.as_deref().unwrap_or("lingara/connection.js"));
            let text = std::fs::read_to_string(&file).map_err(|e| Fail::usage(format!("{shown}: {e}")))?;
            connection_literal::app_manifest(&text).map_err(|r| r.fail(&shown))?
        }
    };
    match manifest::broken_rule(&manifest) {
        Some(rule) => Err(Fail::new(crate::exit::Exit::Usage, "manifest_invalid", format!("{shown}: the manifest breaks the {rule} rule"))),
        None => Ok(manifest),
    }
}

/// D3: `manifest_command` through the platform shell, in the project; its
/// stdout is the wire manifest, its stderr the developer's to see.
fn run_command(command: &str, dir: &Path) -> Result<Value, Fail> {
    let mut shell = supervise::shell(command);
    shell.current_dir(dir).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::inherit());
    let output = shell.output().map_err(|e| Fail::failure(format!("{command}: {e}")))?;
    if !output.status.success() {
        let status = output.status.code().map_or_else(|| "a signal".to_string(), |c| c.to_string());
        return Err(Fail::failure(format!("{command} exited {status}; fix it, then run lingara apps update")));
    }
    serde_json::from_slice(&output.stdout).map_err(|e| Fail::usage(format!("{command} printed no manifest JSON: {e}")))
}

/// D6 step 5: one `manifest_warning` line each, never a failure.
pub fn warn(ctx: &mut Ctx, manifest: &Value) -> Vec<&'static str> {
    let codes = manifest::warnings(manifest);
    for code in &codes {
        ctx.out.note(&message("manifest_warning", &[("code", code), ("text", warning_text(code))]));
    }
    codes
}

/// The display name D2 derives: each `-` a space, each word capitalised.
pub fn display_name(name: &str) -> String {
    let words = name.split('-').map(|w| {
        let mut chars = w.chars();
        chars.next().map_or_else(String::new, |c| c.to_ascii_uppercase().to_string() + chars.as_str())
    });
    words.collect::<Vec<_>>().join(" ")
}

/// D1 step 2 / D6 step 4: the private OAuth client, then the app under it
/// through the registration form's own steps, the id written into the row.
/// Prints the app as the registration form does.
pub fn register(ctx: &mut Ctx, toml: &PlatformToml, row: &Row, manifest: Value) -> Result<Value, Fail> {
    let title = display_name(&row.name);
    let client_body = json!({ "client_type": "confidential", "allowed_scopes": [], "billing_mode": "allowance", "redirect_uris": [] });
    let mut made = Creation::default();
    let result = made.object(ctx, &OAUTH_CLIENT, &title, None, client_body).and_then(|client| {
        let client_id = client["id"].as_str().unwrap_or_default().to_string();
        let app = made.object(ctx, &APP, &title, Some(&client_id), json!({ "manifest": manifest }))?;
        Ok((APP, app["id"].as_str().unwrap_or_default().to_string()))
    });
    let app = made.conclude(ctx, result)?;
    write::set_id(&toml.file, &row.name, app["id"].as_str().unwrap_or_default())?;
    Ok(app)
}
