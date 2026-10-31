//! Skins: a local library of skin files and the Minecraft services calls that apply them.
//!
//! ```text
//! skins/skins.json     names and model variants (this module)
//! skins/<id>.png       the skin images
//! ```

use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::sync::Mutex;

use crate::auth::{Endpoints, Profile};
use crate::error::{Error, IoContext, Result};
use crate::net::client::{Http, json};
use crate::util::fs::{read_json, write_json};
use crate::util::image::png_size;
use crate::util::names::validate_display_name;
use crate::util::now_secs;

/// Larger files are certainly not skins (a 64×64 PNG is a few KB).
const MAX_SKIN_BYTES: usize = 256 * 1024;
const INDEX: &str = "skins.json";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum SkinVariant {
    /// 4-pixel arms ("Steve").
    Classic,
    /// 3-pixel arms ("Alex").
    Slim,
}

impl SkinVariant {
    fn api(self) -> &'static str {
        match self {
            Self::Classic => "classic",
            Self::Slim => "slim",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct SavedSkin {
    pub id: String,
    pub name: String,
    pub variant: SkinVariant,
    pub added_at: i64,
}

/// A PNG of 64×64 (modern) or 64×32 (legacy) pixels.
pub fn validate_skin(bytes: &[u8]) -> Result<()> {
    if bytes.len() > MAX_SKIN_BYTES {
        return Err(Error::FileTooLarge {
            max_kb: (MAX_SKIN_BYTES / 1024) as u64,
        });
    }
    match png_size(bytes) {
        None => Err(Error::UnsupportedImage),
        Some((64, 64 | 32)) => Ok(()),
        Some((width, height)) => Err(Error::SkinSize { width, height }),
    }
}

fn valid_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit())
}

pub struct SkinLibrary {
    dir: PathBuf,
    lock: Mutex<()>,
}

impl SkinLibrary {
    pub fn new(dir: PathBuf) -> Self {
        Self {
            dir,
            lock: Mutex::new(()),
        }
    }

    /// Newest first; entries whose image is missing are skipped.
    pub async fn list(&self) -> Vec<SavedSkin> {
        let mut skins: Vec<SavedSkin> = read_json(self.dir.join(INDEX))
            .await
            .ok()
            .flatten()
            .unwrap_or_default();
        skins.retain(|s| valid_id(&s.id) && self.dir.join(format!("{}.png", s.id)).is_file());
        skins.sort_by_key(|s| std::cmp::Reverse(s.added_at));
        skins
    }

    async fn save(&self, skins: &[SavedSkin]) -> Result<()> {
        write_json(self.dir.join(INDEX), &skins).await
    }

    pub fn file(&self, id: &str) -> Result<PathBuf> {
        if !valid_id(id) {
            return Err(Error::Invalid(format!("invalid skin id {id:?}")));
        }
        Ok(self.dir.join(format!("{id}.png")))
    }

    pub async fn read(&self, id: &str) -> Result<Vec<u8>> {
        let path = self.file(id)?;
        tokio::fs::read(&path).await.at(&path)
    }

    pub async fn add(&self, name: &str, variant: SkinVariant, png: &[u8]) -> Result<SavedSkin> {
        validate_skin(png)?;
        let name = validate_display_name(name)?;
        let _g = self.lock.lock().await;
        tokio::fs::create_dir_all(&self.dir).await.at(&self.dir)?;
        let skin = SavedSkin {
            id: uuid::Uuid::new_v4().simple().to_string(),
            name,
            variant,
            added_at: now_secs(),
        };
        let path = self.file(&skin.id)?;
        tokio::fs::write(&path, png).await.at(&path)?;
        let mut all = self.list().await;
        all.push(skin.clone());
        self.save(&all).await?;
        Ok(skin)
    }

    pub async fn update(&self, id: &str, name: &str, variant: SkinVariant) -> Result<SavedSkin> {
        let name = validate_display_name(name)?;
        let _g = self.lock.lock().await;
        let mut all = self.list().await;
        let skin = all
            .iter_mut()
            .find(|s| s.id == id)
            .ok_or_else(|| Error::Invalid(format!("unknown skin {id}")))?;
        skin.name = name;
        skin.variant = variant;
        let out = skin.clone();
        self.save(&all).await?;
        Ok(out)
    }

    pub async fn delete(&self, id: &str) -> Result<()> {
        let path = self.file(id)?;
        let _g = self.lock.lock().await;
        let mut all = self.list().await;
        all.retain(|s| s.id != id);
        self.save(&all).await?;
        match tokio::fs::remove_file(&path).await {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(Error::io(&path, e)),
            _ => Ok(()),
        }
    }
}

/// Make `png` the account's skin. Returns the updated profile.
pub async fn upload_skin(
    http: &Http,
    ep: &Endpoints,
    token: &str,
    variant: SkinVariant,
    png: &[u8],
) -> Result<Profile> {
    validate_skin(png)?;
    let url = format!("{}/minecraft/profile/skins", ep.minecraft);
    let resp = http
        .send(|c| {
            let file = reqwest::multipart::Part::bytes(png.to_vec())
                .file_name("skin.png")
                .mime_str("image/png")
                .expect("static mime type");
            let form = reqwest::multipart::Form::new()
                .text("variant", variant.api())
                .part("file", file);
            c.post(&url).bearer_auth(token).multipart(form)
        })
        .await?;
    json(resp).await
}

