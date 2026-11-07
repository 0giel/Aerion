//! Microsoft → Xbox Live → XSTS → Minecraft services.

pub mod msa;

use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::error::{AuthError, Error, Result};
use crate::net::client::{Http, check};
use crate::util::jwt;
use crate::util::now_secs;

/// Base URLs (overridable for tests).
/// Who the game runs as.
#[derive(Debug, Clone)]
pub struct AuthInfo {
    pub name: String,
    pub uuid: String,
    pub access_token: String,
    pub xuid: String,
    /// `msa` for Microsoft accounts, `legacy` for offline ones (removed from 26.x arguments).
    pub user_type: String,
}

#[derive(Debug, Clone)]
pub struct Endpoints {
    /// `https://login.microsoftonline.com/consumers/oauth2/v2.0`
    pub msa: String,
    pub xbl: String,
    pub xsts: String,
    pub minecraft: String,
}

impl Default for Endpoints {
    fn default() -> Self {
        Self {
            msa: "https://login.microsoftonline.com/consumers/oauth2/v2.0".into(),
            xbl: "https://user.auth.xboxlive.com".into(),
            xsts: "https://xsts.auth.xboxlive.com".into(),
            minecraft: "https://api.minecraftservices.com".into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Skin {
    /// Mojang's texture id (needed to select a cape).
    #[serde(default)]
    pub id: String,
    pub url: String,
    /// Cape name, e.g. `Migrator`.
    #[serde(default)]
    pub alias: Option<String>,
    /// `CLASSIC` or `SLIM`.
    #[serde(default)]
    pub variant: String,
    #[serde(default)]
    pub state: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
pub struct Profile {
    /// UUID without dashes.
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub skins: Vec<Skin>,
    #[serde(default)]
    pub capes: Vec<Skin>,
}

impl Profile {
    pub fn active_skin(&self) -> Option<&Skin> {
        self.skins
            .iter()
            .find(|s| s.state == "ACTIVE")
            .or(self.skins.first())
    }
    pub fn active_cape(&self) -> Option<&Skin> {
        self.capes.iter().find(|s| s.state == "ACTIVE")
    }
}

/// A logged-in Minecraft session.
#[derive(Debug, Clone)]
pub struct McSession {
    pub access_token: String,
    pub expires_at: i64,
    pub profile: Profile,
    pub xuid: Option<String>,
    pub ms_refresh_token: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XboxResponse {
    token: String,
    display_claims: DisplayClaims,
}

#[derive(Deserialize)]
struct DisplayClaims {
    xui: Vec<serde_json::Map<String, serde_json::Value>>,
}

#[derive(Deserialize)]
struct XErr {
    #[serde(rename = "XErr")]
    xerr: u64,
}

fn user_hash(r: &XboxResponse) -> Result<String> {
    r.display_claims
        .xui
        .first()
        .and_then(|c| c.get("uhs"))
        .and_then(|v| v.as_str())
        .map(str::to_owned)
        .ok_or_else(|| Error::parse("Xbox response", "missing user hash"))
}

async fn xbox_live(http: &Http, ep: &Endpoints, ms_access_token: &str) -> Result<(String, String)> {
    let url = format!("{}/user/authenticate", ep.xbl);
    let body = json!({
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": format!("d={ms_access_token}"),
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT",
    });
    let resp = http
        .send(|c| {
            c.post(&url)
                .header("Accept", "application/json")
                .json(&body)
        })
        .await?;
    let r: XboxResponse = crate::net::client::json(resp).await?;
    let uhs = user_hash(&r)?;
    Ok((r.token, uhs))
}

pub async fn xsts(http: &Http, ep: &Endpoints, xbl_token: &str) -> Result<(String, String)> {
    let url = format!("{}/xsts/authorize", ep.xsts);
    let body = json!({
        "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token] },
        "RelyingParty": "rp://api.minecraftservices.com/",
        "TokenType": "JWT",
    });
    let resp = http
        .send(|c| {
            c.post(&url)
                .header("Accept", "application/json")
                .json(&body)
        })
        .await?;
    if resp.status().as_u16() == 401 {
        let bytes = resp.bytes().await?;
        let code = serde_json::from_slice::<XErr>(&bytes)
            .map(|x| x.xerr)
            .unwrap_or(0);
        return Err(match code {
            2_148_916_227 => AuthError::XboxBanned,
            2_148_916_233 => AuthError::NoXboxAccount,
            2_148_916_235 => AuthError::CountryUnavailable,
            2_148_916_236 | 2_148_916_237 => AuthError::AdultVerification,
            2_148_916_238 => AuthError::ChildAccount,
            other => AuthError::Xsts(other),
        }
        .into());
    }
    let r: XboxResponse = crate::net::client::json(resp).await?;
    let uhs = user_hash(&r)?;
    Ok((r.token, uhs))
}

#[derive(Deserialize)]
struct McLogin {
    access_token: String,
    #[serde(default)]
    expires_in: Option<i64>,
}

async fn login_with_xbox(
    http: &Http,
    ep: &Endpoints,
    uhs: &str,
    xsts_token: &str,
) -> Result<(String, i64)> {
    let url = format!("{}/authentication/login_with_xbox", ep.minecraft);
    let body = json!({ "identityToken": format!("XBL3.0 x={uhs};{xsts_token}") });
    let resp = http
        .send(|c| {
            c.post(&url)
                .header("Accept", "application/json")
                .json(&body)
        })
        .await?;
    if resp.status().as_u16() == 403 {
        let text = resp.text().await.unwrap_or_default();
        return Err(
            if text.contains("ACCOUNT_SUSPENDED") || text.contains("suspended") {
                AuthError::Suspended
            } else {
                // "Invalid app registration, see https://aka.ms/AppRegInfo for more information"
                AuthError::InvalidAppRegistration
            }
            .into(),
        );
    }
    let r: McLogin = crate::net::client::json(resp).await?;
    Ok((r.access_token, now_secs() + r.expires_in.unwrap_or(86_400)))
}

#[derive(Deserialize)]
struct Entitlements {
    #[serde(default)]
    items: Vec<Entitlement>,
}

#[derive(Deserialize)]
struct Entitlement {
    name: String,
}

async fn owns_java_edition(http: &Http, ep: &Endpoints, mc_token: &str) -> Result<bool> {
    let url = format!("{}/entitlements/mcstore", ep.minecraft);
    let resp = check(http.send(|c| c.get(&url).bearer_auth(mc_token)).await?).await?;
    let bytes = resp.bytes().await?;
    let e: Entitlements = serde_json::from_slice(&bytes).unwrap_or(Entitlements { items: vec![] });
    Ok(e.items
        .iter()
        .any(|i| i.name == "product_minecraft" || i.name == "game_minecraft"))
}

pub async fn profile(http: &Http, ep: &Endpoints, mc_token: &str) -> Result<Option<Profile>> {
    let url = format!("{}/minecraft/profile", ep.minecraft);
    let resp = http.send(|c| c.get(&url).bearer_auth(mc_token)).await?;
    if resp.status().as_u16() == 404 {
        return Ok(None);
    }
    crate::net::client::json(resp).await.map(Some)
}

/// Complete the chain from Microsoft tokens to a Minecraft session that owns the game.
pub async fn minecraft_session(
    http: &Http,
    ep: &Endpoints,
    ms: &crate::auth::msa::MsaTokens,
) -> Result<McSession> {
    let (xbl, _) = xbox_live(http, ep, &ms.access_token).await?;
    let (xsts_token, uhs) = xsts(http, ep, &xbl).await?;
    let (access_token, expires_at) = login_with_xbox(http, ep, &uhs, &xsts_token).await?;
    let owns = owns_java_edition(http, ep, &access_token)
        .await
        .unwrap_or(false);
    let profile = match profile(http, ep, &access_token).await? {
        Some(p) => p,
        // Game Pass users get a profile only after opening the official launcher once.
        None if owns => return Err(AuthError::NoProfile.into()),
        None => return Err(AuthError::NoLicense.into()),
    };
    let xuid = jwt::claim_string(&access_token, "xuid");
    Ok(McSession {
        access_token,
        expires_at,
        profile,
        xuid,
        ms_refresh_token: ms.refresh_token.clone(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::msa::MsaTokens;
    use crate::net::client::NetConfig;
    use wiremock::matchers::{body_partial_json, header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn endpoints(server: &MockServer) -> Endpoints {
        let u = server.uri();
        Endpoints {
            msa: format!("{u}/msa"),
            xbl: format!("{u}/xbl"),
            xsts: format!("{u}/xsts"),
            minecraft: format!("{u}/mc"),
        }
    }

    fn tokens() -> MsaTokens {
        MsaTokens {
            access_token: "ms-access".into(),
            refresh_token: "ms-refresh".into(),
            expires_at: 0,
        }
    }

    async fn mount_xbox(server: &MockServer) {
        Mock::given(method("POST"))
            .and(path("/xbl/user/authenticate"))
            .and(body_partial_json(
                json!({"Properties": {"RpsTicket": "d=ms-access"}}),
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                json!({"Token": "xbl", "DisplayClaims": {"xui": [{"uhs": "hash"}]}}),
            ))
            .mount(server)
            .await;
    }

    #[tokio::test]
    async fn full_chain_with_license() {
        let server = MockServer::start().await;
        mount_xbox(&server).await;
        Mock::given(method("POST"))
            .and(path("/xsts/xsts/authorize"))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                json!({"Token": "xsts", "DisplayClaims": {"xui": [{"uhs": "hash"}]}}),
            ))
            .mount(&server)
            .await;
        // JWT whose payload is {"xuid":"2535"}
        let jwt = "eyJhbGciOiJub25lIn0.eyJ4dWlkIjoiMjUzNSJ9.sig";
        Mock::given(method("POST"))
            .and(path("/mc/authentication/login_with_xbox"))
            .and(body_partial_json(
                json!({"identityToken": "XBL3.0 x=hash;xsts"}),
            ))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(json!({"access_token": jwt, "expires_in": 86400})),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/mc/entitlements/mcstore"))
            .and(header("authorization", format!("Bearer {jwt}").as_str()))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                json!({"items": [{"name": "product_minecraft"}, {"name": "game_minecraft"}]}),
            ))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/mc/minecraft/profile"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "id": "069a79f444e94726a5befca90e38aaf5", "name": "Notch",
                "skins": [{"id": "1", "state": "ACTIVE", "url": "http://textures.minecraft.net/texture/abc", "variant": "CLASSIC"}],
                "capes": []
            })))
            .mount(&server)
            .await;
        let http = Http::new(&NetConfig::default()).unwrap();
        let s = minecraft_session(&http, &endpoints(&server), &tokens())
            .await
            .unwrap();
        assert_eq!(s.profile.name, "Notch");
        assert_eq!(s.xuid.as_deref(), Some("2535"));
        assert_eq!(s.ms_refresh_token, "ms-refresh");
        assert_eq!(s.profile.active_skin().unwrap().variant, "CLASSIC");
    }

    #[tokio::test]
    async fn xsts_errors_are_explained() {
        for (code, expected) in [
            (2_148_916_233u64, "auth.noXboxAccount"),
            (2_148_916_235, "auth.countryUnavailable"),
            (2_148_916_238, "auth.childAccount"),
            (2_148_916_236, "auth.adultVerification"),
            (2_148_916_227, "auth.xboxBanned"),
            (42, "auth.xsts"),
        ] {
            let server = MockServer::start().await;
            mount_xbox(&server).await;
            Mock::given(method("POST"))
                .and(path("/xsts/xsts/authorize"))
                .respond_with(
                    ResponseTemplate::new(401)
                        .set_body_json(json!({"Identity": "0", "XErr": code, "Message": ""})),
                )
                .mount(&server)
                .await;
            let http = Http::new(&NetConfig::default()).unwrap();
            let err = minecraft_session(&http, &endpoints(&server), &tokens())
                .await
                .unwrap_err();
            assert_eq!(err.info().code, expected);
        }
    }

    #[tokio::test]
    async fn invalid_app_registration_and_no_license() {
        let server = MockServer::start().await;
        mount_xbox(&server).await;
        Mock::given(method("POST"))
            .and(path("/xsts/xsts/authorize"))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                json!({"Token": "xsts", "DisplayClaims": {"xui": [{"uhs": "hash"}]}}),
            ))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/mc/authentication/login_with_xbox"))
            .respond_with(ResponseTemplate::new(403).set_body_json(json!({
                "path": "/authentication/login_with_xbox",
                "errorMessage": "Invalid app registration, see https://aka.ms/AppRegInfo for more information"
            })))
            .mount(&server)
            .await;
        let http = Http::new(&NetConfig::default()).unwrap();
        let err = minecraft_session(&http, &endpoints(&server), &tokens())
            .await
            .unwrap_err();
        assert_eq!(err.info().code, "auth.invalidAppRegistration");

