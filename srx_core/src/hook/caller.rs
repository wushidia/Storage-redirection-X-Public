// 多级回退解析调用方：Binder UID/PID、FUSE UID/PID、clearCallingIdentity 快照
mod binder;
mod package;

pub use binder::capture_binder_caller_uid_if_available;

use super::context;
use super::stats::InterceptHub;
use crate::monitor::AuditTrail;
use binder::{
    is_likely_binder_thread_cached, resolve_caller_pid_by_binder, resolve_caller_uid_by_binder,
};
use libc::{getpid, getuid};
use package::{resolve_caller_package_by_pid, resolve_caller_package_by_uid};
use std::sync::atomic::{AtomicU64, Ordering};

pub(super) const ANDROID_APP_UID_START: i32 = 10000;
const FUSE_CALLER_MAX_AGE_MS: i64 = 1500;
const BINDER_SAVED_CALLER_MAX_AGE_MS: i64 = 1500;
const BINDER_SAVED_CALLER_HARD_LIMIT_MS: i64 = 30_000;
const CALLER_LOG_SAMPLE_STEP: u64 = 1024;
static CALLER_FUSE_STALE_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_SAVED_UID_STALE_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_FALLBACK_HIT_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_UNRESOLVED_COUNT: AtomicU64 = AtomicU64::new(0);
static CALLER_SIGNAL_SKIP_COUNT: AtomicU64 = AtomicU64::new(0);

// FUSE / Binder saved UID / Binder 线程三者皆无时调用方解析必定失败，可直接跳过
fn has_caller_signal(hub: &InterceptHub) -> bool {
    let fuse_uid = hub.get_fuse_caller_uid();
    if fuse_uid >= ANDROID_APP_UID_START {
        let fuse_age = context::get_fuse_caller_uid_age_ms();
        if (0..=FUSE_CALLER_MAX_AGE_MS).contains(&fuse_age) {
            return true;
        }
    }

    let saved_uid = context::get_binder_saved_caller_uid();
    if saved_uid >= ANDROID_APP_UID_START {
        let max_age = if context::is_binder_identity_cleared() {
            BINDER_SAVED_CALLER_HARD_LIMIT_MS
        } else {
            BINDER_SAVED_CALLER_MAX_AGE_MS
        };
        let saved_age = context::get_binder_saved_caller_uid_age_ms();
        if (0..=max_age).contains(&saved_age) {
            return true;
        }
    }

    is_likely_binder_thread_cached()
}

