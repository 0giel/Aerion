//! From "Play" to a ready command: loader, files, Java, memory, arguments.

use std::path::PathBuf;

use crate::Engine;
use crate::error::{Error, Result};
use crate::game::install::{Prepared, prepare};
use crate::game::launch::{
    AuthInfo, CommandSpec, LaunchOptions, build_command, default_env, prepare_game_dir,
};
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
    let version_id = ensure_version(engine, instances, &inst, ctx).await?;
    let prepared = prepare(&engine.meta, &engine.env, &version_id, mode, ctx).await?;
    ctx.stage("stage.java", None);
    let java = pick_java(engine, settings, &inst, &prepared.java, ctx).await?;
    let mut jvm_args = merge_args(&settings.jvm_args, &inst.jvm_args);
    if !jvm_args.is_empty() {
        probe_args(&java.path, &jvm_args).await?;
    }
    jvm_args.extend(crate::aerion_mod::jvm_args(&engine.paths, &inst));
    let mut game_args = merge_args(&settings.game_args, &inst.game_args);
    if inst.fullscreen.unwrap_or(settings.fullscreen)
        && !game_args.iter().any(|a| a == "--fullscreen")
    {
        game_args.push("--fullscreen".into());
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
        min_memory_mb: inst.min_memory_mb.or(settings.min_memory_mb),
        preset: inst.jvm_preset.unwrap_or(settings.jvm_preset),
        extra_jvm_args: jvm_args,
        extra_game_args: game_args,
        resolution: inst
            .resolution
            .or(settings.resolution)
            .map(|r| (r.width, r.height)),
        quick_play_server: join_server
            .or_else(|| inst.join_server.clone())
            .filter(|s| !s.trim().is_empty()),
        env: merge_env(
            merge_env(default_env(engine.env.os), &settings.env_vars),
            &inst.env_vars,
        ),
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
