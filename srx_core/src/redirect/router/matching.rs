// 路由匹配辅助：黑名单、允许/排除列表、映射与默认重定向路径生成
use crate::domain::PathMapping;
use crate::platform::paths;

// 路径是否属于系统保护目录（不参与重定向）
pub(super) fn is_blacklisted_path(path: &str) -> bool {
    const BLACKLIST: [&str; 11] = [
        "/system/",
        "/data/app/",
        "/data/misc/",
        "/proc/",
        "/dev/",
        "/sys/",
        "/apex/",
        "/vendor/",
        "/product/",
        "/odm/",
        "/cache/",
    ];
    BLACKLIST
        .iter()
        .any(|prefix| paths::starts_with(path, prefix))
}

pub(super) fn is_excluded_real_path(
    excluded_paths: &[String],
    path: &str,
    storage_root: &str,
) -> bool {
    if storage_root.is_empty() {
        return false;
    }

    for excluded in excluded_paths {
        if paths::matches(excluded, path, true) {
            return true;
        }
    }
    false
}

pub(super) fn is_allowed_real_path(
    allowed_paths: &[String],
    path: &str,
    storage_root: &str,
) -> bool {
    if storage_root.is_empty() {
        return false;
    }

    for allowed in allowed_paths {
        if paths::matches(allowed, path, true) {
            return true;
        }
    }
    false
}

pub(super) fn map_path_to_target(mappings: &[PathMapping], path: &str) -> Option<String> {
    for mapping in mappings {
        if path == mapping.request_path {
            return Some(mapping.final_path.clone());
        }
        let prefix = format!("{}/", mapping.request_path);
        if paths::starts_with(path, &prefix) {
            let suffix = &path[mapping.request_path.len()..];
            return Some(format!("{}{}", mapping.final_path, suffix));
        }
    }
    None
}

pub(super) fn generate_redirected_path(
    storage_root: &str,
    redirect_target: &str,
    path: &str,
) -> String {
    if redirect_target.is_empty() || storage_root.is_empty() {
        return path.to_string();
    }

    if path == storage_root {
        return redirect_target.to_string();
    }

    let prefix = format!("{}/", storage_root);
    if !paths::starts_with(path, &prefix) {
        return path.to_string();
    }

    let relative = &path[prefix.len()..];
    paths::join(redirect_target, relative)
}
