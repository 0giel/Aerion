//! Server List Ping: the status a Minecraft server shows in the multiplayer menu (MOTD, players,
//! version, icon) and the round-trip time. SRV records are not resolved (plain host:port).

use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::error::{Error, Result};

const TIMEOUT: Duration = Duration::from_secs(5);
/// Responses are a few KB (a 64×64 PNG icon at most); anything huge is not a Minecraft server.
const MAX_PACKET: usize = 256 * 1024;

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    pub online: u32,
    pub max: u32,
    pub version: String,
    /// Message of the day as plain text (formatting codes removed).
    pub motd: String,
    /// `data:image/png;base64,…`
    pub favicon: Option<String>,
    pub ping_ms: u32,
}

#[derive(Deserialize)]
struct RawStatus {
    #[serde(default)]
    version: Option<RawVersion>,
    #[serde(default)]
    players: Option<RawPlayers>,
    #[serde(default)]
    description: Option<serde_json::Value>,
    #[serde(default)]
    favicon: Option<String>,
}
#[derive(Deserialize)]
struct RawVersion {
    #[serde(default)]
    name: String,
}
#[derive(Deserialize)]
struct RawPlayers {
    #[serde(default)]
    online: u32,
    #[serde(default)]
    max: u32,
}

/// `host`, `host:port` or `[ipv6]:port`; default port 25565.
pub fn parse_address(address: &str) -> Result<(String, u16)> {
    let a = address.trim();
    let bad = || Error::Invalid(format!("invalid server address {address:?}"));
    if let Some(rest) = a.strip_prefix('[') {
        let (host, port) = rest.split_once(']').ok_or_else(bad)?;
        let port = match port.strip_prefix(':') {
            Some(p) => p.parse().map_err(|_| bad())?,
            None => 25565,
        };
        return Ok((host.to_owned(), port));
    }
    match a.rsplit_once(':') {
        Some((h, p)) if !h.contains(':') => Ok((h.to_owned(), p.parse().map_err(|_| bad())?)),
        _ if !a.is_empty() => Ok((a.to_owned(), 25565)),
        _ => Err(bad()),
    }
}

fn varint(mut v: i32, out: &mut Vec<u8>) {
    loop {
        let mut b = (v & 0x7f) as u8;
        v = ((v as u32) >> 7) as i32;
        if v != 0 {
            b |= 0x80;
        }
        out.push(b);
        if v == 0 {
            break;
        }
    }
}

async fn read_varint(s: &mut TcpStream) -> Result<i32> {
    let mut value = 0i32;
    for i in 0..5 {
        let b = s.read_u8().await.map_err(net_err)?;
        value |= ((b & 0x7f) as i32) << (7 * i);
        if b & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(Error::parse("server status", "varint too long"))
}

fn net_err(e: std::io::Error) -> Error {
    Error::Invalid(format!("server unreachable: {e}"))
}

fn packet(id: i32, body: &[u8]) -> Vec<u8> {
    let mut inner = Vec::new();
    varint(id, &mut inner);
    inner.extend(body);
    let mut out = Vec::new();
    varint(inner.len() as i32, &mut out);
    out.extend(inner);
    out
}

/// Plain text of a chat component (string, `{text, extra}` or array), without `§x` codes.
fn plain_text(v: &serde_json::Value) -> String {
    fn walk(v: &serde_json::Value, out: &mut String) {
        match v {
            serde_json::Value::String(s) => out.push_str(s),
            serde_json::Value::Array(a) => a.iter().for_each(|x| walk(x, out)),
            serde_json::Value::Object(o) => {
                if let Some(t) = o.get("text") {
                    walk(t, out);
                }
                if let Some(e) = o.get("extra") {
                    walk(e, out);
                }
            }
            _ => {}
        }
    }
    let mut raw = String::new();
    walk(v, &mut raw);
    let mut out = String::new();
    let mut chars = raw.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
        } else {
            out.push(c);
        }
    }
    out.trim().to_owned()
}

