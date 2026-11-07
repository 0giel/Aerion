//! Accounts: non-secret metadata in `accounts.json`, tokens only in the OS keyring.

use std::path::PathBuf;
use std::sync::Arc;

use md5::Digest;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::auth::{AuthInfo, Endpoints, McSession, Profile, minecraft_session, msa};
use crate::error::{AuthError, Error, Result};
use crate::net::client::Http;
use crate::util::fs::{read_json, write_json};
use crate::util::now_secs;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub enum AccountKind {
    Microsoft,
    Offline,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Account {
    /// Stable id: the profile UUID (no dashes).
    pub id: String,
    pub kind: AccountKind,
    pub username: String,
    pub skin_url: Option<String>,
    /// `classic` or `slim`.
    pub skin_variant: Option<String>,
    pub cape_url: Option<String>,
    pub added_at: i64,
    pub last_used: Option<i64>,
    /// Microsoft accounts: ownership was verified at login.
    pub owns_game: bool,
    /// The refresh token was rejected; the user must sign in again.
    pub needs_relogin: bool,
}

impl Account {
    /// Take the name, skin and cape Minecraft services report for this player.
    fn mirror(&mut self, profile: &Profile) {
        let skin = profile.active_skin();
        self.username = profile.name.clone();
        self.skin_url = skin.map(|s| s.url.clone());
        self.skin_variant = skin.map(|s| s.variant.to_ascii_lowercase());
        self.cape_url = profile.active_cape().map(|c| c.url.clone());
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct AccountsState {
    pub accounts: Vec<Account>,
    pub selected: Option<String>,
}

/// Storage for secrets. The keyring implementation is used in the app; tests use memory.
pub trait SecretStore: Send + Sync {
    fn get(&self, key: &str) -> Result<Option<String>>;
    fn set(&self, key: &str, value: &str) -> Result<()>;
    fn delete(&self, key: &str) -> Result<()>;
}

/// OS keyring (Windows Credential Manager, macOS Keychain, Secret Service). Long values are
/// split into chunks because Credential Manager caps a credential at 2560 bytes.
pub struct KeyringStore {
    service: String,
}

const CHUNK: usize = 1000;

impl KeyringStore {
    pub fn new(service: impl Into<String>) -> Self {
        Self {
            service: service.into(),
        }
    }

    fn entry(&self, key: &str) -> Result<keyring::Entry> {
        keyring::Entry::new(&self.service, key).map_err(|e| Error::Keyring(e.to_string()))
    }

    fn raw_get(&self, key: &str) -> Result<Option<String>> {
        match self.entry(key)?.get_password() {
            Ok(v) => Ok(Some(v)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(Error::Keyring(e.to_string())),
        }
    }

    fn raw_delete(&self, key: &str) -> Result<()> {
        match self.entry(key)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(Error::Keyring(e.to_string())),
        }
    }

    pub fn available() -> Result<()> {
        keyring::Entry::store_status()
            .as_ref()
            .map(|_| ())
            .map_err(|e| Error::Keyring(e.to_string()))
    }
}

impl SecretStore for KeyringStore {
    fn get(&self, key: &str) -> Result<Option<String>> {
        let Some(head) = self.raw_get(key)? else {
            return Ok(None);
        };
        let Some(n) = head
            .strip_prefix("chunks:")
            .and_then(|n| n.parse::<usize>().ok())
        else {
            return Ok(Some(head));
        };
        let mut out = String::new();
        for i in 0..n {
            match self.raw_get(&format!("{key}#{i}"))? {
                Some(part) => out.push_str(&part),
                None => return Ok(None),
            }
        }
        Ok(Some(out))
    }

    fn set(&self, key: &str, value: &str) -> Result<()> {
        self.delete(key)?;
        if value.len() <= CHUNK && !value.starts_with("chunks:") {
            return self
                .entry(key)?
                .set_password(value)
                .map_err(|e| Error::Keyring(e.to_string()));
        }
        let parts: Vec<String> = value
            .chars()
            .collect::<Vec<_>>()
            .chunks(CHUNK)
            .map(|c| c.iter().collect())
            .collect();
        for (i, part) in parts.iter().enumerate() {
            self.entry(&format!("{key}#{i}"))?
                .set_password(part)
                .map_err(|e| Error::Keyring(e.to_string()))?;
        }
        self.entry(key)?
            .set_password(&format!("chunks:{}", parts.len()))
            .map_err(|e| Error::Keyring(e.to_string()))
    }

    fn delete(&self, key: &str) -> Result<()> {
        if let Some(head) = self.raw_get(key)?
            && let Some(n) = head
                .strip_prefix("chunks:")
                .and_then(|n| n.parse::<usize>().ok())
        {
            for i in 0..n {
                self.raw_delete(&format!("{key}#{i}"))?;
            }
        }
        self.raw_delete(key)
    }
}

#[derive(Default)]
pub struct MemoryStore(std::sync::Mutex<std::collections::HashMap<String, String>>);

impl SecretStore for MemoryStore {
    fn get(&self, key: &str) -> Result<Option<String>> {
        Ok(self
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(key)
            .cloned())
    }
    fn set(&self, key: &str, value: &str) -> Result<()> {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(key.to_owned(), value.to_owned());
        Ok(())
    }
    fn delete(&self, key: &str) -> Result<()> {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).remove(key);
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
struct CachedToken {
    token: String,
    expires_at: i64,
    xuid: Option<String>,
}

/// `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` — what servers in offline mode expect.
fn offline_uuid(name: &str) -> String {
    let digest = md5::Md5::digest(format!("OfflinePlayer:{name}").as_bytes());
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest);
    uuid::Builder::from_md5_bytes(bytes)
        .into_uuid()
        .simple()
        .to_string()
}

fn validate_offline_name(name: &str) -> Result<()> {
    let ok = (3..=16).contains(&name.len())
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_');
    if ok {
        Ok(())
    } else {
        Err(Error::InvalidName(
            "3–16 characters: letters, digits, _".into(),
        ))
    }
}

/// Debug builds may skip the ownership requirement for automated tests.
pub fn dev_offline_override() -> bool {
    cfg!(debug_assertions) && std::env::var("LAUNCHER_DEV_OFFLINE").is_ok_and(|v| v == "1")
}

/// Keyring entry of a Microsoft account's refresh token.
fn refresh_key(id: &str) -> String {
    format!("msa-refresh:{id}")
}

/// Keyring entry of a Microsoft account's cached Minecraft token ([`CachedToken`]).
fn token_key(id: &str) -> String {
    format!("mc-token:{id}")
}

pub struct Accounts {
    file: PathBuf,
    secrets: Arc<dyn SecretStore>,
    lock: Mutex<()>,
}

impl Accounts {
    pub fn new(file: PathBuf, secrets: Arc<dyn SecretStore>) -> Self {
        Self {
            file,
            secrets,
            lock: Mutex::new(()),
        }
    }

    pub async fn state(&self) -> AccountsState {
        read_json(&self.file)
            .await
            .ok()
            .flatten()
            .unwrap_or_default()
    }

    pub async fn get(&self, id: &str) -> Option<Account> {
        self.state().await.accounts.into_iter().find(|a| a.id == id)
    }

    async fn save(&self, s: &AccountsState) -> Result<()> {
        write_json(&self.file, s).await
    }

    pub async fn offline_allowed(&self) -> bool {
        dev_offline_override()
            || self
                .state()
                .await
                .accounts
                .iter()
                .any(|a| a.kind == AccountKind::Microsoft && a.owns_game)
    }

    /// Store a fresh Microsoft session (new account or re-login).
    pub async fn add_microsoft(&self, session: &McSession) -> Result<Account> {
        let _g = self.lock.lock().await;
        let id = session.profile.id.replace('-', "");
        self.secrets
            .set(&refresh_key(&id), &session.ms_refresh_token)?;
        self.store_token(&id, session)?;
        let mut s = self.state().await;
        let mut account = Account {
            id: id.clone(),
            kind: AccountKind::Microsoft,
            username: String::new(),
            skin_url: None,
            skin_variant: None,
            cape_url: None,
            added_at: s
                .accounts
                .iter()
                .find(|a| a.id == id)
                .map_or_else(now_secs, |a| a.added_at),
            last_used: Some(now_secs()),
            owns_game: true,
            needs_relogin: false,
        };
        account.mirror(&session.profile);
        s.accounts.retain(|a| a.id != id);
        s.accounts.push(account.clone());
        s.selected = Some(id);
        self.save(&s).await?;
        Ok(account)
    }

    fn store_token(&self, id: &str, session: &McSession) -> Result<()> {
        let cached = CachedToken {
            token: session.access_token.clone(),
            expires_at: session.expires_at,
            xuid: session.xuid.clone(),
        };
        let json = serde_json::to_string(&cached).map_err(|e| Error::parse("token", e))?;
        self.secrets.set(&token_key(id), &json)
    }

    pub async fn add_offline(&self, name: &str) -> Result<Account> {
        validate_offline_name(name)?;
        if !self.offline_allowed().await {
            return Err(AuthError::OfflineNotAllowed.into());
        }
        let _g = self.lock.lock().await;
        let id = offline_uuid(name);
        let mut s = self.state().await;
        s.accounts.retain(|a| a.id != id);
        let account = Account {
            id: id.clone(),
            kind: AccountKind::Offline,
            username: name.to_owned(),
            skin_url: None,
            skin_variant: None,
            cape_url: None,
            added_at: now_secs(),
            last_used: None,
            owns_game: false,
            needs_relogin: false,
        };
        s.accounts.push(account.clone());
        if s.selected.is_none() {
            s.selected = Some(id);
        }
        self.save(&s).await?;
        Ok(account)
    }

    pub async fn remove(&self, id: &str) -> Result<()> {
        let _g = self.lock.lock().await;
        let mut s = self.state().await;
        s.accounts.retain(|a| a.id != id);
        if s.selected.as_deref() == Some(id) {
            s.selected = s.accounts.first().map(|a| a.id.clone());
        }
        self.save(&s).await?;
        let _ = self.secrets.delete(&refresh_key(id));
        let _ = self.secrets.delete(&token_key(id));
        Ok(())
    }

    pub async fn select(&self, id: &str) -> Result<()> {
        let _g = self.lock.lock().await;
        let mut s = self.state().await;
        if !s.accounts.iter().any(|a| a.id == id) {
            return Err(Error::Invalid(format!("unknown account {id}")));
        }
        s.selected = Some(id.to_owned());
        self.save(&s).await
    }

    async fn update<F: FnOnce(&mut Account)>(&self, id: &str, f: F) -> Result<()> {
        let mut s = self.state().await;
        if let Some(a) = s.accounts.iter_mut().find(|a| a.id == id) {
            f(a);
        }
        self.save(&s).await
    }

    /// A valid Minecraft services token of a Microsoft account (refreshed when needed).
    pub async fn microsoft_token(
        &self,
        id: &str,
        http: &Http,
        ep: &Endpoints,
        client_id: Option<&str>,
    ) -> Result<String> {
        let kind = self.get(id).await.ok_or(AuthError::NoAccount)?.kind;
        if kind != AccountKind::Microsoft {
            return Err(Error::SkinNeedsMicrosoft);
        }
        Ok(self
            .launch_auth(id, http, ep, client_id)
            .await?
            .1
            .access_token)
    }

    /// Mirror a profile returned by Minecraft services (after a skin or cape change).
    pub async fn apply_profile(&self, id: &str, profile: &Profile) -> Result<()> {
        let _g = self.lock.lock().await;
        self.update(id, |a| a.mirror(profile)).await
    }

    /// Credentials for launching: cached token if still valid for 10 minutes, otherwise a
    /// silent refresh through the whole chain (rotating the stored refresh token).
    pub async fn launch_auth(
        &self,
        id: &str,
        http: &Http,
        ep: &Endpoints,
        client_id: Option<&str>,
    ) -> Result<(Account, AuthInfo)> {
        let _g = self.lock.lock().await;
        let account = self
            .get(id)
            .await
            .ok_or_else(|| Error::Invalid(format!("unknown account {id}")))?;
        match account.kind {
            AccountKind::Offline => {
                drop(_g);
                if !self.offline_allowed().await {
                    return Err(AuthError::OfflineNotAllowed.into());
                }
                let info = AuthInfo {
                    name: account.username.clone(),
                    uuid: account.id.clone(),
                    access_token: "0".into(),
                    xuid: "0".into(),
                    user_type: "legacy".into(),
                };
                self.update(id, |a| a.last_used = Some(now_secs())).await?;
                Ok((account, info))
            }
            AccountKind::Microsoft => {
                let cached: Option<CachedToken> = self
                    .secrets
                    .get(&token_key(id))?
                    .and_then(|j| serde_json::from_str(&j).ok());
                let (token, xuid) = match cached {
                    Some(c) if c.expires_at - now_secs() > 600 => (c.token, c.xuid),
                    _ => {
                        let client_id = client_id.ok_or(AuthError::ClientIdMissing)?;
                        let refresh = self
                            .secrets
                            .get(&refresh_key(id))?
                            .ok_or(AuthError::SessionExpired)?;
                        let ms = match msa::refresh(http, ep, client_id, &refresh).await {
                            Ok(t) => t,
                            Err(e @ Error::Auth(AuthError::SessionExpired)) => {
                                self.update(id, |a| a.needs_relogin = true).await?;
                                return Err(e);
                            }
                            Err(e) => return Err(e),
                        };
                        self.secrets.set(&refresh_key(id), &ms.refresh_token)?;
                        let session = minecraft_session(http, ep, &ms).await?;
                        self.store_token(id, &session)?;
                        self.update(id, |a| {
                            a.mirror(&session.profile);
                            a.needs_relogin = false;
                        })
                        .await?;
                        (session.access_token, session.xuid)
                    }
                };
                self.update(id, |a| a.last_used = Some(now_secs())).await?;
                let account = self.get(id).await.unwrap_or(account);
                let info = AuthInfo {
                    name: account.username.clone(),
                    uuid: account.id.clone(),
                    access_token: token,
                    xuid: xuid.unwrap_or_else(|| "0".into()),
                    user_type: "msa".into(),
                };
                Ok((account, info))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::Profile;

    #[test]
    fn offline_uuid_matches_java() {
        // Java: UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(UTF_8))
        assert_eq!(offline_uuid("Notch"), "b50ad385829d3141a2167e7d7539ba7f");
        assert!(validate_offline_name("Steve_1").is_ok());
        assert!(validate_offline_name("ab").is_err());
        assert!(validate_offline_name("Łukasz").is_err());
    }

    #[tokio::test]
    async fn offline_requires_licensed_microsoft_account() {
        let dir = tempfile::tempdir().unwrap();
        let accounts = Accounts::new(
            dir.path().join("accounts.json"),
            Arc::new(MemoryStore::default()),
        );
        if !dev_offline_override() {
            assert!(matches!(
                accounts.add_offline("Steve").await,
                Err(Error::Auth(AuthError::OfflineNotAllowed))
            ));
        }
        let session = McSession {
            access_token: "tok".into(),
            expires_at: now_secs() + 3600,
            profile: Profile {
                id: "069a79f444e94726a5befca90e38aaf5".into(),
                name: "Notch".into(),
                skins: vec![],
                capes: vec![],
            },
            xuid: Some("2535".into()),
            ms_refresh_token: "refresh".into(),
        };
        accounts.add_microsoft(&session).await.unwrap();
        let offline = accounts.add_offline("Steve").await.unwrap();
        assert_eq!(offline.kind, AccountKind::Offline);

        let http = Http::new(&crate::net::client::NetConfig::default()).unwrap();
        let (_, info) = accounts
            .launch_auth(
                "069a79f444e94726a5befca90e38aaf5",
                &http,
                &Endpoints::default(),
                None,
            )
            .await
            .unwrap();
        assert_eq!(
            (
                info.access_token.as_str(),
                info.xuid.as_str(),
                info.user_type.as_str()
            ),
            ("tok", "2535", "msa")
        );
        let (_, info) = accounts
            .launch_auth(&offline.id, &http, &Endpoints::default(), None)
            .await
            .unwrap();
        assert_eq!(info.user_type, "legacy");

        accounts
            .remove("069a79f444e94726a5befca90e38aaf5")
            .await
            .unwrap();
        let state = accounts.state().await;
        assert_eq!(state.accounts.len(), 1);
        assert_eq!(state.selected.as_deref(), Some(offline.id.as_str()));
    }
}
