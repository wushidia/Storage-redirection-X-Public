use crate::config::UserProfile;
use crate::platform::fs;
use crate::zygisk::abi;

#[allow(clippy::too_many_arguments)]
pub(super) fn build_companion_request_payload(
    pid: i32,
    uid: i32,
    package_name: &str,
    app_data_dir: &str,
    redirect_target: &str,
    profile: &UserProfile,
) -> String {
    let mut mappings = Vec::new();
    for mapping in &profile.path_mappings {
        mappings.push(serde_json::json!({
            "request_path": mapping.request_path,
            "final_path": mapping.final_path,
        }));
    }

    let payload = serde_json::json!({
        "pid": pid,
        "uid": uid,
        "package": package_name,
        "app_data_dir": app_data_dir,
        "redirect_target": redirect_target,
        "allowed_real_paths": profile.allowed_real_paths,
        "excluded_real_paths": profile.excluded_real_paths,
        "mapping_mode_only": profile.is_mapping_mode_only,
        "sandboxed_paths": profile.sandboxed_paths,
        "read_only_paths": profile.read_only_paths,
        "path_mappings": mappings,
    });

    serde_json::to_string(&payload).unwrap_or_default()
}

// pre 发送后保留 socket，post 读取挂载结果并关闭。
pub(super) fn send_companion_request_payload(api: Option<&abi::Api>, payload: &str) -> libc::c_int {
    let Some(api) = api else {
        return -1;
    };
    if payload.is_empty() {
        return -1;
    }

    let fd = api.connect_companion();
    if fd < 0 {
        log::warn!("companion connect failed");
        return -1;
    }

    if !api.exempt_fd(fd) {
        log::warn!("companion fd exemption failed fd={}", fd);
        unsafe { libc::close(fd) };
        return -1;
    }
    let payload_len = payload.len() as u32;
    let sent =
        fs::write_all(fd, &payload_len.to_ne_bytes()) && fs::write_all(fd, payload.as_bytes());

    if !sent {
        log::warn!("companion send failed");
        unsafe { libc::close(fd) };
        return -1;
    }

    log::info!("companion req sent (async mount)");
    fd
}