/// Back to the default skin.
pub async fn reset_skin(http: &Http, ep: &Endpoints, token: &str) -> Result<Profile> {
    let url = format!("{}/minecraft/profile/skins/active", ep.minecraft);
    json(http.send(|c| c.delete(&url).bearer_auth(token)).await?).await
}

/// Show one of the account's capes, or hide the cape with `None`.
pub async fn set_cape(
    http: &Http,
    ep: &Endpoints,
    token: &str,
    cape_id: Option<&str>,
) -> Result<Profile> {
    let url = format!("{}/minecraft/profile/capes/active", ep.minecraft);
    let resp = match cape_id {
        Some(id) => {
            http.send(|c| {
                c.put(&url)
                    .bearer_auth(token)
                    .json(&json!({ "capeId": id }))
            })
            .await?
        }
        None => http.send(|c| c.delete(&url).bearer_auth(token)).await?,
    };
    json(resp).await
}

/// The live profile (skins and capes) of a Microsoft account.
pub async fn fetch_profile(http: &Http, ep: &Endpoints, token: &str) -> Result<Profile> {
    crate::auth::profile(http, ep, token)
        .await?
        .ok_or(crate::error::AuthError::NoProfile.into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::net::client::NetConfig;
    use crate::util::image::test_png;
    use wiremock::matchers::{body_json, header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[test]
    fn validates_skin_images() {
        assert!(validate_skin(&test_png(64, 64)).is_ok());
        assert!(validate_skin(&test_png(64, 32)).is_ok());
        assert_eq!(
            validate_skin(&test_png(128, 128)).unwrap_err().info().code,
            "skin.size"
        );
        assert_eq!(
            validate_skin(b"GIF89a....").unwrap_err().info().code,
            "image.unsupported"
        );
    }

    #[tokio::test]
    async fn library_crud() {
        let dir = tempfile::tempdir().unwrap();
        let lib = SkinLibrary::new(dir.path().join("skins"));
        assert!(lib.list().await.is_empty());
        let a = lib
            .add("Rycerz", SkinVariant::Classic, &test_png(64, 64))
            .await
            .unwrap();
        let b = lib
            .add("Alex", SkinVariant::Slim, &test_png(64, 64))
            .await
            .unwrap();
        assert_eq!(lib.list().await.len(), 2);
        assert_eq!(lib.read(&a.id).await.unwrap(), test_png(64, 64));
        let a2 = lib
            .update(&a.id, "Rycerz 2", SkinVariant::Slim)
            .await
            .unwrap();
        assert_eq!(
            (a2.name.as_str(), a2.variant),
            ("Rycerz 2", SkinVariant::Slim)
        );
        lib.delete(&b.id).await.unwrap();
        assert_eq!(lib.list().await.len(), 1);
        assert!(lib.file("../skins").is_err());
        assert!(lib.add("x", SkinVariant::Classic, b"nope").await.is_err());
    }

    fn profile_json() -> serde_json::Value {
        json!({
            "id": "abc", "name": "Gracz",
            "skins": [{"id": "s1", "state": "ACTIVE", "url": "https://textures.minecraft.net/texture/new", "variant": "SLIM"}],
            "capes": [{"id": "c1", "state": "ACTIVE", "url": "https://textures.minecraft.net/texture/cape", "alias": "Migrator"}]
        })
    }

    #[tokio::test]
    async fn skin_and_cape_calls() {
        let server = MockServer::start().await;
        let ep = Endpoints {
            minecraft: server.uri(),
            ..Endpoints::default()
        };
        let http = Http::new(&NetConfig::default()).unwrap();
        Mock::given(method("POST"))
            .and(path("/minecraft/profile/skins"))
            .and(header("authorization", "Bearer tok"))
            // The body holds binary PNG data, so match on bytes.
            .and(|r: &wiremock::Request| {
                let has = |needle: &[u8]| r.body.windows(needle.len()).any(|w| w == needle);
                has(b"name=\"variant\"\r\n\r\nslim") && has(b"filename=\"skin.png\"")
            })
            .respond_with(ResponseTemplate::new(200).set_body_json(profile_json()))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("PUT"))
            .and(path("/minecraft/profile/capes/active"))
            .and(body_json(json!({"capeId": "c1"})))
            .respond_with(ResponseTemplate::new(200).set_body_json(profile_json()))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("DELETE"))
            .and(path("/minecraft/profile/skins/active"))
            .respond_with(ResponseTemplate::new(200).set_body_json(profile_json()))
            .expect(1)
            .mount(&server)
            .await;

        let p = upload_skin(&http, &ep, "tok", SkinVariant::Slim, &test_png(64, 64))
            .await
            .unwrap();
        assert_eq!(p.active_skin().unwrap().variant, "SLIM");
        assert!(
            upload_skin(&http, &ep, "tok", SkinVariant::Slim, &test_png(10, 10))
                .await
                .is_err(),
            "rejected before any request"
        );
        let p = set_cape(&http, &ep, "tok", Some("c1")).await.unwrap();
        assert_eq!(p.active_cape().unwrap().alias.as_deref(), Some("Migrator"));
        reset_skin(&http, &ep, "tok").await.unwrap();
    }
}
