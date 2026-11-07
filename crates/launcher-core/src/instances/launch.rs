//! From "Play" to a ready command: loader, files, Java, memory, arguments.

use std::path::PathBuf;

use crate::Engine;
use crate::auth::AuthInfo;
use crate::error::{Error, IoContext, Result};
use crate::game::install::{Prepared, prepare};
use crate::game::launch::{
    CommandSpec, LaunchOptions, build_command, default_env, prepare_game_dir,
};
use crate::horizon;
use crate::instances::{Instance, InstanceManager};
use crate::java::detect::{detect_system, inspect};
use crate::java::flags::{default_max_memory_mb, probe_args};
use crate::java::{JavaInstall, JavaSource};
use crate::loaders;
use crate::loaders::LoaderKind;
use crate::mojang::version::JavaVersionReq;
use crate::net::download::VerifyMode;
use crate::progress::TaskCtx;
use crate::settings::Settings;
use crate::util::sys::total_memory_mb;

pub struct LaunchPlan {
    pub instance: Instance,
    pub prepared: Prepared,
    pub java: JavaInstall,
    pub spec: CommandSpec,
    pub max_memory_mb: u32,
    pub game_dir: PathBuf,
    /// Give this game's settings back to the shared ones when it exits (see `options::Pulled`).
    pub share_back: bool,
}

/// Version id to launch, installing the mod loader profile when needed.
pub async fn ensure_version(
    engine: &Engine,
    instances: &InstanceManager,
    inst: &Instance,
    ctx: &TaskCtx,
) -> Result<String> {
    if inst.loader == LoaderKind::Vanilla {
        return Ok(inst.game_version.clone());
    }
    if let Some(id) = &inst.version_id
        && engine.paths.version_json(id).is_file()
    {
        return Ok(id.clone());
    }
    let (id, loader_version) = loaders::install(
        engine,
        inst.loader,
        &inst.game_version,
        inst.loader_version.as_deref(),
        ctx,
    )
    .await?;
    let id2 = id.clone();
    instances
        .modify(&inst.id, move |i| {
            i.version_id = Some(id2);
            i.loader_version = Some(loader_version);
        })
        .await?;
    Ok(id)
}

/// Launcher defaults overlaid with the instance's own variables (which win).
fn merge_env(
    defaults: Vec<(String, String)>,
    instance: &std::collections::BTreeMap<String, String>,
) -> Vec<(String, String)> {
    let mut env: Vec<(String, String)> = defaults
        .into_iter()
        .filter(|(k, _)| !instance.contains_key(k))
        .collect();
    env.extend(instance.iter().map(|(k, v)| (k.clone(), v.clone())));
    env
}

/// Global arguments first, then the instance's own (later JVM flags win).
fn merge_args(global: &[String], instance: &[String]) -> Vec<String> {
    global.iter().chain(instance).cloned().collect()
}

/// The game's environment: launcher defaults, then the global variables, then the instance's.
fn launch_env(engine: &Engine, settings: &Settings, inst: &Instance) -> Vec<(String, String)> {
    merge_env(
        merge_env(default_env(engine.env.os), &settings.env_vars),
        &inst.env_vars,
    )
}

fn resolution(settings: &Settings, inst: &Instance) -> Option<(u32, u32)> {
    inst.resolution
        .or(settings.resolution)
        .map(|r| (r.width, r.height))
}

/// Where Quick Play goes, as (world, server): a server asked for right now wins; otherwise the
/// instance's world, then its server.
fn quick_play(
    inst: &Instance,
    game_dir: &std::path::Path,
    join_server: Option<String>,
) -> (Option<String>, Option<String>) {
    let world = if join_server.is_some() {
        None
    } else {
        super::worlds::resolve_join_world(game_dir, inst.join_world.as_deref())
    };
    let server = join_server
        .or_else(|| world.is_none().then(|| inst.join_server.clone()).flatten())
        .filter(|s| !s.trim().is_empty());
    (world, server)
}

/// The Horizon build an instance starts with: its own, or the newest (installed and remembered
/// on first use).
async fn horizon_build(
    engine: &Engine,
    instances: &InstanceManager,
    inst: &Instance,
    ctx: &TaskCtx,
) -> Result<horizon::Build> {
    if let Some(b) = inst
        .loader_version
        .as_deref()
        .and_then(|id| horizon::installed_build(engine, id))
    {
        return Ok(b);
    }
    let b = horizon::install_latest(engine, ctx).await?;
    let id = b.id.clone();
    instances
        .modify(&inst.id, move |i| i.loader_version = Some(id))
        .await?;
    Ok(b)
}

