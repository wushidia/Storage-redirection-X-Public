// 应用 specialize 后流程：读取挂载结果并安装 PLT Hook
use super::RuntimeFlow;
use crate::hook::InterceptHub;
use crate::platform::paths::monotonic_ms;
use crate::platform::unique_fd::UniqueFd;
use crate::platform::{self, anti_detect};
use crate::zygisk::abi;
use libc::c_int;
use std::sync::atomic::{AtomicBool, Ordering};

static PLT_HOOK_INSTALLED: AtomicBool = AtomicBool::new(false);
// 覆盖命名空间等待 5 秒、挂载 5 秒及终止宽限 1 秒，预留回包时间。
const MOUNT_RESULT_TIMEOUT_SEC: u64 = 12;
const POST_SPECIALIZE_SLOW_MS: i64 = 20;

impl RuntimeFlow {
    pub fn post_app_specialize(&mut self, _args: *const abi::AppSpecializeArgs) {
        let mount_socket = UniqueFd::new(std::mem::replace(&mut self.companion_fd, -1));
        let perf_started_ms = monotonic_ms();
        if self.should_install_fuse_fixer {
            crate::hook::install_fuse_fixer_hook();
        }

        if self.should_skip_post_work {
            log_post_perf(self, "skip", 0, 0, 0, perf_started_ms);
            return;
        }

        if !self.should_redirect && !self.should_monitor {
            let anti_ms = relocate_retained_module(self);
            self.request_dlclose();
            log_post_perf(self, "bypass", 0, 0, anti_ms, perf_started_ms);
            return;
        }

        let mut mount_wait_ms = 0;
        if self.should_redirect && !self.is_system_writer_hook_redirect {
            if platform::is_isolated_uid(self.app_uid) {
                self.is_mount_applied = false;
                log::info!(
                    "isolated uid skip mount wait uid={} pid={}",
                    self.app_uid,
                    self.app_pid
                );
            } else {
                let mount_started_ms = monotonic_ms();
                read_mount_result(
                    mount_socket.get(),
                    self.is_mount_request_sent,
                    &mut self.is_mount_applied,
                );
                mount_wait_ms = monotonic_ms().saturating_sub(mount_started_ms);
            }
        } else if self.should_redirect && self.is_system_writer_hook_redirect {
            self.is_mount_applied = false;
            log::info!("writer per-caller hook map (skip mount wait)");
        }

        let hook_started_ms = monotonic_ms();
        let is_redirect_via_hook = self.should_redirect && self.is_system_writer_hook_redirect;
        let has_native_monitor = self.should_monitor && platform::android_api_level() < 30;
        self.should_keep_module_loaded |= is_redirect_via_hook || has_native_monitor;
        install_plt_hook(
            &self.package_name,
            self.should_monitor,
            is_redirect_via_hook,
        );
        let hook_ms = monotonic_ms().saturating_sub(hook_started_ms);

        self.request_dlclose();
        let anti_ms = relocate_retained_module(self);
        log_post_perf(
            self,
            "done",
            mount_wait_ms,
            hook_ms,
            anti_ms,
            perf_started_ms,
        );
    }
}

fn relocate_retained_module(flow: &RuntimeFlow) -> i64 {
    if !flow.should_keep_module_loaded {
        return 0;
    }
    let started_ms = monotonic_ms();
    let relocated = anti_detect::relocate_self();
    if relocated > 0 {
        log::info!("self relocated segments n={}", relocated);
    }
    monotonic_ms().saturating_sub(started_ms)
}

fn read_mount_result(fd: c_int, sent: bool, applied: &mut bool) {
    *applied = false;
    if !sent || fd < 0 {
        return;
    }
    match super::mount_result::read_result(
        fd,
        std::time::Duration::from_secs(MOUNT_RESULT_TIMEOUT_SEC),
    ) {
        Ok(result) => {
            *applied = result;
            log::info!("mount result applied={}", result);
        }
        Err(error) => log::warn!("mount result unavailable fd={} error={}", fd, error),
    }
}

fn log_post_perf(
    flow: &RuntimeFlow,
    exit_reason: &str,
    mount_wait_ms: i64,
    hook_ms: i64,
    anti_ms: i64,
    started_ms: i64,
) {
    let total_ms = monotonic_ms().saturating_sub(started_ms);
    if total_ms < POST_SPECIALIZE_SLOW_MS && !flow.should_redirect && !flow.should_monitor {
        return;
    }
    log::info!(
        "perf post pkg={} pid={} exit={} redirect={} monitor={} hook_redirect={} mount_sent={} mount_applied={} mount_wait_ms={} hook_ms={} anti_ms={} total_ms={}",
        flow.package_name,
        flow.app_pid,
        exit_reason,
        flow.should_redirect,
        flow.should_monitor,
        flow.is_system_writer_hook_redirect,
        flow.is_mount_request_sent,
        flow.is_mount_applied,
        mount_wait_ms,
        hook_ms,
        anti_ms,
        total_ms
    );
}

fn install_plt_hook(package_name: &str, should_monitor: bool, is_redirect_via_hook: bool) {
    let should_monitor = should_monitor && platform::android_api_level() < 30;
    let is_monitor_only = !is_redirect_via_hook;
    let should_install = should_monitor || is_redirect_via_hook;
    if !should_install {
        log::info!("plt hook skip");
        return;
    }

    if PLT_HOOK_INSTALLED.swap(true, Ordering::AcqRel) {
        log::info!("plt hook already installed");
        return;
    }

    log::info!(
        "plt hook install redirect={} monitor={}",
        !is_monitor_only,
        should_monitor
    );

    let hub = InterceptHub::instance();
    hub.init(package_name, is_monitor_only, should_monitor);
    if hub.install() {
        log::info!("plt hook ok");
    } else {
        PLT_HOOK_INSTALLED.store(false, Ordering::Release);
        log::warn!("plt hook failed");
    }
}
