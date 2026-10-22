use std::collections::BTreeSet;

use crate::docs::{render, write};
use crate::model::{parse, Spec};
use crate::walk::walk;

const SCHEMA: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../spec/cli.schema.json");

/// 16.10.26ab AC6
#[test]
fn ac6_reference_is_deterministic_and_complete() {
    let text = std::fs::read_to_string(SCHEMA).unwrap();
    let spec: Spec = parse(&text).unwrap();
    let pages = render(&spec, &text);

    let files: BTreeSet<&str> = pages.keys().map(String::as_str).collect();
    let want: BTreeSet<&str> = [
        "reference/apps.md",
        "reference/embed.md",
        "reference/oauth_client.md",
        "reference/webhook.md",
        "reference/canvas.md",
        "reference/auth.md",
        "reference/exit-codes.md",
        "cli.schema.json",
    ]
    .into();
    assert_eq!(files, want);
    assert_eq!(pages["cli.schema.json"], text);

    // One `###` per leaf command, its text the full path without the binary.
    let all: String = pages.values().cloned().collect();
    for site in &walk(&spec).commands {
        let heading = format!("\n### {}\n", site.path());
        assert_eq!(all.matches(&heading).count(), 1, "{heading:?}");
    }
    assert!(pages["reference/webhook.md"].contains("\n### webhook inbound create\n"));
    assert!(pages["reference/auth.md"].contains("\n### login\n"));

    // The danger notice with the flagged usage, and the alias beside its topic.
    let client = &pages["reference/oauth_client.md"];
    assert!(client.contains("> **Dangerous.**"));
    assert!(client.contains("`lingara oauth_client delete <id> -y --confirm <email>`"));
    assert!(client.contains("Alias: `client`."));
    assert!(client.contains("`lingara client delete`"));
    assert!(pages["reference/exit-codes.md"].contains("| 8 | `platform_disabled` |"));

    // Two runs, and two writes, are byte-identical; a stale page is removed.
    assert_eq!(render(&spec, &text), pages);
    let (a, b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
    std::fs::create_dir_all(b.path().join("reference")).unwrap();
    std::fs::write(b.path().join("reference/stale.md"), "old").unwrap();
    write(a.path(), &pages).unwrap();
    write(b.path(), &pages).unwrap();
    for rel in pages.keys() {
        assert_eq!(std::fs::read(a.path().join(rel)).unwrap(), std::fs::read(b.path().join(rel)).unwrap());
    }
    assert!(!b.path().join("reference/stale.md").exists());
}