/// Switch a Horizon instance to the newest build, prepared, and return its id. Builds no
/// instance uses go; this instance's previous one stays as a fallback until the next update, as
/// Horizon advises.
pub async fn update_horizon_build(
    engine: &Engine,
    instances: &InstanceManager,
    instance_id: &str,
    ctx: &TaskCtx,
) -> Result<String> {
    let previous = instances.get(instance_id).await?.loader_version;
    let build = horizon::install_latest(engine, ctx).await?;
    horizon::ensure_resources(engine, &build, ctx).await?;
    let id = build.id.clone();
    instances
        .modify(instance_id, move |i| i.loader_version = Some(id))
        .await?;
    let mut keep: Vec<String> = instances
        .list()
        .await
        .into_iter()
        .filter(|i| i.loader == LoaderKind::Horizon)
        .filter_map(|i| i.loader_version)
        .collect();
    keep.extend(previous);
    horizon::prune(engine, &keep).await;
    Ok(build.id)
}

/// "Verify files": everything a start needs, with every file fully re-hashed and repaired.
pub async fn verify(
    engine: &Engine,
    settings: &Settings,
    instances: &InstanceManager,
    instance_id: &str,
    ctx: &TaskCtx,
) -> Result<()> {
    let inst = instances.get(instance_id).await?;
    if inst.loader == LoaderKind::Horizon {
        let build = horizon_build(engine, instances, &inst, ctx).await?;
        horizon::ensure_resources(engine, &build, ctx).await?;
        return Ok(());
    }
    let version = ensure_version(engine, instances, &inst, ctx).await?;
    let prepared = prepare(&engine.meta, &engine.env, &version, VerifyMode::Full, ctx).await?;
    pick_java(engine, settings, &inst, &prepared.java, ctx).await?;
    Ok(())
}

async fn pick_java(
    engine: &Engine,
    settings: &Settings,
    inst: &Instance,
    req: &JavaVersionReq,
    ctx: &TaskCtx,
) -> Result<JavaInstall> {
    let explicit = inst.java_path.clone().or_else(|| {
        settings
            .java_overrides
            .get(&req.major_version.to_string())
            .cloned()
    });
    if let Some(path) = explicit {
        let java = inspect(&path, JavaSource::System).await?;
        if java.major < req.major_version {
            return Err(Error::JavaInvalid {
                path,
                reason: format!(
                    "Java {} is too old, this version needs Java {}",
                    java.major, req.major_version
                ),
            });
        }
        return Ok(java);
    }
    if settings.java_auto_install {
        return engine
            .runtimes()
            .ensure(&req.component, req.major_version, ctx)
            .await;
    }
    if let Some(j) = engine
        .runtimes()
        .installed(&req.component, req.major_version)
        .await
    {
        return Ok(j);
    }
    detect_system()
        .await
        .into_iter()
        .find(|j| j.major == req.major_version)
        .ok_or(Error::JavaNotFound(req.major_version))
}