async fn exchange(host: &str, port: u16) -> Result<ServerStatus> {
    let mut s = TcpStream::connect((host, port)).await.map_err(net_err)?;
    let _ = s.set_nodelay(true);
    let mut hs = Vec::new();
    varint(-1, &mut hs); // protocol: "any", as clients do when pinging
    varint(host.len() as i32, &mut hs);
    hs.extend(host.as_bytes());
    hs.extend(port.to_be_bytes());
    varint(1, &mut hs); // next state: status
    let mut req = packet(0, &hs);
    req.extend(packet(0, &[]));
    s.write_all(&req).await.map_err(net_err)?;

    let len = read_varint(&mut s).await? as usize;
    if len == 0 || len > MAX_PACKET {
        return Err(Error::parse("server status", "bad packet length"));
    }
    let mut buf = vec![0; len];
    s.read_exact(&mut buf).await.map_err(net_err)?;
    // Packet id (0) + string length, both varints.
    let mut pos = 0;
    let mut skip_varint = || {
        while pos < buf.len() && buf[pos] & 0x80 != 0 {
            pos += 1;
        }
        pos += 1;
    };
    skip_varint();
    skip_varint();
    let json = buf
        .get(pos..)
        .ok_or_else(|| Error::parse("server status", "short packet"))?;
    let raw: RawStatus =
        serde_json::from_slice(json).map_err(|e| Error::parse("server status", e))?;

    let started = Instant::now();
    s.write_all(&packet(1, &42i64.to_be_bytes()))
        .await
        .map_err(net_err)?;
    let pong_len = read_varint(&mut s).await?;
    let mut pong = vec![0; pong_len.clamp(0, 64) as usize];
    let _ = s.read_exact(&mut pong).await;
    let ping_ms = started.elapsed().as_millis().min(u32::MAX as u128) as u32;

    Ok(ServerStatus {
        online: raw.players.as_ref().map_or(0, |p| p.online),
        max: raw.players.as_ref().map_or(0, |p| p.max),
        version: raw.version.map(|v| v.name).unwrap_or_default(),
        motd: raw.description.as_ref().map(plain_text).unwrap_or_default(),
        favicon: raw
            .favicon
            .filter(|f| f.starts_with("data:image/png;base64,") && f.len() < 200_000),
        ping_ms,
    })
}

pub async fn status(address: &str) -> Result<ServerStatus> {
    let (host, port) = parse_address(address)?;
    tokio::time::timeout(TIMEOUT, exchange(&host, port))
        .await
        .map_err(|_| Error::Invalid("server did not answer in time".into()))?
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tokio::net::TcpListener;

    #[test]
    fn addresses_and_text() {
        assert_eq!(
            parse_address("mc.example.com").unwrap(),
            ("mc.example.com".into(), 25565)
        );
        assert_eq!(
            parse_address("1.2.3.4:25566").unwrap(),
            ("1.2.3.4".into(), 25566)
        );
        assert_eq!(parse_address("[::1]:1").unwrap(), ("::1".into(), 1));
        assert!(parse_address("host:notaport").is_err());
        let motd = json!({"text": "§aWitaj ", "extra": [{"text": "na serwerze"}, "§l!"]});
        assert_eq!(plain_text(&motd), "Witaj na serwerze!");
    }

    #[tokio::test]
    async fn pings_a_fake_server() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 512];
            let _ = s.read(&mut buf).await.unwrap();
            let body = json!({"version": {"name": "26.3", "protocol": 999},
                "players": {"max": 20, "online": 3}, "description": "Serwer testowy"})
            .to_string();
            let mut payload = Vec::new();
            varint(body.len() as i32, &mut payload);
            payload.extend(body.as_bytes());
            s.write_all(&packet(0, &payload)).await.unwrap();
            let mut ping = [0u8; 10];
            s.read_exact(&mut ping).await.unwrap();
            s.write_all(&ping).await.unwrap();
        });
        let st = status(&format!("127.0.0.1:{port}")).await.unwrap();
        assert_eq!((st.online, st.max, st.version.as_str()), (3, 20, "26.3"));
        assert_eq!(st.motd, "Serwer testowy");
    }
}
