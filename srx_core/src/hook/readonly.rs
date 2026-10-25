use super::stats::InterceptHub;
use crate::config::{SettingsHub, UserProfile};
use crate::domain::{matches_policy_paths, resolve_mapping_chain};
use crate::platform::{self, paths};
use std::sync::Arc;

fn active_profile(hub: &InterceptHub) -> Option<(i32, Arc<UserProfile>)> {
    if !SettingsHub::instance().has_read_only_rules() {
        return None;
    }
    let package = hub.get_current_caller_package();
    let uid = hub.get_current_caller_uid();
    if package.is_empty() || uid < 10000 {
        return None;
    }
    let profile = crate::redirect::caller_profile(&package, uid)?;
    if profile.read_only_paths.is_empty() {
        return None;
    }
    Some((uid, profile))
}

fn protected_path(uid: i32, profile: &UserProfile, path: &str) -> bool {
    let path = paths::resolve_user_path(&paths::normalize(path), platform::user_id_from_uid(uid));
    let denied = matches_policy_paths(&profile.read_only_paths, &path)
        || resolve_mapping_chain(&path, &profile.path_mappings)
            .ok()
            .flatten()
            .is_some_and(|mapped| matches_policy_paths(&profile.read_only_paths, &mapped));
    if denied {
        unsafe {
            *libc::__errno() = libc::EROFS;
        }
    }
    denied
}

pub fn denies_write(hub: &InterceptHub, path: &str) -> bool {
    let Some((uid, profile)) = active_profile(hub) else {
        return false;
    };
    protected_path(uid, &profile, path)
}

pub fn denies_at(hub: &InterceptHub, dirfd: i32, path: *const libc::c_char) -> bool {
    if path.is_null() {
        return false;
    }
    let Some((uid, profile)) = active_profile(hub) else {
        return false;
    };
    let text = unsafe { super::util::c_str_to_string(path) };
    let absolute = if text.starts_with('/') {
        text
    } else {
        super::path::resolve_path_for_dirfd(dirfd, &text)
    };
    protected_path(uid, &profile, &absolute)
}

pub fn denies_fd(hub: &InterceptHub, fd: i32) -> bool {
    let Some((uid, profile)) = active_profile(hub) else {
        return false;
    };
    protected_path(uid, &profile, &super::path::resolve_dirfd_path(fd))
}
