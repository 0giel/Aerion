//! Microsoft identity platform (consumers tenant): authorization code + PKCE through a
//! loopback redirect, device code flow as a fallback, and refresh.

use std::time::Duration;

use base64::Engine as _;
use serde::Deserialize;
use sha2::Digest;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::auth::Endpoints;
use crate::error::{AuthError, Error, Result};
use crate::net::client::Http;
use crate::util::now_secs;

pub const SCOPE: &str = "XboxLive.signin offline_access";

#[derive(Debug, Clone)]
pub struct MsaTokens {
    pub access_token: String,
    pub refresh_token: String,
    pub expires_at: i64,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    #[serde(default)]
    refresh_token: Option<String>,
    #[serde(default)]
    expires_in: Option<i64>,
}

#[derive(Deserialize)]
struct OAuthError {
    error: String,
    #[serde(default)]
    error_description: Option<String>,
}

fn b64url(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn random_b64(n: usize) -> String {
    let mut buf = vec![0u8; n];
    getrandom::fill(&mut buf).expect("OS random number generator");
    b64url(&buf)
}

/// PKCE verifier (43 chars, RFC 7636) and its S256 challenge.
pub fn pkce_pair() -> (String, String) {
    let verifier = random_b64(32);
    let challenge = b64url(&sha2::Sha256::digest(verifier.as_bytes()));
    (verifier, challenge)
}

async fn token_request(http: &Http, ep: &Endpoints, form: &[(&str, &str)]) -> Result<MsaTokens> {
    let url = format!("{}/token", ep.msa);
    let resp = http.send(|c| c.post(&url).form(form)).await?;
    let status = resp.status();
    let body = resp.bytes().await?;
    if !status.is_success() {
        return Err(match serde_json::from_slice::<OAuthError>(&body) {
            Ok(e) if e.error == "authorization_declined" || e.error == "access_denied" => {
                AuthError::Declined.into()
            }
            Ok(e) if e.error == "expired_token" => AuthError::Expired.into(),
            Ok(e) => AuthError::OAuth {
                error: e.error,
                description: e.error_description,
            }
            .into(),
            Err(_) => Error::Http {
                status: status.as_u16(),
                url,
                body: None,
            },
        });
    }
    let t: TokenResponse =
        serde_json::from_slice(&body).map_err(|e| Error::parse("token response", e))?;
    Ok(MsaTokens {
        access_token: t.access_token,
        refresh_token: t.refresh_token.unwrap_or_default(),
        expires_at: now_secs() + t.expires_in.unwrap_or(3600),
    })
}

pub async fn refresh(
    http: &Http,
    ep: &Endpoints,
    client_id: &str,
    refresh_token: &str,
) -> Result<MsaTokens> {
    let mut t = token_request(
        http,
        ep,
        &[
            ("client_id", client_id),
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("scope", SCOPE),
        ],
    )
    .await
    .map_err(|e| match e {
        Error::Auth(AuthError::OAuth { error, .. }) if error == "invalid_grant" => {
            AuthError::SessionExpired.into()
        }
        other => other,
    })?;
    if t.refresh_token.is_empty() {
        t.refresh_token = refresh_token.to_owned();
    }
    Ok(t)
}

/// An authorization-code login waiting for the browser to come back to localhost.
pub struct LoopbackLogin {
    pub auth_url: String,
    redirect_uri: String,
    verifier: String,
    state: String,
    listeners: Vec<TcpListener>,
}

impl LoopbackLogin {
    /// Bind a random loopback port (IPv4 and, when possible, the same port on IPv6) and
    /// build the authorization URL to open in the system browser.
    pub async fn start(ep: &Endpoints, client_id: &str) -> Result<Self> {
        let v4 = TcpListener::bind(("127.0.0.1", 0))
            .await
            .map_err(|e| Error::io("127.0.0.1", e))?;
        let port = v4
            .local_addr()
            .map_err(|e| Error::io("127.0.0.1", e))?
            .port();
        let mut listeners = vec![v4];
        if let Ok(v6) = TcpListener::bind(("::1", port)).await {
            listeners.push(v6);
        }
        let redirect_uri = format!("http://localhost:{port}");
        let (verifier, challenge) = pkce_pair();
        let state = random_b64(16);
        let mut url = reqwest::Url::parse(&format!("{}/authorize", ep.msa))
            .map_err(|e| Error::Invalid(e.to_string()))?;
        url.query_pairs_mut()
            .append_pair("client_id", client_id)
            .append_pair("response_type", "code")
            .append_pair("redirect_uri", &redirect_uri)
            .append_pair("response_mode", "query")
            .append_pair("scope", SCOPE)
            .append_pair("state", &state)
            .append_pair("code_challenge", &challenge)
            .append_pair("code_challenge_method", "S256")
            .append_pair("prompt", "select_account");
        Ok(Self {
            auth_url: url.to_string(),
            redirect_uri,
            verifier,
            state,
            listeners,
        })
    }

    /// Wait for the redirect, answer the browser with a short page, exchange the code.
    pub async fn finish(
        self,
        http: &Http,
        ep: &Endpoints,
        client_id: &str,
        cancel: &CancellationToken,
    ) -> Result<MsaTokens> {
        let code = tokio::select! {
            _ = cancel.cancelled() => return Err(Error::Cancelled),
            _ = tokio::time::sleep(Duration::from_secs(600)) => return Err(AuthError::Expired.into()),
            c = accept_code(&self.listeners, &self.state) => c?,
        };
        token_request(
            http,
            ep,
            &[
                ("client_id", client_id),
                ("grant_type", "authorization_code"),
                ("code", &code),
                ("redirect_uri", &self.redirect_uri),
                ("code_verifier", &self.verifier),
                ("scope", SCOPE),
            ],
        )
        .await
    }
}

const PAGE_OK: &str = "<!doctype html><meta charset=utf-8><title>Aerion</title><body style=\"font:16px system-ui;background:#16171b;color:#eee;display:grid;place-items:center;height:90vh\"><div><h2>✔ Zalogowano / Signed in</h2><p>Możesz zamknąć tę kartę i wrócić do Aeriona.<br>You can close this tab and return to Aerion.</p></div>";
const PAGE_ERR: &str = "<!doctype html><meta charset=utf-8><title>Aerion</title><body style=\"font:16px system-ui;background:#16171b;color:#eee;display:grid;place-items:center;height:90vh\"><div><h2>✖ Logowanie przerwane / Sign-in cancelled</h2><p>Wróć do Aeriona, aby spróbować ponownie.<br>Return to Aerion to try again.</p></div>";

async fn accept_code(listeners: &[TcpListener], state: &str) -> Result<String> {
    loop {
        let (mut sock, _) = match listeners {
            [a] => a.accept().await,
            [a, b, ..] => tokio::select! { r = a.accept() => r, r = b.accept() => r },
            [] => return Err(Error::Invalid("no loopback listener".into())),
        }
        .map_err(|e| Error::io("loopback", e))?;
        let mut buf = vec![0u8; 8192];
        let n = tokio::time::timeout(Duration::from_secs(10), sock.read(&mut buf))
            .await
            .unwrap_or(Ok(0))
            .unwrap_or(0);
        let req = String::from_utf8_lossy(&buf[..n]);
        let target = req
            .lines()
            .next()
            .and_then(|l| l.split_whitespace().nth(1))
            .unwrap_or("/");
        let url = reqwest::Url::parse(&format!("http://localhost{target}")).ok();
        let query = |k: &str| {
            url.as_ref().and_then(|u| {
                u.query_pairs()
                    .find(|(key, _)| key == k)
                    .map(|(_, v)| v.into_owned())
            })
        };
        let (code, error) = (query("code"), query("error"));
        if code.is_none() && error.is_none() {
            // favicon.ico and other stray requests
            let _ = sock
                .write_all(
                    b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                )
                .await;
            continue;
        }
        let ok = code.is_some() && query("state").as_deref() == Some(state);
        let page = if ok { PAGE_OK } else { PAGE_ERR };
        let resp = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n{page}",
            page.len()
        );
        let _ = sock.write_all(resp.as_bytes()).await;
        let _ = sock.shutdown().await;
        return match (code, error) {
            (Some(c), _) if ok => Ok(c),
            (Some(_), _) => Err(AuthError::OAuth {
                error: "state_mismatch".into(),
                description: None,
            }
            .into()),
            (None, Some(e)) if e == "access_denied" => Err(AuthError::Declined.into()),
            (None, Some(e)) => Err(AuthError::OAuth {
                error: e,
                description: query("error_description"),
            }
            .into()),
            (None, None) => unreachable!(),
        };
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct DeviceCode {
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: i64,
    #[serde(default = "default_interval")]
    pub interval: u64,
    #[serde(default)]
    pub message: String,
    device_code: String,
}

fn default_interval() -> u64 {
    5
}

pub async fn device_code_start(http: &Http, ep: &Endpoints, client_id: &str) -> Result<DeviceCode> {
    let url = format!("{}/devicecode", ep.msa);
    let resp = http
        .send(|c| {
            c.post(&url)
                .form(&[("client_id", client_id), ("scope", SCOPE)])
        })
        .await?;
    let status = resp.status();
    let body = resp.bytes().await?;
    if !status.is_success() {
        return Err(match serde_json::from_slice::<OAuthError>(&body) {
            Ok(e) => AuthError::OAuth {
                error: e.error,
                description: e.error_description,
            }
            .into(),
            Err(_) => Error::Http {
                status: status.as_u16(),
                url,
                body: None,
            },
        });
    }
    serde_json::from_slice(&body).map_err(|e| Error::parse("device code response", e))
}

pub async fn device_code_poll(
    http: &Http,
    ep: &Endpoints,
    client_id: &str,
    dc: &DeviceCode,
    cancel: &CancellationToken,
) -> Result<MsaTokens> {
    let deadline = now_secs() + dc.expires_in;
    let mut interval = dc.interval.max(1);
    loop {
        tokio::select! {
            _ = cancel.cancelled() => return Err(Error::Cancelled),
            _ = tokio::time::sleep(Duration::from_secs(interval)) => {}
        }
        if now_secs() > deadline {
            return Err(AuthError::Expired.into());
        }
        match token_request(
            http,
            ep,
            &[
                ("client_id", client_id),
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("device_code", &dc.device_code),
            ],
        )
        .await
        {
            Ok(t) => return Ok(t),
            Err(Error::Auth(AuthError::OAuth { error, .. }))
                if error == "authorization_pending" => {}
            // RFC 8628 §3.5: increase the interval by 5 seconds.
            Err(Error::Auth(AuthError::OAuth { error, .. })) if error == "slow_down" => {
                interval += 5
            }
            Err(e) => return Err(e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_shapes() {
        let (v, c) = pkce_pair();
        assert_eq!(v.len(), 43);
        assert_eq!(c.len(), 43);
        assert_ne!(v, c);
        assert!(
            v.chars()
                .all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_')
        );
    }

    #[tokio::test]
    async fn loopback_receives_code() {
        let ep = Endpoints::default();
        let login = LoopbackLogin::start(&ep, "client").await.unwrap();
        let url = reqwest::Url::parse(&login.auth_url).unwrap();
        let q: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();
        assert_eq!(q["code_challenge_method"], "S256");
        assert_eq!(q["scope"], SCOPE);
        let redirect = q["redirect_uri"].clone();
        let state = q["state"].clone();
        let listeners = login.listeners;
        let browser = tokio::spawn(async move {
            let client = Http::new(&crate::net::client::NetConfig::default())
                .unwrap()
                .client()
                .clone();
            let _ = client.get(format!("{redirect}/favicon.ico")).send().await;
            client
                .get(format!("{redirect}/?code=abc123&state={state}"))
                .send()
                .await
                .unwrap()
                .text()
                .await
                .unwrap()
        });
        let code = accept_code(&listeners, &q["state"]).await.unwrap();
        assert_eq!(code, "abc123");
        assert!(browser.await.unwrap().contains("Signed in"));
    }
}
