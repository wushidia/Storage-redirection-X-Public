// 路由决策的慢日志输出与采样步进
use super::RedirectDecision;
use crate::platform::paths;

const ROUTER_SLOW_MS: i64 = 5;

// 首次与每 step 次命中才打样本，避免热路径刷满 running.log
#[inline]
pub(super) fn should_log_step(count: u64, step: u64) -> bool {
    count == 1 || count.is_multiple_of(step)
}

#[allow(clippy::too_many_arguments)]
pub(super) fn log_router_perf(
    exit_reason: &str,
    original_path: &str,
    normalized_path: &str,
    resolved_path: &str,
    map_count: usize,
    allow_count: usize,
    excl_count: usize,
    normalize_ms: i64,
    lock_ms: i64,
    map_ms: i64,
    allow_ms: i64,
    started_ms: i64,
    decision: &RedirectDecision,
) {
    let total_ms = paths::monotonic_ms().saturating_sub(started_ms);
    if total_ms < ROUTER_SLOW_MS {
        return;
    }
    log::info!(
        "perf router exit={} action={} map={} allow={} excl={} path={} normalized={} resolved={} to={} normalize_ms={} lock_ms={} map_ms={} allow_ms={} total_ms={}",
        exit_reason,
        if decision.is_redirect() {
            "redirect"
        } else {
            "allow"
        },
        map_count,
        allow_count,
        excl_count,
        original_path,
        normalized_path,
        resolved_path,
        decision.new_path,
        normalize_ms,
        lock_ms,
        map_ms,
        allow_ms,
        total_ms
    );
}
