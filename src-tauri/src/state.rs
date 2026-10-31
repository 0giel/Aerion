//! Application state shared by all commands.

use std::sync::{Arc, Mutex};

use launcher_core::accounts::{Accounts, KeyringStore, SecretStore};
use launcher_core::auth::Endpoints;
use launcher_core::auth::msa::DeviceCode;
use launcher_core::game::textures::Textures;
use launcher_core::instances::InstanceManager;
use launcher_core::mods::curseforge::CurseForge;
use launcher_core::mods::modrinth::Modrinth;
use launcher_core::paths::Paths;
use launcher_core::settings::Settings;
use launcher_core::skins::SkinLibrary;
use launcher_core::{Engine, Result};
use tokio::sync::RwLock;
use tokio_util::sync::CancellationToken;

use crate::games::GameRegistry;
use crate::tasks::TaskRegistry;

pub const KEYRING_SERVICE: &str = "aerion";
pub const CF_KEY_ENTRY: &str = "curseforge-api-key";

pub struct AppState {
    pub paths: Paths,
    pub settings: RwLock<Settings>,
    pub engine: RwLock<Engine>,
    pub accounts: Accounts,
    pub instances: InstanceManager,
    pub skins: SkinLibrary,
    pub textures: Textures,
    pub tasks: TaskRegistry,
    pub games: GameRegistry,
    pub endpoints: Endpoints,
    pub keyring: Arc<KeyringStore>,
    pub cf_key: RwLock<Option<String>>,
    pub cf_key_from_env: bool,
    pub login: Mutex<Option<CancellationToken>>,
    pub device_code: tokio::sync::Mutex<Option<DeviceCode>>,
}

impl AppState {
    pub async fn init() -> Result<Self> {
        let base = Paths::detect()?;
        let settings = Settings::load(&base).await?;
        let paths = base.with_data_dir(settings.data_dir.clone());
        for dir in [
            paths.root.clone(),
            paths.data.clone(),
            paths.instances_dir(),
            paths.themes_dir(),
            paths.logs_dir(),
        ] {
            let _ = tokio::fs::create_dir_all(&dir).await;
        }
        let engine = Engine::new(paths.clone(), &settings)?;
        let keyring = Arc::new(KeyringStore::new(KEYRING_SERVICE));
        let env_key = std::env::var("CURSEFORGE_API_KEY")
            .ok()
            .filter(|k| !k.trim().is_empty());
        let cf_key_from_env = env_key.is_some();
        let cf_key = match env_key {
            Some(k) => Some(k),
            None => {
                let k = keyring.clone();
                tokio::task::spawn_blocking(move || k.get(CF_KEY_ENTRY).ok().flatten())
                    .await
                    .ok()
                    .flatten()
            }
        };
        Ok(Self {
            accounts: Accounts::new(paths.accounts_file(), keyring.clone()),
            instances: InstanceManager::new(paths.instances_dir()),
            skins: SkinLibrary::new(paths.skins_dir()),
            textures: Textures::default(),
            settings: RwLock::new(settings),
            engine: RwLock::new(engine),
            tasks: TaskRegistry::default(),
            games: GameRegistry::default(),
            endpoints: Endpoints::default(),
            keyring,
            cf_key: RwLock::new(cf_key),
            cf_key_from_env,
            login: Mutex::new(None),
            device_code: tokio::sync::Mutex::new(None),
            paths,
        })
    }

    pub async fn engine(&self) -> Engine {
        self.engine.read().await.clone()
    }

    pub async fn modrinth(&self) -> Modrinth {
        let e = self.engine().await;
        Modrinth::new(e.http.clone(), e.cache.clone())
    }

    pub async fn curseforge(&self) -> CurseForge {
        let e = self.engine().await;
        CurseForge::new(e.http.clone(), self.cf_key.read().await.clone())
    }
}
