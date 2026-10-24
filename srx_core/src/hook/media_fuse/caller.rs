use super::ANDROID_APP_UID_START;
use crate::config::SettingsHub;
use crate::hook::context;
use crate::hook::stats::InterceptHub;

const FUSE_CALLER_MAX_AGE_MS: i64 = 1500;

pub(super) fn should_allow_private_owner_sqlite_for_owner(
    owner_package: &str,
    user_id: i32,
) -> bool {
    should_force_private_owner_sqlite_for_owner(owner_package, user_id)
}

pub(super) fn should_force_private_owner_sqlite_for_owner(
    owner_package: &str,
    user_id: i32,
) -> bool {
    SettingsHub::instance().is_user_profile_enabled_in_memory(owner_package, user_id)
}

pub(super) fn current_fuse_caller_uid() -> i32 {
    let caller_uid = InterceptHub::instance().get_fuse_caller_uid();
    if caller_uid < ANDROID_APP_UID_START {
        return -1;
    }

    let age_ms = context::get_fuse_caller_uid_age_ms();
    if (0..=FUSE_CALLER_MAX_AGE_MS).contains(&age_ms) {
        caller_uid
    } else {
        -1
    }
}

pub(super) fn current_thread_caller_package_for_uid(caller_uid: i32) -> String {
    if caller_uid < ANDROID_APP_UID_START {
        return String::new();
    }

    let hub = InterceptHub::instance();
    if hub.get_current_caller_uid() == caller_uid {
        hub.get_current_caller_package()
    } else {
        String::new()
    }
}
