use crate::hook::diagnostic;
use crate::hook::monitor;
use crate::hook::path as path_utils;
use crate::hook::runtime;
use crate::hook::stats::InterceptHub;
use crate::hook::util::c_str_to_string;
use crate::redirect::{process_redirect_path, record_redirect_hit};
use libc::{c_char, c_int, c_void, mode_t};
use std::borrow::Cow;
use std::ffi::CString;

// FuseDaemon 通过 mknod 创建文件节点，必须 hook
pub unsafe extern "C" fn hooked_mknod(
    pathname: *const c_char,
    mode: mode_t,
    dev: libc::dev_t,
) -> c_int {
    let self_ptr = hooked_mknod as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::mknod(pathname, mode, dev),
                |prev| {
                    let f: unsafe extern "C" fn(*const c_char, mode_t, libc::dev_t) -> c_int =
                        std::mem::transmute(prev);
                    f(pathname, mode, dev)
                },
            )
        },
        |hub| {
            hub.increment_mkdir_calls();
            if runtime::should_resolve_caller_context(hub) {
                crate::hook::caller::update_caller_package_for_current_thread(hub);
            }

            handle_mknod_like(hub, "mknod", pathname, mode, |call_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::mknod(call_path, mode, dev),
                    |prev| {
                        let f: unsafe extern "C" fn(*const c_char, mode_t, libc::dev_t) -> c_int =
                            std::mem::transmute(prev);
                        f(call_path, mode, dev)
                    },
                )
            })
        },
    )
}

pub unsafe extern "C" fn hooked_mknodat(
    dirfd: c_int,
    pathname: *const c_char,
    mode: mode_t,
    dev: libc::dev_t,
) -> c_int {
    let self_ptr = hooked_mknodat as *mut c_void;
    runtime::with_hook_guard(
        || {
            runtime::call_prev(
                self_ptr,
                || libc::syscall(libc::SYS_mknodat, dirfd, pathname, mode, dev) as c_int,
                |prev| {
                    let f: unsafe extern "C" fn(
                        c_int,
                        *const c_char,
                        mode_t,
                        libc::dev_t,
                    ) -> c_int = std::mem::transmute(prev);
                    f(dirfd, pathname, mode, dev)
                },
            )
        },
        |hub| {
            hub.increment_mkdir_calls();
            if runtime::should_resolve_caller_context(hub) {
                crate::hook::caller::update_caller_package_for_current_thread(hub);
            }

            handle_mknod_like(hub, "mknodat", pathname, mode, |call_path| {
                runtime::call_prev(
                    self_ptr,
                    || libc::syscall(libc::SYS_mknodat, dirfd, call_path, mode, dev) as c_int,
                    |prev| {
                        let f: unsafe extern "C" fn(
                            c_int,
                            *const c_char,
                            mode_t,
                            libc::dev_t,
                        ) -> c_int = std::mem::transmute(prev);
                        f(dirfd, call_path, mode, dev)
                    },
                )
            })
        },
    )
}

// 统一按文件创建事件记录
fn handle_mknod_like<F>(
    hub: &InterceptHub,
    op_name: &str,
    pathname: *const c_char,
    mode: mode_t,
    call_original: F,
) -> c_int
where
    F: FnOnce(*const c_char) -> c_int,
{
    if pathname.is_null() {
        return call_original(pathname);
    }

    let path_text = unsafe { c_str_to_string(pathname) };
    if path_text.is_empty() || !path_text.starts_with('/') {
        return call_original(pathname);
    }

    diagnostic::log_diag_path_event(hub, op_name, "input", &path_text, mode as i32);

    if hub.is_monitor_only() {
        let result = call_original(pathname);
        let error_no = if result < 0 {
            unsafe { *libc::__errno() }
        } else {
            0
        };
        monitor::record_mkdir_result(hub, op_name, &path_text, result, error_no);
        return result;
    }

    if !path_utils::is_relevant_storage_path(hub, &path_text) {
        diagnostic::record_fast_bypass(op_name, &path_text);
        return call_original(pathname);
    }

    let redirect_result = process_redirect_path(hub, &path_text);
    diagnostic::log_diag_redirect_decision(hub, op_name, &path_text, &redirect_result);

    let mut final_path: Cow<'_, str> = Cow::Borrowed(path_text.as_str());
    let result = if redirect_result.is_redirect() {
        record_redirect_hit(hub, op_name, &path_text, &redirect_result.new_path);
        runtime::ensure_redirect_parent_dirs(&redirect_result.new_path, mode);
        if let Ok(c_path) = CString::new(redirect_result.new_path.as_str()) {
            final_path = Cow::Owned(redirect_result.new_path);
            call_original(c_path.as_ptr())
        } else {
            call_original(pathname)
        }
    } else {
        call_original(pathname)
    };
    let error_no = if result < 0 {
        unsafe { *libc::__errno() }
    } else {
        0
    };
    monitor::record_mkdir_result(hub, op_name, final_path.as_ref(), result, error_no);
    result
}
