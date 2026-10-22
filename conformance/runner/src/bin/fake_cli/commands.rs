//! The commands the committed cases and the runner's tests drive.

use std::io::{BufRead, BufReader, IsTerminal, Write};
use std::net::TcpListener;
use std::time::Duration;

use serde_json::Value;

use crate::classify::{PLATFORM_DISABLED_TEXT, classify};
use crate::net::{self, CLIENT_ID, Session};
use crate::{Ctx, Fail, exit, ritual};

/// The flags each command takes; anything else is `usage` (B2).
const FLAGS: &[(&str, &[&str])] = &[
    ("apps list", &["json"]),
    ("client get", &["json"]),
    ("oauth_client get", &["json"]),
    ("client delete", &["json", "yes", "confirm", "version"]),
    ("oauth_client delete", &["json", "yes", "confirm", "version"]),
    ("login", &["json", "device"]),
    ("probe", &[]),
    ("hang", &[]),
];

pub fn dispatch(ctx: &Ctx) -> Result<(), Fail> {
    let words: Vec<&str> = ctx.words.iter().map(String::as_str).collect();
    let usage = |what: String| Err(Fail::new(exit::USAGE, "usage", what));
    let Some((path, allowed)) = FLAGS.iter().find(|(p, _)| words.starts_with(&p.split(' ').collect::<Vec<_>>())) else {
        let code = if ctx.script.has("unknown_exits_4") { exit::NOT_FOUND } else { exit::USAGE };
        return Err(Fail::new(code, "usage", format!("unknown command {:?}", ctx.words)));
    };
    if let Some((bad, _)) = ctx.flags.iter().find(|(n, _)| !allowed.contains(&n.as_str())) {
        return usage(format!("unknown flag --{bad} for `{path}`"));
    }
    let arg = words.get(path.split(' ').count()).map(|s| s.to_string());
    match (*path, arg) {
        ("apps list", _) => apps_list(ctx),
        ("client get" | "oauth_client get", Some(id)) => client_get(ctx, &id),
        ("client delete" | "oauth_client delete", Some(id)) => ritual::delete(ctx, &id),
        ("login", _) if ctx.flag("device").is_some() => login_device(ctx),
        ("login", _) => login_browser(ctx),
        ("probe", _) => probe(),
        ("hang", _) => {
            std::thread::sleep(Duration::from_secs(60));
            Ok(())
        }
        (p, None) => usage(format!("`{p}` needs an id")),
        _ => usage("unreachable".into()),
    }
}

