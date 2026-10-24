use crate::domain::PathMapping;
use crate::platform::fs;
use crate::zygisk::abi;
use std::ffi::CString;

#[allow(clippy::too_many_arguments)]
pub(super) fn build_companion_request_payload(
    pid: i32,
    uid: i32,
    package_name: &str,
    app_data_dir: &str,
    redirect_target: &str,
    allowed_real_paths: &[String],
    path_mappings: &[PathMapping],
    is_mapping_mode_only: bool,
) -> String {
    let mut mappings = Vec::new();
    for mapping in path_mappings {
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
        "allowed_real_paths": allowed_real_paths,
        "mapping_mode_only": is_mapping_mode_only,
        "path_mappings": mappings,
    });

    serde_json::to_string(&payload).unwrap_or_default()
}

// 序列化挂载请求并通过伴生进程 fd 发送
pub(super) fn send_companion_request_payload(api: Option<&abi::Api>, payload: &str) -> bool {
    let Some(api) = api else {
        return false;
    };
    if payload.is_empty() {
        return false;
    }

    let fd = api.connect_companion();
    if fd < 0 {
        log::warn!("companion connect failed");
        return false;
    }

    let payload_len = payload.len() as u32;
    let sent =
        fs::write_all(fd, &payload_len.to_ne_bytes()) && fs::write_all(fd, payload.as_bytes());
    unsafe { libc::close(fd) };

    if !sent {
        log::warn!("companion send failed");
        return false;
    }

    log::info!("companion req sent (async mount)");
    true
}

// 发送挂载请求前清理当前 PID 标记，避免读取到旧结果。
pub(super) fn clear_mount_status_marker(app_data_dir: &str, app_pid: i32) {
    if app_data_dir.is_empty() || app_pid <= 0 {
        return;
    }

    let marker_path = format!("{}/.srx_mount_status_{}", app_data_dir, app_pid);
    let Ok(c_path) = CString::new(marker_path.clone()) else {
        return;
    };
    if unsafe { libc::unlink(c_path.as_ptr()) } == 0 {
        log::info!("old marker cleared {}", marker_path);
    }
}
