use super::SQLITE_SHM_MIN_SIZE;
use super::access::should_allow_private_owner_sqlite_access_for_caller;
use super::caller::{
    current_fuse_caller_uid, current_thread_caller_package_for_uid,
    should_force_private_owner_sqlite_for_owner,
};
use super::recent::has_recent_private_owner_sqlite_access_for_caller;
use super::resolve::{
    is_sqlite_shm_sidecar_path, resolve_private_owner_sqlite_path, storage_to_data_media_path,
};
use std::ffi::CString;

pub fn prepare_private_owner_sqlite_sidecar(path: &str) -> bool {
    let Some((normalized_path, owner_package, user_id)) = resolve_private_owner_sqlite_path(path)
    else {
        return false;
    };
    if !is_sqlite_shm_sidecar_path(&normalized_path) {
        return false;
    }
    if !should_prepare_private_owner_sqlite_sidecar(&normalized_path, &owner_package, user_id) {
        return false;
    }

    let backend_path = storage_to_data_media_path(&normalized_path);
    if backend_path == normalized_path || !backend_path.starts_with("/data/media/") {
        return false;
    }

    prepare_sqlite_shm_backend(&normalized_path, &backend_path)
}

fn should_prepare_private_owner_sqlite_sidecar(
    path: &str,
    owner_package: &str,
    user_id: i32,
) -> bool {
    if should_force_private_owner_sqlite_for_owner(owner_package, user_id) {
        return true;
    }

    let caller_uid = current_fuse_caller_uid();
    let caller_package = current_thread_caller_package_for_uid(caller_uid);
    should_allow_private_owner_sqlite_access_for_caller(path, caller_uid, &caller_package)
        || has_recent_private_owner_sqlite_access_for_caller(path, caller_uid, &caller_package)
}

fn prepare_sqlite_shm_backend(storage_path: &str, backend_path: &str) -> bool {
    if !ensure_backend_parent_dir(backend_path, storage_path) {
        return false;
    }

    let Ok(c_path) = CString::new(backend_path) else {
        return false;
    };

    let fd = unsafe { libc::open(c_path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    if fd < 0 {
        log::warn!(
            "prepare sqlite shm backend open failed errno={} storage={} backend={}",
            current_errno(),
            storage_path,
            backend_path
        );
        return false;
    }

    let size = fd_size(fd);
    if size >= SQLITE_SHM_MIN_SIZE {
        unsafe {
            libc::close(fd);
        }
        return true;
    }

    if size > 0 {
        let truncate_zero = unsafe { libc::ftruncate(fd, 0) };
        if truncate_zero != 0 {
            log::warn!(
                "prepare sqlite shm backend reset failed errno={} size={} storage={} backend={}",
                current_errno(),
                size,
                storage_path,
                backend_path
            );
            unsafe {
                libc::close(fd);
            }
            return false;
        }
    }

    let result = unsafe { libc::ftruncate(fd, SQLITE_SHM_MIN_SIZE) };
    let errno = current_errno();
    let final_size = fd_size(fd);
    unsafe {
        libc::close(fd);
    }

    if result == 0 {
        log::info!(
            "prepare sqlite shm backend ok size={} final_size={} storage={} backend={}",
            size,
            final_size,
            storage_path,
            backend_path
        );
        true
    } else {
        log::warn!(
            "prepare sqlite shm backend failed errno={} size={} final_size={} storage={} backend={}",
            errno,
            size,
            final_size,
            storage_path,
            backend_path
        );
        false
    }
}

pub fn ensure_backend_parent_dir(backend_path: &str, storage_path: &str) -> bool {
    let Some(parent) = std::path::Path::new(backend_path).parent() else {
        return true;
    };
    if parent.exists() {
        return true;
    }

    match std::fs::create_dir_all(parent) {
        Ok(()) => {
            log::info!(
                "ensure sqlite shm backend parent dir ok storage={} backend_parent={}",
                storage_path,
                parent.display()
            );
            true
        }
        Err(err) => {
            log::warn!(
                "ensure sqlite shm backend parent dir failed storage={} backend_parent={} err={}",
                storage_path,
                parent.display(),
                err
            );
            false
        }
    }
}

fn fd_size(fd: libc::c_int) -> libc::off_t {
    let mut statbuf = std::mem::MaybeUninit::<libc::stat>::uninit();
    let result = unsafe { libc::fstat(fd, statbuf.as_mut_ptr()) };
    if result != 0 {
        return -1;
    }
    let statbuf = unsafe { statbuf.assume_init() };
    statbuf.st_size as libc::off_t
}

fn current_errno() -> i32 {
    unsafe { *libc::__errno() }
}
