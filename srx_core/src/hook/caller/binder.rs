// Binder 线程识别与 IPCThreadState 符号解析，提供调用方 UID/PID
use super::ANDROID_APP_UID_START;
use crate::hook::context;
use libc::{RTLD_LOCAL, RTLD_NOW, c_void, dlopen, dlsym, getpid, getuid, prctl};
use std::cell::Cell;
use std::ffi::CString;
use std::sync::OnceLock;

thread_local! {
    // Binder 线程名终身不变，缓存避免重复 prctl 系统调用
    static BINDER_THREAD_CACHED: Cell<Option<bool>> = const { Cell::new(None) };
}

pub(super) fn is_likely_binder_thread_cached() -> bool {
    BINDER_THREAD_CACHED.with(|cell| {
        if let Some(cached) = cell.get() {
            return cached;
        }
        let result = is_likely_binder_thread();
        cell.set(Some(result));
        result
    })
}

pub(super) fn resolve_caller_uid_by_binder() -> i32 {
    if !is_likely_binder_thread_cached() {
        return -1;
    }

    let symbols = get_caller_binder_symbols();
    let mut caller_uid = -1;
    if let Some(func) = symbols.ndk_get_calling_uid {
        caller_uid = unsafe { func() } as i32;
    }

    if caller_uid < 0
        && let (Some(self_fn), Some(get_uid_fn)) =
            (symbols.binder_self, symbols.binder_get_calling_uid)
    {
        let ipc_state = unsafe { self_fn() };
        if !ipc_state.is_null() {
            caller_uid = unsafe { get_uid_fn(ipc_state) } as i32;
        }
    }

    if caller_uid < ANDROID_APP_UID_START {
        return -1;
    }

    let self_uid = unsafe { getuid() } as i32;
    if caller_uid == self_uid {
        return -1;
    }

    caller_uid
}

pub(super) fn resolve_caller_pid_by_binder() -> i32 {
    if !is_likely_binder_thread_cached() {
        return -1;
    }

    let symbols = get_caller_binder_symbols();
    let mut caller_pid = -1;
    if let Some(func) = symbols.ndk_get_calling_pid {
        caller_pid = unsafe { func() };
    }

    if caller_pid <= 0
        && let (Some(self_fn), Some(get_pid_fn)) =
            (symbols.binder_self, symbols.binder_get_calling_pid)
    {
        let ipc_state = unsafe { self_fn() };
        if !ipc_state.is_null() {
            caller_pid = unsafe { get_pid_fn(ipc_state) };
        }
    }

    if caller_pid <= 0 || caller_pid == unsafe { getpid() } as i32 {
        return -1;
    }

    caller_pid
}

fn is_likely_binder_thread() -> bool {
    let mut name = [0u8; 17];
    let ret = unsafe { prctl(libc::PR_GET_NAME, name.as_mut_ptr() as *mut c_void, 0, 0, 0) };
    if ret != 0 {
        return false;
    }

    let raw = String::from_utf8_lossy(&name);
    raw.starts_with("Binder:")
        || raw.starts_with("binder:")
        || raw.starts_with("HwBinder:")
        || raw.starts_with("hwbinder:")
}

#[derive(Clone, Copy)]
struct CallerBinderSymbols {
    ndk_get_calling_uid: Option<unsafe extern "C" fn() -> u32>,
    ndk_get_calling_pid: Option<unsafe extern "C" fn() -> i32>,
    binder_self: Option<unsafe extern "C" fn() -> *mut c_void>,
    binder_get_calling_uid: Option<unsafe extern "C" fn(*mut c_void) -> u32>,
    binder_get_calling_pid: Option<unsafe extern "C" fn(*mut c_void) -> i32>,
}

fn get_caller_binder_symbols() -> &'static CallerBinderSymbols {
    static SYMBOLS: OnceLock<CallerBinderSymbols> = OnceLock::new();
    SYMBOLS.get_or_init(|| {
        let mut symbols = CallerBinderSymbols {
            ndk_get_calling_uid: None,
            ndk_get_calling_pid: None,
            binder_self: None,
            binder_get_calling_uid: None,
            binder_get_calling_pid: None,
        };

        unsafe {
            if let Some(libbinder_ndk_name) = c_str("libbinder_ndk.so") {
                let libbinder_ndk = dlopen(libbinder_ndk_name.as_ptr(), RTLD_NOW | RTLD_LOCAL);
                if !libbinder_ndk.is_null() {
                    symbols.ndk_get_calling_uid =
                        load_symbol(libbinder_ndk, "AIBinder_getCallingUid");
                    symbols.ndk_get_calling_pid =
                        load_symbol(libbinder_ndk, "AIBinder_getCallingPid");
                }
            }

            if let Some(libbinder_name) = c_str("libbinder.so") {
                let libbinder = dlopen(libbinder_name.as_ptr(), RTLD_NOW | RTLD_LOCAL);
                if !libbinder.is_null() {
                    symbols.binder_self =
                        load_symbol(libbinder, "_ZN7android14IPCThreadState4selfEv");
                    symbols.binder_get_calling_uid =
                        load_symbol(libbinder, "_ZNK7android14IPCThreadState13getCallingUidEv");
                    if symbols.binder_get_calling_uid.is_none() {
                        symbols.binder_get_calling_uid =
                            load_symbol(libbinder, "_ZN7android14IPCThreadState13getCallingUidEv");
                    }
                    symbols.binder_get_calling_pid =
                        load_symbol(libbinder, "_ZNK7android14IPCThreadState13getCallingPidEv");
                    if symbols.binder_get_calling_pid.is_none() {
                        symbols.binder_get_calling_pid =
                            load_symbol(libbinder, "_ZN7android14IPCThreadState13getCallingPidEv");
                    }
                }
            }
        }

        symbols
    })
}

unsafe fn load_symbol<T>(handle: *mut c_void, name: &str) -> Option<T> {
    let c_name = c_str(name)?;
    let symbol = dlsym(handle, c_name.as_ptr());
    if symbol.is_null() {
        None
    } else {
        Some(std::mem::transmute_copy(&symbol))
    }
}

fn c_str(value: &str) -> Option<CString> {
    CString::new(value).ok()
}

// clearCallingIdentity 之前提前快照 UID，供后续 restore 区间回查
pub fn capture_binder_caller_uid_if_available() {
    let uid = resolve_caller_uid_by_binder();
    if uid >= ANDROID_APP_UID_START {
        context::set_binder_saved_caller_uid(uid);
    } else {
        context::clear_binder_saved_caller_uid();
    }
}
