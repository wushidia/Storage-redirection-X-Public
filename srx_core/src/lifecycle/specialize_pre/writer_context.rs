use crate::config::SettingsHub;
use crate::domain::PathMapping;
use crate::redirect::policy;

pub(super) struct SystemWriterContext {
    pub(super) is_system_writer: bool,
    pub(super) is_media_provider: bool,
    pub(super) has_merged_writer_mappings: bool,
    pub(super) merged_writer_mappings: Vec<PathMapping>,
}

// 解析系统代写进程的重定向上下文，决定挂载或 Hook 模式
pub(super) fn resolve_system_writer_context(
    package_name: &str,
    app_uid: i32,
    config: &SettingsHub,
    should_redirect: &mut bool,
    should_monitor: &mut bool,
    is_system_writer_hook_redirect: &mut bool,
) -> SystemWriterContext {
    let is_shared_uid_writer = policy::is_shared_uid_process(app_uid);
    let mut context = SystemWriterContext {
        is_system_writer: policy::is_system_writer_package(package_name) || is_shared_uid_writer,
        is_media_provider: policy::is_media_provider_package(package_name) || is_shared_uid_writer,
        has_merged_writer_mappings: false,
        merged_writer_mappings: Vec::new(),
    };

    if !context.is_system_writer {
        return context;
    }

    policy::refresh_shared_uid_cache();
    context.merged_writer_mappings = config.get_merged_path_mappings_for_user(app_uid);
    context.has_merged_writer_mappings = !context.merged_writer_mappings.is_empty();

    let has_enabled_apps = config.has_enabled_redirect_apps_for_user(app_uid);

    let is_file_monitor_enabled = config.is_file_monitor_enabled();
    if context.has_merged_writer_mappings || has_enabled_apps {
        *should_redirect = true;
        *is_system_writer_hook_redirect = true;
    } else {
        *should_redirect = false;
        *is_system_writer_hook_redirect = false;
    }
    if !*should_monitor && context.is_media_provider && is_file_monitor_enabled {
        *should_monitor = true;
    }

    if context.has_merged_writer_mappings {
        log::info!(
            "writer map-mode on merged={} (per-caller hook)",
            context.merged_writer_mappings.len()
        );
    } else if has_enabled_apps {
        log::info!("writer map-mode on: enabled apps exist, caller default redirect");
    } else {
        log::info!("writer map-mode skip: no enabled apps, fallback monitor/bypass");
    }

    context
}
