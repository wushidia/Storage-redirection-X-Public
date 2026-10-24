// 通过 PID/UID 解析调用方包名，多候选时按重定向启用与映射优选唯一解
use super::ANDROID_APP_UID_START;
use crate::config::SettingsHub;
use crate::hook::stats::InterceptHub;
use crate::redirect::policy;
use libc::{c_int, c_void};
use std::ffi::CString;

// 通过 /proc/PID/cmdline 取包名
pub(super) fn resolve_caller_package_by_pid(pid: i32) -> String {
    if pid <= 0 {
        return String::new();
    }

    let cmdline_path = format!("/proc/{}/cmdline", pid);
    let mut cmdline = read_single_line_by_syscall(&cmdline_path);
    if cmdline.is_empty() {
        return String::new();
    }

    if let Some(pos) = cmdline.find('\0') {
        cmdline.truncate(pos);
    }

    if let Some(pos) = cmdline.find(':') {
        cmdline.truncate(pos);
    }

    normalize_caller_package_text(&cmdline)
}

pub(super) fn resolve_caller_package_by_uid(caller_uid: i32, hub: &InterceptHub) -> String {
    if caller_uid < ANDROID_APP_UID_START {
        return String::new();
    }

    let mut packages = policy::get_packages_for_uid(caller_uid);
    if packages.is_empty() {
        policy::refresh_shared_uid_cache();
        packages = policy::get_packages_for_uid(caller_uid);
    }
    if packages.is_empty() {
        return String::new();
    }

    packages.sort();
    packages.dedup();

    let mut candidates = Vec::new();
    let self_package = hub.get_package_name();
    for package in packages {
        if package.is_empty() || package == self_package {
            continue;
        }
        if policy::is_system_writer_package(&package) {
            continue;
        }
        candidates.push(package);
    }

    if candidates.is_empty() {
        return String::new();
    }
    if candidates.len() == 1 {
        return candidates.remove(0);
    }

    let config = SettingsHub::instance();
    let mut mapping_candidates = Vec::new();
    let mut fallback_candidates = Vec::new();

    for candidate in candidates {
        if !config.should_redirect(&candidate, caller_uid) {
            continue;
        }
        let mappings = config.get_path_mappings(&candidate, caller_uid);
        if !mappings.is_empty() {
            mapping_candidates.push(candidate);
        } else {
            fallback_candidates.push(candidate);
        }
    }

    if mapping_candidates.len() == 1 {
        return mapping_candidates.remove(0);
    }
    if mapping_candidates.is_empty() && fallback_candidates.len() == 1 {
        return fallback_candidates.remove(0);
    }

    String::new()
}

// 直接走 syscall，避免触发自身 Hook
fn read_single_line_by_syscall(path: &str) -> String {
    if path.is_empty() {
        return String::new();
    }

    let Ok(c_path) = CString::new(path) else {
        return String::new();
    };

    let fd = unsafe {
        libc::syscall(
            libc::SYS_openat,
            libc::AT_FDCWD,
            c_path.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC,
            0,
        ) as c_int
    };
    if fd < 0 {
        return String::new();
    }

    let mut buffer = [0u8; 256];
    let mut total = 0usize;
    while total < buffer.len() - 1 {
        let n = unsafe {
            libc::read(
                fd,
                buffer[total..].as_mut_ptr() as *mut c_void,
                buffer.len() - 1 - total,
            )
        };
        if n <= 0 {
            break;
        }

        total += n as usize;
        if buffer[..total]
            .iter()
            .any(|b| *b == 0 || *b == b'\n' || *b == b'\r')
        {
            break;
        }
    }

    unsafe { libc::close(fd) };
    if total == 0 {
        return String::new();
    }

    let mut end = 0usize;
    while end < total {
        let ch = buffer[end];
        if ch == 0 || ch == b'\n' || ch == b'\r' {
            break;
        }
        end += 1;
    }

    if end == 0 {
        return String::new();
    }

    String::from_utf8_lossy(&buffer[..end]).to_string()
}

fn normalize_caller_package_text(text: &str) -> String {
    if text.is_empty() {
        return String::new();
    }

    let mut value = text.to_string();
    if let Some(pos) = value.find(['\r', '\n', '\t', ' ']) {
        value.truncate(pos);
    }
    if value.is_empty() {
        return String::new();
    }

    if !value
        .chars()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == '.' || ch == '_' || ch == '-')
    {
        return String::new();
    }
    value
}
