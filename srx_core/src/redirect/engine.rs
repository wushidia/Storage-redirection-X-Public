// 重定向决策引擎：统一处理普通应用与系统代写进程的路径重定向
mod writer_flow;
mod writer_perf;

use super::policy;
use super::router::{PathRouter, RedirectDecision};
use crate::hook::stats::InterceptHub;
use crate::platform::paths;
use std::sync::atomic::{AtomicU64, Ordering};

const REDIRECT_SLOW_MS: i64 = 10;
const REDIRECT_SAMPLE_STEP: u64 = 8192;
static REDIRECT_DECISION_COUNT: AtomicU64 = AtomicU64::new(0);

#[inline]
pub(super) fn should_log_step(count: u64, step: u64) -> bool {
    count == 1 || count.is_multiple_of(step)
}

// 根据进程身份决策路径重定向，系统代写进程使用按调用方映射
pub fn process_redirect_path(hub: &InterceptHub, pathname: &str) -> RedirectDecision {
    let perf_started_ms = paths::monotonic_ms();
    let self_uid = unsafe { libc::getuid() as i32 };
    let package_name = hub.get_package_name();
    let is_shared_uid = policy::is_shared_uid_process(self_uid);
    let is_system_writer_process = policy::is_system_writer_package(&package_name) || is_shared_uid;
    if !is_system_writer_process {
        let decision = PathRouter::instance().process_path(pathname);
        log_redirect_perf(
            "app",
            &package_name,
            "router",
            pathname,
            "",
            perf_started_ms,
            &decision,
        );
        return decision;
    }

    writer_flow::run(
        hub,
        pathname,
        &package_name,
        self_uid,
        is_shared_uid,
        perf_started_ms,
    )
}

pub fn record_redirect_hit(hub: &InterceptHub, op_name: &str, from_path: &str, to_path: &str) {
    log::trace!("{}: {} -> {}", op_name, from_path, to_path);
    hub.increment_total_redirected();
    hub.increment_global_redirect_count();
}

fn log_redirect_perf(
    mode: &str,
    package_name: &str,
    exit_reason: &str,
    path: &str,
    caller_package: &str,
    started_ms: i64,
    decision: &RedirectDecision,
) {
    let total_ms = paths::monotonic_ms().saturating_sub(started_ms);
    if !should_log_redirect_perf(total_ms) {
        return;
    }
    log::info!(
        "perf redirect mode={} pkg={} caller={} exit={} action={} path={} to={} total_ms={}",
        mode,
        package_name,
        caller_package,
        exit_reason,
        redirect_action_text(decision),
        path,
        decision.new_path,
        total_ms
    );
}

fn should_log_redirect_perf(total_ms: i64) -> bool {
    if total_ms >= REDIRECT_SLOW_MS {
        return true;
    }
    let count = REDIRECT_DECISION_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    should_log_step(count, REDIRECT_SAMPLE_STEP)
}

pub(super) fn redirect_action_text(decision: &RedirectDecision) -> &'static str {
    if decision.is_redirect() {
        "redirect"
    } else {
        "allow"
    }
}