// Every input is a distinct piece of launch context; a struct would only rename them.
#[allow(clippy::too_many_arguments)]
pub async fn plan(
    engine: &Engine,
    settings: &Settings,
    instances: &InstanceManager,
    instance_id: &str,
    auth: AuthInfo,
    mode: VerifyMode,
    join_server: Option<String>,
    ctx: &TaskCtx,
) -> Result<LaunchPlan> {
    let inst = instances.get(instance_id).await?;
    if inst.loader == LoaderKind::Horizon {
        return plan_horizon(engine, settings, instances, inst, auth, join_server, ctx).await;
    }
    let version_id = ensure_version(engine, instances, &inst, ctx).await?;
    let prepared = prepare(&engine.meta, &engine.env, &version_id, mode, ctx).await?;
    ctx.stage("stage.java", None);
    let java = pick_java(engine, settings, &inst, &prepared.java, ctx).await?;
    let mut jvm_args = merge_args(&settings.jvm_args, &inst.jvm_args);
    probe_args(&java.path, &jvm_args).await?;
    if inst.aerion_mod {
        jvm_args.extend(crate::aerion_mod::jvm_args(
            &engine.paths,
            &inst.id,
            inst.loader,
            &inst.game_version,
        ));
    }
    let mut game_args = merge_args(&settings.game_args, &inst.game_args);
    if inst.fullscreen.unwrap_or(settings.fullscreen)
        && !game_args.iter().any(|a| a == "--fullscreen")
    {
        game_args.push("--fullscreen".into());
    }
    let max_memory_mb = inst
        .max_memory_mb
        .or(settings.max_memory_mb)
        .unwrap_or_else(|| default_max_memory_mb(total_memory_mb(), inst.loader.is_modded()));
    let game_dir = instances.game_dir(&inst.id)?;
    prepare_game_dir(&prepared, &game_dir).await?;
    let mut share_back = false;
    if inst.shared_options {
        // The shared settings (controls, video, sound…) before the game reads its own.
        let (shared, dir, ver) = (
            engine.paths.shared_options_dir(),
            game_dir.clone(),
            inst.game_version.clone(),
        );
        match tokio::task::spawn_blocking(move || super::options::pull_shared(&shared, &dir, &ver))
            .await
        {
            Ok(Ok(pulled)) => {
                tracing::info!(?pulled, "shared game settings");
                share_back = pulled.shares_back();
            }
            Ok(Err(e)) => tracing::warn!(error = %e, "couldn't apply the shared game settings"),
            Err(e) => tracing::warn!(error = %e, "couldn't apply the shared game settings"),
        }
    }
    let (quick_play_world, quick_play_server) = quick_play(&inst, &game_dir, join_server);
    let opts = LaunchOptions {
        game_dir: game_dir.clone(),
        java: java.path.clone(),
        java_major: java.major,
        max_memory_mb,
        min_memory_mb: inst.min_memory_mb.or(settings.min_memory_mb),
        preset: inst.jvm_preset.unwrap_or(settings.jvm_preset),
        extra_jvm_args: jvm_args,
        extra_game_args: game_args,
        resolution: resolution(settings, &inst),
        quick_play_world,
        quick_play_server,
        env: launch_env(engine, settings, &inst),
        wrapper: inst
            .wrapper
            .as_deref()
            .or(settings.wrapper.as_deref())
            .map(|w| w.split_whitespace().map(str::to_owned).collect())
            .unwrap_or_default(),
        auth,
        client_id: settings.client_id.clone(),
    };
    let spec = build_command(&prepared, &opts, &engine.paths, &engine.env);
    Ok(LaunchPlan {
        instance: inst,
        prepared,
        java,
        spec,
        max_memory_mb,
        game_dir,
        share_back,
    })
}

/// A Horizon instance: its build (installed on first start), the resources prepared from the
/// player's own 26.3 files, and the game started with the session on its standard input.
async fn plan_horizon(
    engine: &Engine,
    settings: &Settings,
    instances: &InstanceManager,
    inst: Instance,
    auth: AuthInfo,
    join_server: Option<String>,
    ctx: &TaskCtx,
) -> Result<LaunchPlan> {
    let build = horizon_build(engine, instances, &inst, ctx).await?;
    let (resources, prepared, java) = horizon::ensure_resources(engine, &build, ctx).await?;
    let game_dir = instances.game_dir(&inst.id)?;
    tokio::fs::create_dir_all(&game_dir).await.at(&game_dir)?;
    let (world, server) = quick_play(&inst, &game_dir, join_server);
    let spec = horizon::command(
        &build,
        &auth,
        &horizon::StartOptions {
            game_dir: game_dir.clone(),
            resources,
            resolution: resolution(settings, &inst),
            fullscreen: inst.fullscreen.unwrap_or(settings.fullscreen),
            world,
            server,
            env: launch_env(engine, settings, &inst),
        },
    )?;
    Ok(LaunchPlan {
        instance: inst,
        prepared,
        java,
        spec,
        max_memory_mb: 0,
        game_dir,
        share_back: false,
    })
}

#[cfg(test)]
mod tests {
    use super::{merge_args, merge_env};

    #[test]
    fn global_then_instance_arguments() {
        let global = ["-XX:+UseZGC".to_owned(), "-Dglobal=1".to_owned()];
        let inst = ["-Dglobal=2".to_owned()];
        assert_eq!(
            merge_args(&global, &inst),
            ["-XX:+UseZGC", "-Dglobal=1", "-Dglobal=2"]
        );
    }

    #[test]
    fn instance_env_overrides_defaults() {
        let defaults = vec![
            ("__GL_THREADED_OPTIMIZATIONS".to_owned(), "0".to_owned()),
            ("A".to_owned(), "1".to_owned()),
        ];
        let instance = [("__GL_THREADED_OPTIMIZATIONS".to_owned(), "1".to_owned())].into();
        let env = merge_env(defaults, &instance);
        assert_eq!(
            env,
            vec![
                ("A".to_owned(), "1".to_owned()),
                ("__GL_THREADED_OPTIMIZATIONS".to_owned(), "1".to_owned()),
            ]
        );
    }
}