// 通过 Binder / FUSE / PID 多级回退解析当前线程调用方
pub fn update_caller_package_for_current_thread(hub: &InterceptHub) {
    if !has_caller_signal(hub) {
        let count = CALLER_SIGNAL_SKIP_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
        if should_log_sample(count) {
            log::debug!(
                "caller signal skip proc={} n={}",
                hub.get_package_name(),
                count
            );
        }
        hub.clear_current_caller();
        return;
    }

    let binder_uid = resolve_caller_uid_by_binder();
    let mut caller_uid = binder_uid;
    let mut caller_source = "binder_uid";
    let mut fuse_uid = -1;
    let mut fuse_age_ms = -1;

    if caller_uid < 0 {
        fuse_uid = hub.get_fuse_caller_uid();
        fuse_age_ms = context::get_fuse_caller_uid_age_ms();
        let self_uid = unsafe { getuid() } as i32;
        if fuse_uid >= ANDROID_APP_UID_START && (0..=FUSE_CALLER_MAX_AGE_MS).contains(&fuse_age_ms)
        {
            if fuse_uid != self_uid {
                caller_uid = fuse_uid;
                caller_source = "fuse_uid";
            } else {
                // 共享 UID：UID 相同但 PID 不同说明来自同组的另一个进程
                let fuse_pid = context::get_fuse_caller_pid();
                let self_pid = unsafe { getpid() } as i32;
                if fuse_pid > 0 && fuse_pid != self_pid {
                    let pkg = resolve_caller_package_by_pid(fuse_pid);
                    if !pkg.is_empty() {
                        hub.set_current_caller_uid(fuse_uid);
                        hub.set_current_caller_package(&pkg);
                        AuditTrail::instance().update_caller_package(&pkg);
                        let count = CALLER_FALLBACK_HIT_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
                        if should_log_sample(count) {
                            log::debug!(
                                "caller fallback src=fuse_pid pkg={} uid={} fuse_pid={} n={}",
                                pkg,
                                fuse_uid,
                                fuse_pid,
                                count
                            );
                        }
                        context::clear_fuse_caller_uid();
                        return;
                    }
                }
            }
        } else if fuse_uid >= ANDROID_APP_UID_START && fuse_age_ms > FUSE_CALLER_MAX_AGE_MS {
            let count = CALLER_FUSE_STALE_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            if should_log_sample(count) {
                log::debug!(
                    "caller skip stale fuse uid={} age_ms={} n={}",
                    fuse_uid,
                    fuse_age_ms,
                    count
                );
            }
        }
        context::clear_fuse_caller_uid();
    }

    let mut package_name = String::new();
    if caller_uid >= ANDROID_APP_UID_START {
        package_name = resolve_caller_package_by_uid(caller_uid, hub);
    }

    let mut caller_pid = -1;
    let saved_uid = context::get_binder_saved_caller_uid();
    let saved_uid_age_ms = context::get_binder_saved_caller_uid_age_ms();
    let identity_cleared = context::is_binder_identity_cleared();
    if package_name.is_empty() {
        caller_pid = resolve_caller_pid_by_binder();
        if caller_pid > 0 {
            package_name = resolve_caller_package_by_pid(caller_pid);
            if !package_name.is_empty() {
                caller_source = "binder_pid";
            }
        }
        // clearCallingIdentity 前保存的调用方 UID 作为末级回退
        // 处于 clear/restore 区间时直接使用，否则走时间窗口兜底
        if package_name.is_empty() && saved_uid >= ANDROID_APP_UID_START {
            let saved_uid_valid = if identity_cleared {
                (0..=BINDER_SAVED_CALLER_HARD_LIMIT_MS).contains(&saved_uid_age_ms)
            } else {
                (0..=BINDER_SAVED_CALLER_MAX_AGE_MS).contains(&saved_uid_age_ms)
            };
            if saved_uid_valid {
                caller_uid = saved_uid;
                package_name = resolve_caller_package_by_uid(saved_uid, hub);
                if !package_name.is_empty() {
                    caller_source = "saved_uid";
                }
            } else {
                context::clear_binder_saved_caller_uid();
                context::set_binder_identity_cleared(false);
                if saved_uid_age_ms > BINDER_SAVED_CALLER_MAX_AGE_MS {
                    let count = CALLER_SAVED_UID_STALE_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
                    if should_log_sample(count) {
                        log::debug!(
                            "caller skip stale saved_uid uid={} age_ms={} cleared={} n={}",
                            saved_uid,
                            saved_uid_age_ms,
                            identity_cleared,
                            count
                        );
                    }
                }
            }
        }
        if package_name.is_empty() {
            caller_uid = -1;
        }
    }

    if !package_name.is_empty() {
        hub.set_current_caller_uid(caller_uid);
        hub.set_current_caller_package(&package_name);
        AuditTrail::instance().update_caller_package(&package_name);
        if caller_source != "binder_uid" {
            let count = CALLER_FALLBACK_HIT_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            if should_log_sample(count) {
                log::debug!(
                    "caller fallback src={} pkg={} uid={} binder_uid={} fuse_uid={} fuse_age_ms={} cleared={} n={}",
                    caller_source,
                    package_name,
                    caller_uid,
                    binder_uid,
                    fuse_uid,
                    fuse_age_ms,
                    identity_cleared,
                    count
                );
            }
        }
        return;
    }

    let count = CALLER_UNRESOLVED_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if should_log_sample(count) {
        log::warn!(
            "caller unresolved proc={} binder_uid={} binder_pid={} saved_uid={} saved_age_ms={} fuse_uid={} fuse_age_ms={} n={}",
            hub.get_package_name(),
            binder_uid,
            caller_pid,
            saved_uid,
            saved_uid_age_ms,
            fuse_uid,
            fuse_age_ms,
            count
        );
    }
    hub.clear_current_caller();
}

#[inline]
fn should_log_sample(count: u64) -> bool {
    count == 1 || count.is_multiple_of(CALLER_LOG_SAMPLE_STEP)
}
