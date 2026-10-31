//! Play statistics computed from instances and their content manifests (nothing extra is
//! tracked besides per-instance play time and session count).

use std::collections::HashMap;

use serde::Serialize;

use crate::instances::{Instance, InstanceManager, LoaderKind};

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct InstanceStat {
    pub id: String,
    pub name: String,
    pub icon: Option<String>,
    pub loader: LoaderKind,
    pub game_version: String,
    pub play_secs: u64,
    pub launches: u32,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct GroupStat {
    pub key: String,
    pub play_secs: u64,
    pub instances: u32,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct ModStat {
    pub title: String,
    pub icon_url: Option<String>,
    pub page_url: Option<String>,
    /// Instances that have it installed.
    pub instances: u32,
    /// Play time of those instances.
    pub play_secs: u64,
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct Stats {
    pub total_play_secs: u64,
    pub total_launches: u32,
    pub instance_count: u32,
    pub world_count: u32,
    pub screenshot_count: u32,
    pub mod_count: u32,
    /// Most played first.
    pub instances: Vec<InstanceStat>,
    pub versions: Vec<GroupStat>,
    pub loaders: Vec<GroupStat>,
    /// Installed in the most instances first (top 12).
    pub mods: Vec<ModStat>,
}

fn count_dir(path: &std::path::Path, f: impl Fn(&std::fs::DirEntry) -> bool) -> u32 {
    std::fs::read_dir(path)
        .map(|rd| rd.flatten().filter(|e| f(e)).count() as u32)
        .unwrap_or(0)
}

fn group(instances: &[Instance], key: impl Fn(&Instance) -> String) -> Vec<GroupStat> {
    let mut map: HashMap<String, GroupStat> = HashMap::new();
    for i in instances {
        let k = key(i);
        let g = map.entry(k.clone()).or_insert(GroupStat {
            key: k,
            play_secs: 0,
            instances: 0,
        });
        g.play_secs += i.play_time_secs;
        g.instances += 1;
    }
    let mut out: Vec<GroupStat> = map.into_values().collect();
    out.sort_by(|a, b| {
        b.play_secs
            .cmp(&a.play_secs)
            .then(b.instances.cmp(&a.instances))
    });
    out
}

pub async fn compute(manager: &InstanceManager) -> Stats {
    let instances = manager.list().await;
    let mut mods: HashMap<String, ModStat> = HashMap::new();
    let (mut worlds, mut shots, mut mod_count) = (0, 0, 0);
    for inst in &instances {
        if let Ok(game) = manager.game_dir(&inst.id) {
            let g = game.clone();
            let (w, s) = tokio::task::spawn_blocking(move || {
                (
                    count_dir(&g.join("saves"), |e| e.path().join("level.dat").is_file()),
                    count_dir(&g.join("screenshots"), |e| {
                        e.file_name().to_string_lossy().ends_with(".png")
                    }),
                )
            })
            .await
            .unwrap_or((0, 0));
            worlds += w;
            shots += s;
        }
        let Ok(content) = manager.content(&inst.id) else {
            continue;
        };
        for e in content.entries().await {
            if e.kind != crate::mods::model::ContentKind::Mod {
                continue;
            }
            mod_count += 1;
            // Same project across instances: by project id when known, else by title.
            let key = e
                .project_id
                .clone()
                .unwrap_or_else(|| e.title.to_lowercase());
            let m = mods.entry(key).or_insert(ModStat {
                title: e.title.clone(),
                icon_url: e.icon_url.clone(),
                page_url: e.page_url.clone(),
                instances: 0,
                play_secs: 0,
            });
            m.instances += 1;
            m.play_secs += inst.play_time_secs;
        }
    }
    let mut mods: Vec<ModStat> = mods.into_values().collect();
    mods.sort_by(|a, b| {
        b.instances
            .cmp(&a.instances)
            .then(b.play_secs.cmp(&a.play_secs))
    });
    mods.truncate(12);
    let mut per_instance: Vec<InstanceStat> = instances
        .iter()
        .map(|i| InstanceStat {
            id: i.id.clone(),
            name: i.name.clone(),
            icon: i.icon.clone(),
            loader: i.loader,
            game_version: i.game_version.clone(),
            play_secs: i.play_time_secs,
            launches: i.launches,
        })
        .collect();
    per_instance.sort_by_key(|i| std::cmp::Reverse(i.play_secs));
    Stats {
        total_play_secs: instances.iter().map(|i| i.play_time_secs).sum(),
        total_launches: instances.iter().map(|i| i.launches).sum(),
        instance_count: instances.len() as u32,
        world_count: worlds,
        screenshot_count: shots,
        mod_count,
        versions: group(&instances, |i| i.game_version.clone()),
        loaders: group(&instances, |i| i.loader.as_str().to_owned()),
        instances: per_instance,
        mods,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instances::NewInstance;

    #[tokio::test]
    async fn sums_and_groups() {
        let dir = tempfile::tempdir().unwrap();
        let m = InstanceManager::new(dir.path().join("instances"));
        for (name, v, secs) in [
            ("A", "26.3", 600),
            ("B", "26.3", 60),
            ("C", "1.21.11", 3600),
        ] {
            let i = m
                .create(NewInstance {
                    name: name.into(),
                    game_version: v.into(),
                    loader: LoaderKind::Fabric,
                    loader_version: None,
                    icon: None,
                    group: None,
                    game_dir: None,
                })
                .await
                .unwrap();
            m.record_play(&i.id, 1, secs).await.unwrap();
        }
        let g = m.game_dir("A").unwrap().join("saves/World");
        std::fs::create_dir_all(&g).unwrap();
        std::fs::write(g.join("level.dat"), b"x").unwrap();
        let s = compute(&m).await;
        assert_eq!(
            (s.total_play_secs, s.total_launches, s.world_count),
            (4260, 3, 1)
        );
        assert_eq!(s.instances[0].name, "C");
        assert_eq!(s.versions[0].key, "1.21.11");
        assert_eq!(
            (s.versions[1].key.as_str(), s.versions[1].instances),
            ("26.3", 2)
        );
    }
}