        let server = MockServer::start().await;
        mount_xbox(&server).await;
        Mock::given(method("POST"))
            .and(path("/xsts/xsts/authorize"))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                json!({"Token": "xsts", "DisplayClaims": {"xui": [{"uhs": "hash"}]}}),
            ))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/mc/authentication/login_with_xbox"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(json!({"access_token": "t", "expires_in": 86400})),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/mc/entitlements/mcstore"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"items": []})))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/mc/minecraft/profile"))
            .respond_with(
                ResponseTemplate::new(404)
                    .set_body_json(json!({"path": "/minecraft/profile", "error": "NOT_FOUND"})),
            )
            .mount(&server)
            .await;
        let err = minecraft_session(&http, &endpoints(&server), &tokens())
            .await
            .unwrap_err();
        assert_eq!(err.info().code, "auth.noLicense");
    }

    #[tokio::test]
    async fn refresh_rotates_and_maps_invalid_grant() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/msa/token"))
            .and(wiremock::matchers::body_string_contains(
                "refresh_token=good",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(
                json!({"access_token": "a2", "refresh_token": "r2", "expires_in": 3600}),
            ))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/msa/token"))
            .respond_with(
                ResponseTemplate::new(400).set_body_json(
                    json!({"error": "invalid_grant", "error_description": "expired"}),
                ),
            )
            .mount(&server)
            .await;
        let http = Http::new(&NetConfig::default()).unwrap();
        let ep = endpoints(&server);
        let t = msa::refresh(&http, &ep, "cid", "good").await.unwrap();
        assert_eq!(
            (t.access_token.as_str(), t.refresh_token.as_str()),
            ("a2", "r2")
        );
        let err = msa::refresh(&http, &ep, "cid", "bad").await.unwrap_err();
        assert_eq!(err.info().code, "auth.sessionExpired");
    }
}
