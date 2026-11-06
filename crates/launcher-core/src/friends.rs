//! Friends without a server of our own: nicknames (faces from their Mojang skins) and the
//! address a friend shares when hosting a world (e4mc or LAN), to join with one click.

use base64::Engine as _;
use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::net::client::Http;

pub const MAX_FRIENDS: usize = 200;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Friend {
    pub name: String,
    /// Mojang profile id without dashes; `None` for names Mojang doesn't know (offline players).
    pub uuid: Option<String>,
    pub skin_url: Option<String>,
    /// The last address they shared (`abc-def.eu.e4mc.link`, `192.168.1.5:25565`…).
    pub address: Option<String>,
    /// Instance to join them with; `None` = the last played one.
    pub instance_id: Option<String>,
    /// Unix seconds.
    pub added: i64,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct PlayerProfile {
    pub name: String,
    pub uuid: String,
    pub skin_url: Option<String>,
}

/// Java Edition names: 1–16 letters, digits and underscores (old accounts have short ones).
pub fn valid_name(name: &str) -> bool {
    (1..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

/// The address in what a friend pasted: an e4mc address anywhere in a message, else the text
/// itself when it looks like `host` or `host:port`.
pub fn parse_address(text: &str) -> Option<String> {
    let words = text
        .split(|c: char| {
            c.is_whitespace() || matches!(c, '"' | '\'' | '`' | '<' | '>' | ',' | '(' | ')')
        })
        .map(|w| w.trim_end_matches(['.', '!', '?', ';']))
        .map(|w| w.strip_prefix("minecraft://").unwrap_or(w))
        .filter(|w| !w.is_empty());
    let mut fallback = None;
    for w in words {
        let host_ok = |h: &str| {
            !h.is_empty()
                && h.len() <= 253
                && h.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
        };
        let (host, port) = match w.rsplit_once(':') {
            Some((h, p)) if p.parse::<u16>().is_ok() => (h, Some(p)),
            _ => (w, None),
        };
        if !host_ok(host) {
            continue;
        }
        let lower = host.to_ascii_lowercase();
        let value = match port {
            Some(p) => format!("{lower}:{p}"),
            None => lower.clone(),
        };
        if lower.ends_with(".e4mc.link") {
            return Some(value);
        }
        // A plain host needs a dot (or a port) so ordinary words don't count.
        if fallback.is_none() && (lower.contains('.') || port.is_some()) {
            fallback = Some(value);
        }
    }
    fallback
}

#[derive(Deserialize)]
struct NameLookup {
    id: String,
    name: String,
}

#[derive(Deserialize)]
struct SessionProfile {
    #[serde(default)]
    properties: Vec<Property>,
}

#[derive(Deserialize)]
struct Property {
    name: String,
    value: String,
}

#[derive(Deserialize)]
struct Textures {
    textures: TextureSet,
}

#[derive(Deserialize)]
struct TextureSet {
    #[serde(rename = "SKIN")]
    skin: Option<Texture>,
}

#[derive(Deserialize)]
struct Texture {
    url: String,
}

/// The skin URL in a session profile's `textures` property (always served over https).
fn skin_url(profile: &SessionProfile) -> Option<String> {
    let value = &profile
        .properties
        .iter()
        .find(|p| p.name == "textures")?
        .value;
    let json = base64::engine::general_purpose::STANDARD
        .decode(value)
        .ok()?;
    let t: Textures = serde_json::from_slice(&json).ok()?;
    let url = t.textures.skin?.url;
    Some(match url.strip_prefix("http://") {
        Some(rest) => format!("https://{rest}"),
        None => url,
    })
}

/// A player's profile by name; `None` when Mojang has no such player.
pub async fn lookup(http: &Http, name: &str) -> Result<Option<PlayerProfile>> {
    if !valid_name(name) {
        return Err(Error::Invalid(format!("{name}: not a Minecraft name")));
    }
    let url = format!("https://api.mojang.com/users/profiles/minecraft/{name}");
    let resp = http.send(|c| c.get(&url)).await?;
    if matches!(resp.status().as_u16(), 204 | 404) {
        return Ok(None);
    }
    let found: NameLookup = crate::net::client::json(resp).await?;
    if found.id.len() != 32 || !found.id.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(Error::Invalid("unexpected profile id".into()));
    }
    let url = format!(
        "https://sessionserver.mojang.com/session/minecraft/profile/{}",
        found.id
    );
    // The face is a nicety: a failed skin lookup still adds the friend.
    let skin = match http.get_json::<SessionProfile>(&url).await {
        Ok(p) => skin_url(&p),
        Err(e) => {
            tracing::debug!(error = %e, "couldn't read a friend's skin");
            None
        }
    };
    Ok(Some(PlayerProfile {
        name: found.name,
        uuid: found.id,
        skin_url: skin,
    }))
}

/// Settings coming from the UI (or an imported file) keep only sane friends.
pub fn validate(friends: &[Friend]) -> Result<()> {
    if friends.len() > MAX_FRIENDS {
        return Err(Error::Invalid(format!(
            "at most {MAX_FRIENDS} friends are kept"
        )));
    }
    for f in friends {
        if !valid_name(&f.name) {
            return Err(Error::Invalid(format!("{}: not a Minecraft name", f.name)));
        }
        if let Some(a) = &f.address
            && parse_address(a).as_deref() != Some(a.as_str())
        {
            return Err(Error::Invalid(format!("{a}: not a server address")));
        }
        if let Some(u) = &f.skin_url
            && !u.starts_with("https://textures.minecraft.net/")
        {
            return Err(Error::Invalid("skins come only from Mojang".into()));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_address_in_a_pasted_message() {
        assert_eq!(
            parse_address("Join me: Cool-Fox-12.eu.e4mc.link!").as_deref(),
            Some("cool-fox-12.eu.e4mc.link")
        );
        assert_eq!(
            parse_address("\"192.168.1.20:51234\"").as_deref(),
            Some("192.168.1.20:51234")
        );
        assert_eq!(
            parse_address("try play.example.org or abc.e4mc.link").as_deref(),
            Some("abc.e4mc.link")
        );
        assert_eq!(parse_address("hello there"), None);
        assert_eq!(parse_address("a/b;rm -rf"), None);
    }

    #[test]
    fn names_and_skins_are_checked() {
        assert!(valid_name("Notch") && valid_name("a_b_9") && !valid_name("x y"));
        assert!(!valid_name("seventeen_chars__"));
        let ok = Friend {
            name: "Steve".into(),
            uuid: None,
            skin_url: Some("https://textures.minecraft.net/texture/abc".into()),
            address: Some("abc.e4mc.link".into()),
            instance_id: None,
            added: 0,
        };
        assert!(validate(std::slice::from_ref(&ok)).is_ok());
        let bad = Friend {
            skin_url: Some("file:///etc/passwd".into()),
            ..ok.clone()
        };
        assert!(validate(&[bad]).is_err());
    }

    #[test]
    fn reads_the_skin_from_the_textures_property() {
        let tex = r#"{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/ff"}}}"#;
        let p = SessionProfile {
            properties: vec![Property {
                name: "textures".into(),
                value: base64::engine::general_purpose::STANDARD.encode(tex),
            }],
        };
        assert_eq!(
            skin_url(&p).as_deref(),
            Some("https://textures.minecraft.net/texture/ff")
        );
    }
}
