//! The one seam every request crosses: reqwest on a current-thread tokio
//! runtime in production, a scripted fake in the unit tests. Everything
//! above it is synchronous, so the commands read as plain sequences.

use std::time::Duration;

/// One request on the wire, already spelled: method, absolute URL, headers
/// and an optional body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WireRequest {
    pub method: &'static str,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WireResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl WireResponse {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

/// A transport error is a connection failure: contract B2 rule 7.
pub trait Transport {
    fn send(&self, request: &WireRequest) -> Result<WireResponse, String>;
}

pub struct Reqwest {
    client: reqwest::Client,
    runtime: tokio::runtime::Runtime,
}

impl Reqwest {
    pub fn new() -> Result<Reqwest, String> {
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
        // No redirects: an unexpected 3xx is B2 rule 8's to classify.
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(60))
            .build()
            .map_err(|e| e.to_string())?;
        Ok(Reqwest { client, runtime })
    }
}

impl Transport for Reqwest {
    fn send(&self, request: &WireRequest) -> Result<WireResponse, String> {
        let method = reqwest::Method::from_bytes(request.method.as_bytes()).map_err(|e| e.to_string())?;
        let mut builder = self.client.request(method, &request.url);
        for (k, v) in &request.headers {
            builder = builder.header(k, v);
        }
        if !request.body.is_empty() {
            builder = builder.body(request.body.clone());
        }
        self.runtime.block_on(async {
            let response = builder.send().await.map_err(|e| e.to_string())?;
            let status = response.status().as_u16();
            let headers = response
                .headers()
                .iter()
                .map(|(k, v)| (k.as_str().to_string(), v.to_str().unwrap_or("").to_string()))
                .collect();
            let body = response.bytes().await.map_err(|e| e.to_string())?.to_vec();
            Ok(WireResponse { status, headers, body })
        })
    }
}
