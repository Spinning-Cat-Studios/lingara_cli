//! Contract B2's refusal on stderr: `error: <text> (<code>)` and any advice
//! lines, or under `--json` one JSON line. What a part-way create made is
//! named either way (D2), so nothing it made is left unnamed.

use serde_json::{Value, json};

use crate::exit::Fail;

/// The lines `fail` prints on stderr.
pub fn lines(fail: &Fail, json: bool) -> Vec<String> {
    if json {
        return vec![json_line(fail).to_string()];
    }
    let mut out = vec![format!("error: {} ({})", fail.message, fail.code)];
    out.extend(fail.hints.iter().cloned());
    out.extend(fail.made.iter().map(|m| format!("made: {} {}", m.kind, m.id)));
    out
}

fn json_line(fail: &Fail) -> Value {
    let mut error = json!({ "code": fail.code, "message": fail.message, "exit": fail.exit.code() });
    if !fail.hints.is_empty() {
        error["hints"] = json!(fail.hints);
    }
    if !fail.made.is_empty() {
        error["made"] = Value::Array(fail.made.iter().map(|m| json!({ "kind": m.kind, "id": m.id })).collect());
    }
    json!({ "error": error })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::exit::{Exit, Made};

    #[test]
    fn human_and_json_forms() {
        let mut fail = Fail::new(Exit::NotFound, "object_not_found", "No such object.").hint("try again");
        fail.made.push(Made { kind: "oauth_client".into(), id: "oc1".into() });
        assert_eq!(lines(&fail, false), ["error: No such object. (object_not_found)", "try again", "made: oauth_client oc1"]);
        let one = lines(&fail, true);
        let v: Value = serde_json::from_str(&one[0]).unwrap();
        assert_eq!((v["error"]["exit"].as_u64(), v["error"]["made"][0]["id"].as_str()), (Some(4), Some("oc1")));
    }
}
