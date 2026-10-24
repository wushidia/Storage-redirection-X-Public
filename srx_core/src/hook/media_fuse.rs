mod access;
mod caller;
mod recent;
mod resolve;
mod sidecar;

const ANDROID_APP_UID_START: i32 = 10000;
const SQLITE_SHM_MIN_SIZE: libc::off_t = 32 * 1024;

pub use access::{
    adjusted_private_owner_sqlite_truncate_length, should_allow_configured_real_path_access,
    should_allow_private_owner_sqlite_access, should_allow_private_owner_sqlite_access_for_caller,
    should_allow_private_owner_sqlite_owner_backend,
    should_force_userspace_for_private_owner_sqlite_path,
};
pub use recent::has_recent_private_owner_sqlite_access;
pub(crate) use sidecar::ensure_backend_parent_dir;
pub use sidecar::prepare_private_owner_sqlite_sidecar;
