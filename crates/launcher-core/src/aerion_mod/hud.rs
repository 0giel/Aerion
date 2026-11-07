//! `hud.json`: the Aerion mod's HUD modules and client features, edited by the launcher and by the
//! in-game menu (free-form text formats only in the launcher). Key bindings live in the game's
//! options.txt. Positions are per-mille of the
//! free space (0 = left/top, 1000 = right/bottom), scale is a percentage. The defaults match the
//! mod's (`mod/common/.../hud/HudConfig.java`).

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::paths::Paths;

/// Look of a module. In the shared style every field is set; a module's own style overrides
/// only what it sets.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct HudStyle {
    /// `theme`, `chroma` or `#rrggbb`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// `theme`, `none`, `chroma` or `#rrggbb`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub background: Option<String>,
    /// Background opacity, 0–100.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub opacity: Option<u8>,
    /// `none`, `accent`, `chroma` or `#rrggbb`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub border: Option<String>,
    /// Border thickness, 1–4.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub border_width: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub radius: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub padding: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shadow: Option<bool>,
    /// `left`, `center` or `right`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub align: Option<String>,
    /// `game` (Minecraft's pixel font) or `smooth`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub font: Option<String>,
    /// How fast `chroma` colours move, 1–10.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chroma_speed: Option<u8>,
    /// Chroma text as a wave across the letters.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chroma_wave: Option<bool>,
}