fn cell(v: &Value) -> String {
    match v {
        Value::Null => "-".into(),
        Value::String(s) if s.is_empty() => "-".into(),
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// B1's human table: padded, two spaces apart, no trailing whitespace.
fn print_table(rows: &[Vec<String>]) {
    let widths: Vec<usize> = (0..rows[0].len()).map(|i| rows.iter().map(|r| r[i].chars().count()).max().unwrap_or(0)).collect();
    for row in rows {
        let cells: Vec<String> = row.iter().zip(&widths).map(|(c, w)| format!("{c:<w$}")).collect();
        println!("{}", cells.join("  ").trim_end());
    }
}

/// Every cursor page, printed once (B1).
fn apps_list(ctx: &Ctx) -> Result<(), Fail> {
    let mut session = Session::open(&ctx.script)?;
    let mut items = Vec::new();
    let mut target = "/platform/objects?kind=app".to_string();
    loop {
        let page = session.call("GET", &target, None, "app.list")?;
        items.extend(page["items"].as_array().cloned().unwrap_or_default());
        match page["next_cursor"].as_str() {
            Some(c) => target = format!("/platform/objects?kind=app&cursor={}", net::encode(c)),
            None => break,
        }
    }
    if ctx.json {
        println!("{}", Value::Array(items));
        return Ok(());
    }
    let mut rows = vec![["ID", "NAME", "CLIENT", "SERVING", "VISIBILITY"].map(String::from).to_vec()];
    for item in &items {
        rows.push(["id", "name", "parent_id", "serving_version", "visibility"].iter().map(|k| cell(&item[*k])).collect());
    }
    print_table(&rows);
    Ok(())
}

fn client_get(ctx: &Ctx, id: &str) -> Result<(), Fail> {
    let mut session = Session::open(&ctx.script)?;
    let object = session.call("GET", &format!("/platform/objects/{id}"), None, "oauth_client.get")?;
    if ctx.json {
        println!("{object}");
        return Ok(());
    }
    let mut rows = vec![vec!["FIELD".to_string(), "VALUE".to_string()]];
    for (k, v) in object.as_object().into_iter().flatten() {
        rows.push(vec![k.clone(), cell(v)]);
    }
    print_table(&rows);
    Ok(())
}

fn login_device(ctx: &Ctx) -> Result<(), Fail> {
    let scope = "platform:read platform:write";
    let resp = net::send("POST", "/oauth/device_authorization", None, net::form(&[("client_id", CLIENT_ID), ("scope", scope)]))?;
    let Some(grant) = resp.json().filter(|_| resp.status == 200) else { return Err(classify(&ctx.script, &resp, "oauth.device_authorization")) };
    eprintln!("Open {} and enter {}", grant["verification_uri"].as_str().unwrap_or(""), grant["user_code"].as_str().unwrap_or(""));
    let mut interval = grant["interval"].as_u64().unwrap_or(5);
    let device_code = grant["device_code"].as_str().unwrap_or("").to_string();
    loop {
        std::thread::sleep(Duration::from_secs(interval));
        let form = [("grant_type", "urn:ietf:params:oauth:grant-type:device_code"), ("device_code", &device_code), ("client_id", CLIENT_ID)];
        match net::token(&ctx.script, &form) {
            Ok(tokens) => return signed_in(&tokens),
            Err(fail) if fail.message == "authorization_pending" => {}
            Err(fail) if fail.message == "slow_down" => interval += 5,
            Err(fail) if fail.exit == exit::PLATFORM_DISABLED => {
                return Err(Fail::new(exit::PLATFORM_DISABLED, "platform_disabled", PLATFORM_DISABLED_TEXT));
            }
            Err(fail) => return Err(fail),
        }
    }
}

fn signed_in(tokens: &Value) -> Result<(), Fail> {
    net::store(tokens, "");
    eprintln!("Signed in.");
    Ok(())
}

/// PKCE's loopback half: print the authorize URL, take one callback.
fn login_browser(ctx: &Ctx) -> Result<(), Fail> {
    let fail = |e: std::io::Error| Fail::new(exit::FAILURE, "failure", e.to_string());
    let listener = TcpListener::bind("127.0.0.1:0").map_err(fail)?;
    let redirect = format!("http://127.0.0.1:{}/callback", listener.local_addr().map_err(fail)?.port());
    let query = format!("response_type=code&client_id={CLIENT_ID}&redirect_uri={}&state=st_conformance", net::encode(&redirect));
    eprintln!("Open {}/oauth/authorize?{query}", net::base());
    let (mut stream, _) = listener.accept().map_err(fail)?;
    let mut line = String::new();
    BufReader::new(&stream).read_line(&mut line).map_err(fail)?;
    let _ = stream.write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 10\r\nconnection: close\r\n\r\nSigned in.");
    let params = line.split(' ').nth(1).and_then(|t| t.split_once('?')).map_or("", |(_, q)| q).to_string();
    let get = |k: &str| params.split('&').find_map(|p| p.strip_prefix(&format!("{k}="))).unwrap_or("").to_string();
    if get("state") != "st_conformance" {
        return Err(Fail::new(exit::AUTH_REQUIRED, "auth_required", "the callback's state did not match"));
    }
    let code = get("code");
    let form = [("grant_type", "authorization_code"), ("code", code.as_str()), ("redirect_uri", &redirect), ("client_id", CLIENT_ID)];
    signed_in(&net::token(&ctx.script, &form)?)
}

/// For the runner's pty test: which streams are terminals, then one prompt.
fn probe() -> Result<(), Fail> {
    println!("stdin={} stdout={} stderr={}", std::io::stdin().is_terminal(), std::io::stdout().is_terminal(), std::io::stderr().is_terminal());
    eprint!("name? ");
    let mut name = String::new();
    let _ = std::io::stdin().read_line(&mut name);
    println!("hello {}", name.trim());
    Ok(())
}
