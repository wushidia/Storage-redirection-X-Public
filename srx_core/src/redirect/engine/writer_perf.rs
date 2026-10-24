// 系统代写重定向的性能采样与慢日志输出
use super::{redirect_action_text, should_log_step};
use crate::redirect::router::RedirectDecision;
use std::sync::atomic::{AtomicU64, Ordering};

const WRITER_DEFAULT_REDIRECT_LOG_STEP: u64 = 4096;
const WRITER_REDIRECT_PHASE_SLOW_MS: i64 = 10;
const WRITER_REDIRECT_TOTAL_SLOW_MS: i64 = 100;
static WRITER_DEFAULT_REDIRECT_COUNT: AtomicU64 = AtomicU64::new(0);

pub(super) fn log_writer_default_redirect_summary(
    caller_package: &str,
    caller_uid: i32,
    from_path: &str,
    to_path: &str,
) {
    let count = WRITER_DEFAULT_REDIRECT_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
    if !should_log_step(count, WRITER_DEFAULT_REDIRECT_LOG_STEP) {
        return;
    }
    log::debug!(
        "writer summary default_redirect caller={} uid={} count={} sample_from={} sample_to={}",
        caller_package,
        caller_uid,
        count,
        from_path,
        to_path
    );
}

pub(super) struct WriterRedirectPerf<'a> {
    pub(super) package_name: &'a str,
    pub(super) exit_reason: &'a str,
    pub(super) caller_package: &'a str,
    pub(super) path: &'a str,
    pub(super) normalized_path: &'a str,
    pub(super) reload_ms: i64,
    pub(super) caller_ms: i64,
    pub(super) enable_ms: i64,
    pub(super) mapping_ms: i64,
    pub(super) allow_ms: i64,
    pub(super) fallback_ms: i64,
    pub(super) total_ms: i64,
    pub(super) decision: &'a RedirectDecision,
}

pub(super) fn log_writer_redirect_perf(perf: &WriterRedirectPerf<'_>) {
    if !should_log_writer_redirect_perf(perf) {
        return;
    }
    log::info!(
        "perf redirect mode=writer pkg={} caller={} exit={} action={} path={} normalized={} to={} reload_ms={} caller_ms={} enable_ms={} mapping_ms={} allow_ms={} fallback_ms={} total_ms={}",
        perf.package_name,
        perf.caller_package,
        perf.exit_reason,
        redirect_action_text(perf.decision),
        perf.path,
        perf.normalized_path,
        perf.decision.new_path,
        perf.reload_ms,
        perf.caller_ms,
        perf.enable_ms,
        perf.mapping_ms,
        perf.allow_ms,
        perf.fallback_ms,
        perf.total_ms
    );
}

// 任一阶段或总耗时越过慢阈值即输出
fn should_log_writer_redirect_perf(perf: &WriterRedirectPerf<'_>) -> bool {
    if perf.total_ms >= WRITER_REDIRECT_TOTAL_SLOW_MS {
        return true;
    }
    perf.reload_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.caller_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.enable_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.mapping_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.allow_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
        || perf.fallback_ms >= WRITER_REDIRECT_PHASE_SLOW_MS
}
