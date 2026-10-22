//! Rendering a template (ADR 16.10.26ae D2, D3; spec/templates.md): the
//! fixed `{{key}}` set, the array syntax each language's bare template
//! uses, the two renaming rules, and `template.toml`, read and never written.

use std::path::{Path, PathBuf};

use crate::assets;
use crate::exit::Fail;

/// What the scaffold substitutes, each derived once (spec/templates.md).
pub struct Keys {
    pub name: String,
    pub display_name: String,
    pub description: String,
    pub port: u16,
    pub slots: Vec<String>,
    pub context: Vec<String>,
}

/// `template.toml`: the row's start command and its manifest source.
#[derive(Debug, Default)]
pub struct Meta {
    pub start: Option<String>,
    /// `("manifest" | "manifest_command", value)`.
    pub manifest: Option<(String, String)>,
}

/// One file, at its path in the project.
pub struct Rendered {
    pub path: PathBuf,
    pub bytes: Vec<u8>,
}

const META: &str = "template.toml";

/// `["a", "b"]` in the syntax the template's language writes an array in.
fn array(lang: &str, variant: &str, items: &[String]) -> String {
    let inner = items.iter().map(|i| format!("\"{i}\"")).collect::<Vec<_>>().join(", ");
    match (variant, lang) {
        ("bare", "go") => format!("[]string{{{inner}}}"),
        ("bare", "java") => format!("List.of({inner})"),
        ("bare", "kotlin") => format!("listOf({inner})"),
        _ => format!("[{inner}]"),
    }
}

/// A segment starting `_` is written with `.`; a `.tmpl` suffix is dropped.
fn project_path(relative: &str) -> PathBuf {
    let segments = relative.split('/').map(|s| match s.strip_prefix('_') {
        Some(rest) => format!(".{rest}"),
        None => s.to_string(),
    });
    let mut path: PathBuf = segments.collect();
    if let Some(name) = path.file_name().and_then(|n| n.to_str()).and_then(|n| n.strip_suffix(".tmpl")).map(str::to_string) {
        path.set_file_name(name);
    }
    path
}

fn substitute(text: &str, lang: &str, variant: &str, keys: &Keys) -> String {
    let pairs = [
        ("{{name}}", keys.name.clone()),
        ("{{display_name}}", keys.display_name.clone()),
        ("{{description}}", keys.description.clone()),
        ("{{port}}", keys.port.to_string()),
        ("{{slots}}", array(lang, variant, &keys.slots)),
        ("{{context}}", array(lang, variant, &keys.context)),
    ];
    pairs.iter().fold(text.to_string(), |t, (k, v)| t.replace(k, v))
}

/// Every file of `<lang>/<variant>`, rendered, and its `template.toml`.
pub fn render(lang: &str, variant: &str, keys: &Keys) -> Result<(Vec<Rendered>, Meta), Fail> {
    let files = assets::template(lang, variant);
    if files.is_empty() {
        return Err(Fail::failure(format!("this lingara carries no {lang} {variant} template")));
    }
    let mut meta = Meta::default();
    let mut out = Vec::new();
    for (relative, bytes) in files {
        let text = String::from_utf8_lossy(bytes);
        if relative == META {
            meta = parse_meta(&text)?;
            continue;
        }
        out.push(Rendered { path: project_path(relative), bytes: substitute(&text, lang, variant, keys).into_bytes() });
    }
    Ok((out, meta))
}

fn parse_meta(text: &str) -> Result<Meta, Fail> {
    let value: toml::Value = toml::from_str(text).map_err(|e| Fail::failure(format!("{META}: {e}")))?;
    let get = |k: &str| value.get(k).and_then(toml::Value::as_str).map(str::to_string);
    let manifest = match (get("manifest"), get("manifest_command")) {
        (Some(m), _) => Some(("manifest".to_string(), m)),
        (None, Some(c)) => Some(("manifest_command".to_string(), c)),
        (None, None) => None,
    };
    Ok(Meta { start: get("start"), manifest })
}

/// Writes every file under `dir`, creating directories as it goes.
pub fn write_all(dir: &Path, files: &[Rendered]) -> Result<(), Fail> {
    for file in files {
        let path = dir.join(&file.path);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| Fail::failure(format!("{}: {e}", parent.display())))?;
        }
        std::fs::write(&path, &file.bytes).map_err(|e| Fail::failure(format!("{}: {e}", path.display())))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arrays_paths_and_keys_follow_the_spec() {
        let two = vec!["home.side".to_string(), "plans.empty_detail".to_string()];
        assert_eq!(array("go", "default", &two), r#"["home.side", "plans.empty_detail"]"#);
        assert_eq!(array("go", "bare", &two), r#"[]string{"home.side", "plans.empty_detail"}"#);
        assert_eq!(array("kotlin", "bare", &[]), "listOf()");
        assert_eq!(project_path("_gitignore"), PathBuf::from(".gitignore"));
        assert_eq!(project_path("Cargo.toml.tmpl"), PathBuf::from("Cargo.toml"));
        assert_eq!(project_path("src/app.ts"), PathBuf::from("src/app.ts"));
        let keys = Keys { name: "a".into(), display_name: "A".into(), description: "d".into(), port: 1, slots: two, context: vec![] };
        assert_eq!(substitute("{{name}} {{other}} {{port}}", "rust", "default", &keys), "a {{other}} 1");
    }
}
