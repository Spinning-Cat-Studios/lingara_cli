//! The mock platform (ADR 16.10.26ac D5 step 1): one case's exchanges on a
//! fresh `127.0.0.1` port, every request logged with its arrival time, and
//! an unexpected one answered `599` naming what was expected (I2).

mod arm;
pub mod http;
mod matcher;
pub mod platform_dev;
mod replay;

pub use arm::{fill, fill_json, substitutions};
use platform_dev::{DevCall, DevModel, Minted};

use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Instant;

use serde_json::json;
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

use crate::case::Case;
use crate::routes::Routes;
use http::HttpRequest;
use matcher::Exchanges;

const MISMATCH_STATUS: u16 = 599;

/// One request the mock received.
#[derive(Debug, Clone)]
pub struct Logged {
    pub request: HttpRequest,
    pub at: Instant,
    /// The JSON the answer carried, for I6's issued-secret scan.
    pub answer: Option<serde_json::Value>,
    /// Why no exchange matched it, so it was answered `599` (I2).
    pub mismatch: Option<String>,
}

#[derive(Debug)]
struct State {
    exchanges: Exchanges,
    log: Vec<Logged>,
    /// `mock: platform_dev`'s model, serving the `app_dev.*` routes.
    dev: Option<DevModel>,
}

/// What a finished case's mock saw.
#[derive(Debug, Default)]
pub struct Finished {
    pub log: Vec<Logged>,
    /// One line per exchange never consumed its `times`.
    pub unconsumed: Vec<String>,
    /// `platform_dev`'s call log, for `expect.calls`.
    pub dev_calls: Vec<DevCall>,
    /// Every secret `platform_dev` minted, which I6 counts as issued.
    pub minted: Vec<Minted>,
}

pub struct Mock {
    pub url: String,
    state: Arc<Mutex<State>>,
    task: JoinHandle<()>,
}

impl Mock {
    /// Binds a free port, arms `case` and serves until `finish`.
    pub async fn start(case: &Case, routes: &Routes) -> std::io::Result<Mock> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let url = format!("http://127.0.0.1:{}", listener.local_addr()?.port());
        let (order, items) = arm::arm(case, routes, &url);
        let exchanges = Exchanges::new(order, items, Instant::now());
        let dev = case.mock.map(|_| DevModel::new(&case.mock_seed.clone().unwrap_or_default(), routes));
        let state = Arc::new(Mutex::new(State { exchanges, log: Vec::new(), dev }));
        let shared = state.clone();
        let task = tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                tokio::spawn(connection(shared.clone(), stream));
            }
        });
        Ok(Mock { url, state, task })
    }

    pub fn finish(self) -> Finished {
        self.task.abort();
        let state = lock(&self.state);
        let (dev_calls, minted) = state.dev.as_ref().map(|d| (d.calls.clone(), d.minted.clone())).unwrap_or_default();
        Finished { log: state.log.clone(), unconsumed: state.exchanges.unconsumed(), dev_calls, minted }
    }
}

/// The model's answer as a scripted response.
fn dev_response(status: u16, json: Option<serde_json::Value>) -> crate::case::Response {
    crate::case::Response { delay_ms: None, status: Some(status), headers: None, json, text: None, redirect: None }
}

fn lock(state: &Mutex<State>) -> MutexGuard<'_, State> {
    state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

async fn connection(state: Arc<Mutex<State>>, mut stream: TcpStream) {
    let Ok(Some(request)) = http::read_request(&mut stream).await else { return };
    let at = Instant::now();
    let taken = {
        let mut s = lock(&state);
        let modelled = s.dev.as_mut().and_then(|d| d.answer(&request)).map(|(status, json)| dev_response(status, json));
        let taken = match modelled {
            Some(response) => Ok(response),
            None => s.exchanges.take(&request, at),
        };
        let (answer, mismatch) = match &taken {
            Ok(r) => (r.json.clone(), None),
            Err(reason) => (None, Some(reason.clone())),
        };
        s.log.push(Logged { request: request.clone(), at, answer, mismatch });
        taken
    };
    match taken {
        Ok(response) => {
            let _ = replay::respond(stream, &response, &request).await;
        }
        Err(reason) => {
            let body = serde_json::to_vec(&json!({ "conformance_mismatch": reason })).unwrap_or_default();
            let _ = stream.write_all(&http::whole(MISMATCH_STATUS, "application/json", &body)).await;
            let _ = stream.shutdown().await;
        }
    }
}
