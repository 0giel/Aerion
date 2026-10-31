//! Error type shared by the whole engine.
//!
//! Every error maps to an [`ErrorInfo`] with a stable `code` the UI translates
//! (`errors.<code>` in the i18n files) plus parameters, so messages stay
//! human-readable in every language and always carry a hint on how to fix things.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::Serialize;

pub type Result<T, E = Error> = std::result::Result<T, E>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("network error: {0}")]
    Network(#[from] reqwest::Error),

    #[error("HTTP {status} from {url}")]
    Http {
        status: u16,
        url: String,
        body: Option<String>,
    },

    #[error("I/O error at {path}: {source}")]
    Io {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },

    #[error("invalid data from {context}: {message}")]
    Parse { context: String, message: String },

    #[error("checksum mismatch for {path}: expected {expected}, got {actual}")]
    HashMismatch {
        path: PathBuf,
        expected: String,
        actual: String,
    },

    #[error("operation cancelled")]
    Cancelled,

    #[error("unsafe path in archive or pack: {0}")]
    UnsafePath(String),

    #[error("archive error: {0}")]
    Zip(#[from] zip::result::ZipError),

    #[error("Minecraft version {0} not found")]
    VersionNotFound(String),

    #[error("no suitable Java {0} runtime available")]
    JavaNotFound(u32),

    #[error("Java at {path} is not usable: {reason}")]
    JavaInvalid { path: PathBuf, reason: String },

    #[error("invalid JVM argument(s): {0}")]
    InvalidJvmArgs(String),

    #[error(transparent)]
    Auth(#[from] AuthError),

    #[error("secure storage (keyring) error: {0}")]
    Keyring(String),

    #[error("instance {0} not found")]
    InstanceNotFound(String),

    #[error("invalid name: {0}")]
    InvalidName(String),

    #[error("CurseForge API key is not configured")]
    CurseForgeKeyMissing,

    #[error("the author of {project} disabled third-party downloads of {file_name}")]
    DistributionDisabled {
        project: String,
        file_name: String,
        url: String,
    },

    #[error("no compatible file of {project} for {game_version} / {loader}")]
    NoCompatibleFile {
        project: String,
        game_version: String,
        loader: String,
    },

    #[error("{loader} {version} is not available for Minecraft {game_version}")]
    LoaderUnavailable {
        loader: String,
        version: String,
        game_version: String,
    },

    #[error("loader installer failed: {0}")]
    LoaderInstall(String),

    #[error("game is already running")]
    AlreadyRunning,

    #[error("unsupported image format")]
    UnsupportedImage,

    #[error("file is larger than {max_kb} KB")]
    FileTooLarge { max_kb: u64 },

    #[error("a skin must be 64×64 or 64×32 pixels, this one is {width}×{height}")]
    SkinSize { width: u32, height: u32 },

    #[error("changing the skin needs a Microsoft account")]
    SkinNeedsMicrosoft,

    #[error("the folder {0} is not empty")]
    FolderNotEmpty(PathBuf),

    #[error("{0}")]
    Invalid(String),
}

/// Authentication failures, each with a dedicated user-facing explanation.
#[derive(Debug, thiserror::Error)]
pub enum AuthError {
    #[error("no Azure client ID configured")]
    ClientIdMissing,
    #[error("the Azure app is not approved for Minecraft (Invalid app registration)")]
    InvalidAppRegistration,
    #[error("this Microsoft account has no Xbox profile")]
    NoXboxAccount,
    #[error("this account is banned from Xbox Live")]
    XboxBanned,
    #[error("Xbox Live is not available in this account's country")]
    CountryUnavailable,
    #[error("the account needs adult verification on xbox.com")]
    AdultVerification,
    #[error("child account must be added to a Microsoft family")]
    ChildAccount,
    #[error("Xbox Live refused the login (XErr {0})")]
    Xsts(u64),
    #[error("this account does not own Minecraft: Java Edition")]
    NoLicense,
    #[error("the account owns the game but has no Minecraft profile yet")]
    NoProfile,
    #[error("the Minecraft account is suspended")]
    Suspended,
    #[error("Microsoft login failed: {error}")]
    OAuth {
        error: String,
        description: Option<String>,
    },
    #[error("sign-in was declined")]
    Declined,
    #[error("the sign-in code expired")]
    Expired,
    #[error("the saved session expired; sign in again")]
    SessionExpired,
    #[error("offline accounts require a Microsoft account that owns the game")]
    OfflineNotAllowed,
    #[error("no account selected; sign in first")]
    NoAccount,
}

/// Serializable error for the UI.
#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct ErrorInfo {
    /// Stable machine code, e.g. `auth.noLicense`; the UI looks up `errors.<code>`.
    pub code: String,
    /// English fallback message (also what goes into logs).
    pub message: String,
    /// Named values the translated message may interpolate.
    pub params: BTreeMap<String, String>,
}

impl Error {
    pub fn io(path: impl AsRef<Path>, source: std::io::Error) -> Self {
        Error::Io {
            path: path.as_ref().to_path_buf(),
            source,
        }
    }

    pub fn parse(context: impl Into<String>, message: impl ToString) -> Self {
        Error::Parse {
            context: context.into(),
            message: message.to_string(),
        }
    }

    pub fn info(&self) -> ErrorInfo {
        let mut params = BTreeMap::new();
        let mut put = |k: &str, v: String| {
            params.insert(k.to_owned(), v);
        };
        let code = match self {
            Error::Network(e) => {
                if e.is_timeout() {
                    "net.timeout"
                } else if e.is_connect() {
                    "net.offline"
                } else {
                    "net.generic"
                }
            }
            Error::Http { status, url, .. } => {
                put("status", status.to_string());
                put("url", url.clone());
                match status {
                    429 => "net.rateLimited",
                    401 | 403 => "net.forbidden",
                    404 => "net.notFound",
                    500..=599 => "net.serverError",
                    _ => "net.http",
                }
            }
            Error::Io { path, source } => {
                put("path", path.display().to_string());
                match source.kind() {
                    std::io::ErrorKind::PermissionDenied => "io.permission",
                    std::io::ErrorKind::StorageFull => "io.diskFull",
                    std::io::ErrorKind::NotFound => "io.notFound",
                    _ => "io.generic",
                }
            }
            Error::Parse { context, .. } => {
                put("context", context.clone());
                "parse"
            }
            Error::HashMismatch { path, .. } => {
                put("path", path.display().to_string());
                "download.hashMismatch"
            }
            Error::Cancelled => "cancelled",
            Error::UnsafePath(p) => {
                put("path", p.clone());
                "security.unsafePath"
            }
            Error::Zip(_) => "archive.invalid",
            Error::VersionNotFound(v) => {
                put("version", v.clone());
                "version.notFound"
            }
            Error::JavaNotFound(major) => {
                put("major", major.to_string());
                "java.notFound"
            }
            Error::JavaInvalid { path, .. } => {
                put("path", path.display().to_string());
                "java.invalid"
            }
            Error::InvalidJvmArgs(args) => {
                put("args", args.clone());
                "java.invalidArgs"
            }
            Error::Auth(a) => a.code(),
            Error::Keyring(_) => "keyring.unavailable",
            Error::InstanceNotFound(id) => {
                put("id", id.clone());
                "instance.notFound"
            }
            Error::InvalidName(reason) => {
                put("reason", reason.clone());
                "instance.invalidName"
            }
            Error::CurseForgeKeyMissing => "curseforge.noKey",
            Error::DistributionDisabled {
                project,
                file_name,
                url,
            } => {
                put("project", project.clone());
                put("file", file_name.clone());
                put("url", url.clone());
                "curseforge.distributionDisabled"
            }
            Error::NoCompatibleFile {
                project,
                game_version,
                loader,
            } => {
                put("project", project.clone());
                put("gameVersion", game_version.clone());
                put("loader", loader.clone());
                "mods.noCompatibleFile"
            }
            Error::LoaderUnavailable {
                loader,
                version,
                game_version,
            } => {
                put("loader", loader.clone());
                put("version", version.clone());
                put("gameVersion", game_version.clone());
                "loader.unavailable"
            }
            Error::LoaderInstall(_) => "loader.installFailed",
            Error::AlreadyRunning => "launch.alreadyRunning",
            Error::UnsupportedImage => "image.unsupported",
            Error::FileTooLarge { max_kb } => {
                put("max", max_kb.to_string());
                "file.tooLarge"
            }
            Error::SkinSize { width, height } => {
                put("width", width.to_string());
                put("height", height.to_string());
                "skin.size"
            }
            Error::SkinNeedsMicrosoft => "skin.needsMicrosoft",
            Error::FolderNotEmpty(path) => {
                put("path", path.display().to_string());
                "instance.folderNotEmpty"
            }
            Error::Invalid(_) => "generic",
        };
        ErrorInfo {
            code: code.to_owned(),
            message: self.to_string(),
            params,
        }
    }
}

impl AuthError {
    fn code(&self) -> &'static str {
        match self {
            AuthError::ClientIdMissing => "auth.clientIdMissing",
            AuthError::InvalidAppRegistration => "auth.invalidAppRegistration",
            AuthError::NoXboxAccount => "auth.noXboxAccount",
            AuthError::XboxBanned => "auth.xboxBanned",
            AuthError::CountryUnavailable => "auth.countryUnavailable",
            AuthError::AdultVerification => "auth.adultVerification",
            AuthError::ChildAccount => "auth.childAccount",
            AuthError::Xsts(_) => "auth.xsts",
            AuthError::NoLicense => "auth.noLicense",
            AuthError::NoProfile => "auth.noProfile",
            AuthError::Suspended => "auth.suspended",
            AuthError::OAuth { .. } => "auth.oauth",
            AuthError::Declined => "auth.declined",
            AuthError::Expired => "auth.expired",
            AuthError::SessionExpired => "auth.sessionExpired",
            AuthError::OfflineNotAllowed => "auth.offlineNotAllowed",
            AuthError::NoAccount => "auth.noAccount",
        }
    }
}

/// Attach a path to `std::io::Result`s.
pub trait IoContext<T> {
    fn at(self, path: impl AsRef<Path>) -> Result<T>;
}

impl<T> IoContext<T> for std::io::Result<T> {
    fn at(self, path: impl AsRef<Path>) -> Result<T> {
        self.map_err(|e| Error::io(path, e))
    }
}

impl From<Error> for ErrorInfo {
    fn from(e: Error) -> Self {
        e.info()
    }
}

impl ErrorInfo {
    pub fn msg(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.to_owned(),
            message: message.into(),
            params: BTreeMap::new(),
        }
    }
}
