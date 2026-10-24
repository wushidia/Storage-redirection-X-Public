use crate::domain::PathMapping;
use crate::java_hook;
use crate::redirect::policy;

pub(super) fn install_java_hook(
    env: *mut jni_sys::JNIEnv,
    query_enabled: bool,
    install_monitor: bool,
    monitor_enabled: bool,
) {
    if !java_hook::is_available() {
        log::info!("java hook skip: hooker dex unavailable");
        return;
    }
    if java_hook::init_once(env, query_enabled, install_monitor, monitor_enabled) {
        log::info!("java hook init ok");
    } else {
        log::warn!("java hook init failed");
    }
}

pub(super) fn should_bypass_file_monitor_ui_process(package_name: &str, nice_name: &str) -> bool {
    policy::is_file_monitor_ui_package(package_name) || is_legacy_file_monitor_ui_process(nice_name)
}

fn is_legacy_file_monitor_ui_process(nice_name: &str) -> bool {
    nice_name == "android.process.mediaUI" || nice_name.ends_with(":PhotoPicker")
}

pub(super) fn log_allowed_real_paths(paths: &[String]) {
    for path in paths {
        log::info!("cfg allow={}", path);
    }
}

pub(super) fn log_excluded_real_paths(paths: &[String]) {
    for path in paths {
        log::info!("cfg excl={}", path);
    }
}

pub(super) fn log_path_mappings(mappings: &[PathMapping]) {
    for mapping in mappings {
        log::info!("cfg map {} -> {}", mapping.request_path, mapping.final_path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_monitor_ui_bypass_covers_system_oem_and_legacy_names() {
        assert!(should_bypass_file_monitor_ui_process(
            "com.android.documentsui",
            "com.android.documentsui"
        ));
        assert!(should_bypass_file_monitor_ui_process(
            "com.coloros.filemanager",
            "com.coloros.filemanager"
        ));
        assert!(should_bypass_file_monitor_ui_process(
            "com.android.providers.media",
            "com.android.providers.media:PhotoPicker"
        ));
        assert!(should_bypass_file_monitor_ui_process(
            "android.process.mediaUI",
            "android.process.mediaUI"
        ));
        assert!(!should_bypass_file_monitor_ui_process(
            "com.android.providers.downloads",
            "com.android.providers.downloads"
        ));
    }
}
