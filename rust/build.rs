//! Generates `$OUT_DIR/assets.rs`: every file under `assets/templates/apps/`,
//! compiled in with `include_bytes!` (ADR 16.10.26ae D12), so the published
//! crate scaffolds from its own bytes and never reads a path outside itself.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

fn walk(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            walk(&path, out);
        } else {
            out.push(path);
        }
    }
}

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").expect("cargo sets CARGO_MANIFEST_DIR"));
    let root = manifest_dir.join("assets/templates/apps");
    println!("cargo:rerun-if-changed=assets");
    let mut files = Vec::new();
    walk(&root, &mut files);
    files.sort();
    let mut code = String::from("/// `(path under templates/apps/, bytes)`, sorted by path.\npub static TEMPLATES: &[(&str, &[u8])] = &[\n");
    for file in &files {
        let relative = file.strip_prefix(&root).expect("under the root").to_string_lossy().replace('\\', "/");
        let _ = writeln!(code, "    ({relative:?}, include_bytes!({:?})),", file.display().to_string());
    }
    code.push_str("];\n");
    let out = PathBuf::from(std::env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join("assets.rs");
    std::fs::write(out, code).expect("OUT_DIR is writable");
}
