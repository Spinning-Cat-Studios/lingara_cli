//! Reading `cases/<group>/<slug>.yaml`: the closed parse, then the id rule.

use std::path::{Path, PathBuf};

use super::{Case, GROUPS};

#[derive(Debug, Clone)]
pub struct Loaded {
    pub case: Case,
    pub path: PathBuf,
}

/// Parses one case. Unknown keys are refused by the types.
pub fn parse(text: &str) -> Result<Case, String> {
    serde_yaml::from_str(text).map_err(|e| e.to_string())
}

/// Every case under `dir`, sorted by path; or every refusal, each naming
/// its file.
pub fn load_dir(dir: &Path) -> Result<Vec<Loaded>, Vec<String>> {
    let mut paths = Vec::new();
    let groups = std::fs::read_dir(dir).map_err(|e| vec![format!("{}: {e}", dir.display())])?;
    for group in groups.flatten().map(|e| e.path()).filter(|p| p.is_dir()) {
        let files = std::fs::read_dir(&group).map_err(|e| vec![format!("{}: {e}", group.display())])?;
        paths.extend(files.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "yaml")));
    }
    paths.sort();
    let (mut cases, mut errors) = (Vec::new(), Vec::new());
    for path in paths {
        match load_one(&path) {
            Ok(case) => cases.push(Loaded { case, path }),
            Err(e) => errors.push(format!("{}: {e}", path.display())),
        }
    }
    if errors.is_empty() { Ok(cases) } else { Err(errors) }
}

fn load_one(path: &Path) -> Result<Case, String> {
    let text = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
    let case = parse(&text)?;
    let group = path.parent().and_then(Path::file_name).and_then(|g| g.to_str()).unwrap_or("");
    let slug = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
    if !GROUPS.contains(&group) {
        return Err(format!("group `{group}` is not one of {GROUPS:?}"));
    }
    if case.id != format!("{group}.{slug}") {
        return Err(format!("id `{}` must be `{group}.{slug}`", case.id));
    }
    Ok(case)
}