impl HudStyle {
    fn is_empty(&self) -> bool {
        *self == Self::default()
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct HudModule {
    pub enabled: bool,
    pub x: u16,
    pub y: u16,
    pub scale: u16,
    #[serde(skip_serializing_if = "HudStyle::is_empty")]
    pub style: HudStyle,
    /// Module-specific options, as strings (formats, layouts, on/off as "1"/"0").
    pub options: BTreeMap<String, String>,
}

impl Default for HudModule {
    fn default() -> Self {
        Self {
            enabled: false,
            x: 0,
            y: 0,
            scale: 100,
            style: HudStyle::default(),
            options: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct Zoom {
    pub enabled: bool,
    /// How many times closer, 2–10.
    pub factor: u8,
    pub smooth: bool,
    /// The mouse wheel zooms in and out while the key is held.
    pub scroll: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct Freelook {
    pub enabled: bool,
    /// Look around while the key is held (otherwise the key switches it on and off).
    pub hold: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct Toggle {
    pub enabled: bool,
}

/// A feature whose settings the in-game menu owns (hitboxes, overlays, sounds); the launcher
/// keeps them and only switches the feature on and off.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct FeatureOptions {
    pub enabled: bool,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub options: BTreeMap<String, String>,
}

/// The sound editor: enabled unless switched off, volume per sound id in `options`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct SoundOptions {
    pub enabled: bool,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub options: BTreeMap<String, String>,
}

impl Default for SoundOptions {
    fn default() -> Self {
        Self {
            enabled: true,
            options: BTreeMap::new(),
        }
    }
}

impl Default for Zoom {
    fn default() -> Self {
        Self {
            enabled: true,
            factor: 4,
            smooth: true,
            scroll: true,
        }
    }
}

impl Default for Freelook {
    fn default() -> Self {
        Self {
            enabled: false,
            hold: true,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct HudFeatures {
    pub zoom: Zoom,
    pub freelook: Freelook,
    /// `options.level`: gamma in percent (100–1500), also changed in the game with keys.
    pub fullbright: FeatureOptions,
    /// The field of view ignores sprinting, speed effects and bows (`options.spyglass`).
    pub fov_lock: FeatureOptions,
    pub toggle_sprint: Toggle,
    pub toggle_sneak: Toggle,
    pub hitboxes: FeatureOptions,
    pub overlays: FeatureOptions,
    pub sounds: SoundOptions,
    pub crosshair: FeatureOptions,
    pub hurt_cam: FeatureOptions,
    pub light_levels: FeatureOptions,
    pub block_outline: FeatureOptions,
    pub chat: FeatureOptions,
    pub perspective: FeatureOptions,
    pub fov_key: FeatureOptions,
    pub world_look: FeatureOptions,
    pub hit_color: FeatureOptions,
    pub screens: FeatureOptions,
    pub waypoints: FeatureOptions,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase", default)]
pub struct HudConfig {
    pub enabled: bool,
    pub style: HudStyle,
    pub modules: BTreeMap<String, HudModule>,
    pub features: HudFeatures,
}

/// Every module the mod knows: id, on by default, x, y.
const MODULES: &[(&str, bool, u16, u16)] = &[
    ("fps", true, 8, 10),
    ("coords", true, 8, 110),
    ("potions", true, 992, 480),
    ("armor", true, 992, 900),
    ("toggles", true, 8, 960),
    ("keystrokes", false, 8, 760),
    ("cps", false, 8, 290),
    ("direction", false, 500, 10),
    ("ping", false, 8, 390),
    ("clock", false, 700, 10),
    ("minimap", false, 992, 10),
    ("memory", false, 8, 490),
    ("speed", false, 8, 590),
    ("death", false, 8, 680),
    ("totems", false, 860, 900),
    ("items", false, 860, 560),
    ("session", false, 500, 170),
    ("day", false, 500, 250),
    ("light", false, 500, 90),
    ("armorWarn", false, 500, 330),
    ("packs", false, 500, 410),
    ("bossbar", false, 500, 55),
    ("scoreboard", false, 1000, 500),
    ("chatbox", false, 0, 733),
    ("blockInfo", false, 300, 90),
    ("fpsGraph", false, 992, 700),
    ("playerList", false, 992, 300),
];

impl Default for HudConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            style: HudStyle {
                text: Some("theme".into()),
                background: Some("theme".into()),
                opacity: Some(55),
                border: Some("none".into()),
                border_width: Some(1),
                radius: Some(0),
                padding: Some(3),
                shadow: Some(true),
                align: Some("left".into()),
                font: Some("smooth".into()),
                chroma_speed: Some(5),
                chroma_wave: Some(false),
            },
            modules: MODULES
                .iter()
                .map(|(id, enabled, x, y)| {
                    (
                        (*id).to_owned(),
                        HudModule {
                            enabled: *enabled,
                            x: *x,
                            y: *y,
                            ..HudModule::default()
                        },
                    )
                })
                .collect(),
            features: HudFeatures::default(),
        }
    }
}

fn color_ok(c: &str, words: &[&str]) -> bool {
    words.contains(&c)
        || (c.len() == 7 && c.starts_with('#') && c[1..].bytes().all(|b| b.is_ascii_hexdigit()))
}

fn style_ok(s: &HudStyle) -> bool {
    s.text
        .as_deref()
        .is_none_or(|c| color_ok(c, &["theme", "chroma"]))
        && s.background
            .as_deref()
            .is_none_or(|c| color_ok(c, &["theme", "none", "chroma"]))
        && s.border
            .as_deref()
            .is_none_or(|c| color_ok(c, &["none", "accent", "chroma"]))
        && s.opacity.is_none_or(|o| o <= 100)
        && s.radius.is_none_or(|r| r <= 8)
        && s.padding.is_none_or(|p| p <= 10)
        && s.align
            .as_deref()
            .is_none_or(|a| matches!(a, "left" | "center" | "right"))
        && s.border_width.is_none_or(|w| (1..=4).contains(&w))
        && s.chroma_speed.is_none_or(|v| (1..=10).contains(&v))
        && s.font
            .as_deref()
            .is_none_or(|f| matches!(f, "game" | "smooth"))
}

impl HudConfig {
    pub fn validate(&self) -> Result<()> {
        let ok = style_ok(&self.style)
            && self.modules.len() <= 64
            && self.modules.iter().all(|(id, m)| {
                !id.is_empty()
                    && id.len() <= 32
                    && id.bytes().all(|b| b.is_ascii_alphanumeric())
                    && m.x <= 1000
                    && m.y <= 1000
                    && (25..=400).contains(&m.scale)
                    && style_ok(&m.style)
                    && m.options.len() <= 16
                    && m.options
                        .iter()
                        .all(|(k, v)| k.len() <= 32 && v.len() <= 200)
            })
            && (2..=10).contains(&self.features.zoom.factor)
            && self.features.hitboxes.options.len() <= 32
            && self.features.overlays.options.len() <= 32
            && self.features.sounds.options.len() <= 8192
            && [
                &self.features.hitboxes.options,
                &self.features.overlays.options,
                &self.features.crosshair.options,
                &self.features.hurt_cam.options,
                &self.features.light_levels.options,
                &self.features.block_outline.options,
                &self.features.chat.options,
                &self.features.perspective.options,
                &self.features.fov_key.options,
                &self.features.fullbright.options,
                &self.features.fov_lock.options,
                &self.features.world_look.options,
                &self.features.hit_color.options,
                &self.features.screens.options,
                &self.features.waypoints.options,
                &self.features.sounds.options,
            ]
            .iter()
            .all(|o| o.iter().all(|(k, v)| k.len() <= 200 && v.len() <= 64));
        if ok {
            Ok(())
        } else {
            Err(Error::Invalid("invalid HUD settings".into()))
        }
    }
}

pub fn file(paths: &Paths) -> PathBuf {
    paths.aerion_mod_dir().join("hud.json")
}

/// The saved settings with every known module present (new modules get their defaults).
pub async fn load(paths: &Paths) -> HudConfig {
    let mut cfg = match tokio::fs::read(file(paths)).await {
        Ok(bytes) => serde_json::from_slice::<HudConfig>(&bytes).unwrap_or_else(|e| {
            tracing::warn!(error = %e, "hud.json is invalid; using defaults");
            HudConfig::default()
        }),
        Err(_) => HudConfig::default(),
    };
    for (id, m) in HudConfig::default().modules {
        cfg.modules.entry(id).or_insert(m);
    }
    cfg
}

pub async fn save(paths: &Paths, cfg: &HudConfig) -> Result<()> {
    cfg.validate()?;
    crate::util::fs::write_json(file(paths), cfg).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_valid_and_complete() {
        let d = HudConfig::default();
        d.validate().unwrap();
        assert_eq!(d.modules.len(), MODULES.len());
        // Unset module styles are not written, so the mod falls back to the shared style.
        let json = serde_json::to_value(&d).unwrap();
        assert!(json["modules"]["fps"].get("style").is_none());
        assert_eq!(json["features"]["toggleSprint"]["enabled"], false);
    }

    #[test]
    fn rejects_bad_values() {
        let mut c = HudConfig::default();
        c.modules.get_mut("fps").unwrap().scale = 900;
        assert!(c.validate().is_err());
        let mut c = HudConfig::default();
        c.style.text = Some("red; drop".into());
        assert!(c.validate().is_err());
        let mut c = HudConfig::default();
        c.style.font = Some("comic".into());
        assert!(c.validate().is_err());
    }

    #[test]
    fn keeps_a_module_look_set_in_the_game() {
        // The in-game menu writes only the fields a module changes.
        let m: HudModule = serde_json::from_str(
            r##"{"style":{"font":"smooth","borderWidth":2,"text":"#ffe25a"}}"##,
        )
        .unwrap();
        assert_eq!(m.style.font.as_deref(), Some("smooth"));
        assert_eq!(m.style.border_width, Some(2));
        let back = serde_json::to_value(&m).unwrap();
        assert_eq!(back["style"]["borderWidth"], 2);
        assert!(back["style"].get("align").is_none());
    }

    #[test]
    fn accepts_chroma_and_keeps_new_features() {
        let mut c: HudConfig = serde_json::from_str(
            r##"{"features":{"hurtCam":{"enabled":true,"options":{"strength":"25"}},
                "crosshair":{"enabled":true,"options":{"color":"chroma"}}}}"##,
        )
        .unwrap();
        c.style.text = Some("chroma".into());
        c.style.border = Some("chroma".into());
        c.style.chroma_speed = Some(10);
        c.validate().unwrap();
        let back = serde_json::to_value(&c).unwrap();
        assert_eq!(back["features"]["hurtCam"]["options"]["strength"], "25");
        assert_eq!(back["features"]["crosshair"]["options"]["color"], "chroma");
        c.style.chroma_speed = Some(11);
        assert!(c.validate().is_err());
    }

    #[test]
    fn keeps_feature_settings_from_the_game() {
        let c: HudConfig = serde_json::from_str(
            r##"{"features":{"sounds":{"options":{"minecraft:entity.zombie.ambient":"25"}},
                "hitboxes":{"enabled":true,"options":{"reachColor":"#ff0000"}}}}"##,
        )
        .unwrap();
        c.validate().unwrap();
        assert!(c.features.sounds.enabled);
        let back = serde_json::to_value(&c).unwrap();
        assert_eq!(
            back["features"]["sounds"]["options"]["minecraft:entity.zombie.ambient"],
            "25"
        );
        assert_eq!(
            back["features"]["hitboxes"]["options"]["reachColor"],
            "#ff0000"
        );
    }

    #[tokio::test]
    async fn fills_missing_modules() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::at(dir.path().to_path_buf(), false);
        std::fs::create_dir_all(paths.aerion_mod_dir()).unwrap();
        std::fs::write(
            file(&paths),
            r#"{"modules":{"fps":{"enabled":false,"x":5,"y":5,"scale":150}}}"#,
        )
        .unwrap();
        let c = load(&paths).await;
        assert_eq!(c.modules["fps"].scale, 150);
        assert!(c.modules.contains_key("minimap"));
    }
}
