//! From "Play" to a ready command: loader, files, Java, memory, arguments.

use std::path::PathBuf;

use crate::Engine;
use crate::error::{Error, Result};
use crate::game::install::{Prepared, prepare};
use crate::game::launch::{AuthInfo, CommandSpec, LaunchOptions, build_command, prepare_game_dir};
use crate::instances::{Instance, InstanceManager, LoaderKind};
use crate::java::detect::{detect_system, inspect};
use crate::java::flags::{default_max_memory_mb, probe_args};
use crate::java::{JavaInstall, JavaSource};
use crate::loaders;
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

pub async fn pick_java(
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

pub async fn plan(
    engine: &Engine,
    settings: &Settings,
    instances: &InstanceManager,
    instance_id: &str,
    auth: AuthInfo,
    mode: VerifyMode,
    ctx: &TaskCtx,
) -> Result<LaunchPlan> {
    let inst = instances.get(instance_id).await?;
    let version_id = ensure_version(engine, instances, &inst, ctx).await?;
    let prepared = prepare(&engine.meta, &engine.env, &version_id, mode, ctx).await?;
    ctx.stage("stage.java", None);
    let java = pick_java(engine, settings, &inst, &prepared.java, ctx).await?;
    if !inst.jvm_args.is_empty() {
        probe_args(&java.path, &inst.jvm_args).await?;
    }
    let modded = inst.loader != LoaderKind::Vanilla;
    let max_memory_mb = inst
        .max_memory_mb
        .or(settings.max_memory_mb)
        .unwrap_or_else(|| default_max_memory_mb(total_memory_mb(), modded));
    let game_dir = instances.game_dir(&inst.id)?;
    prepare_game_dir(&prepared, &game_dir).await?;
    let opts = LaunchOptions {
        game_dir: game_dir.clone(),
        java: java.path.clone(),
        java_major: java.major,
        max_memory_mb,
        min_memory_mb: inst.min_memory_mb,
        preset: inst.jvm_preset.unwrap_or(settings.jvm_preset),
        extra_jvm_args: inst.jvm_args.clone(),
        extra_game_args: inst.game_args.clone(),
        resolution: inst.resolution.map(|r| (r.width, r.height)),
        quick_play_server: inst.join_server.clone().filter(|s| !s.trim().is_empty()),
        env: inst
            .env_vars
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect(),
        wrapper: inst
            .wrapper
            .as_deref()
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
    })
}
