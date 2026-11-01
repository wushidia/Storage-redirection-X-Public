// 在应用私有命名空间提供挂载前视图；不冻结 maps，也不改写其他 PID 的读取。
use std::ffi::CString;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::fs::{MetadataExt, PermissionsExt};

const RUNTIME_DIR: &str = "/dev/srx_runtime";

pub(crate) struct ProcMountViews {
    pid: i32,
    snapshots: Vec<(&'static str, File)>,
}

impl ProcMountViews {
    pub(crate) fn capture(pid: i32) -> io::Result<Self> {
        let namespace = fs::read_link("/proc/self/ns/mnt")?;
        let parent = unsafe { libc::getppid() };
        if namespace == fs::read_link(format!("/proc/{parent}/ns/mnt"))? {
            return Err(io::Error::other("target mount namespace is not isolated"));
        }
        fs::create_dir_all(RUNTIME_DIR)?;
        fs::set_permissions(RUNTIME_DIR, fs::Permissions::from_mode(0o700))?;
        let snapshots = ["mountinfo", "mounts", "mountstats"]
            .into_iter()
            .map(|name| capture_snapshot(pid, name).map(|file| (name, file)))
            .collect::<io::Result<Vec<_>>>()?;
        Ok(Self { pid, snapshots })
    }

    pub(crate) fn install(&self) -> io::Result<()> {
        // /proc 的传播属性与 /storage 独立，覆盖前必须阻断向其他命名空间传播。
        mount_file(None, c"/proc", libc::MS_PRIVATE | libc::MS_REC)?;
        let mut mounted: Vec<CString> = Vec::new();
        for (name, file) in &self.snapshots {
            let source = CString::new(format!("/proc/self/fd/{}", file.as_raw_fd()))?;
            for target in [
                format!("/proc/{}/{name}", self.pid),
                format!("/proc/{}/task/{}/{name}", self.pid, self.pid),
            ] {
                let target = CString::new(target)?;
                if let Err(error) = install_snapshot(&source, &target) {
                    for previous in mounted.iter().rev() {
                        unsafe { libc::umount2(previous.as_ptr(), libc::MNT_DETACH) };
                    }
                    return Err(error);
                }
                mounted.push(target);
            }
        }
        Ok(())
    }
}

fn capture_snapshot(pid: i32, name: &str) -> io::Result<File> {
    let mut original = File::open(format!("/proc/{pid}/{name}"))?;
    let metadata = original.metadata()?;
    let mut content = Vec::new();
    original.read_to_end(&mut content)?;
    let mount_column = if name == "mounts" { 1 } else { 4 };
    let mut snapshot = create_snapshot_file()?;
    for line in content.split_inclusive(|byte| *byte == b'\n') {
        let mountpoint = line
            .split(|byte| byte.is_ascii_whitespace())
            .filter(|part| !part.is_empty())
            .nth(mount_column);
        if mountpoint
            .is_some_and(|path| path == b"/dev/srx_config" || path.starts_with(b"/dev/srx_config/"))
        {
            continue;
        }
        snapshot.write_all(line)?;
    }
    let fd = snapshot.as_raw_fd();
    if unsafe { libc::fchown(fd, metadata.uid(), metadata.gid()) } != 0
        || unsafe { libc::fchmod(fd, metadata.mode() & 0o777) } != 0
    {
        return Err(io::Error::last_os_error());
    }
    copy_selinux_context(original.as_raw_fd(), fd)?;
    Ok(snapshot)
}

fn create_snapshot_file() -> io::Result<File> {
    // bind mount 要求来源属于当前命名空间，不能使用 memfd 的内部 shmem 挂载。
    let directory = CString::new(RUNTIME_DIR)?;
    let fd = unsafe {
        libc::open(
            directory.as_ptr(),
            libc::O_TMPFILE | libc::O_RDWR | libc::O_CLOEXEC,
            0o600 as libc::mode_t,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { File::from_raw_fd(fd) })
}

fn copy_selinux_context(source: i32, target: i32) -> io::Result<()> {
    let mut context = [0u8; 256];
    let size = unsafe {
        libc::fgetxattr(
            source,
            c"security.selinux".as_ptr(),
            context.as_mut_ptr().cast(),
            context.len(),
        )
    };
    if size < 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe {
        libc::fsetxattr(
            target,
            c"security.selinux".as_ptr(),
            context.as_ptr().cast(),
            size as usize,
            0,
        )
    } != 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn install_snapshot(source: &std::ffi::CStr, target: &std::ffi::CStr) -> io::Result<()> {
    mount_file(Some(source), target, libc::MS_BIND)?;
    let flags = libc::MS_BIND
        | libc::MS_REMOUNT
        | libc::MS_RDONLY
        | libc::MS_NOSUID
        | libc::MS_NODEV
        | libc::MS_NOEXEC;
    if let Err(error) = mount_file(None, target, flags) {
        unsafe { libc::umount2(target.as_ptr(), libc::MNT_DETACH) };
        return Err(error);
    }
    Ok(())
}

fn mount_file(
    source: Option<&std::ffi::CStr>,
    target: &std::ffi::CStr,
    flags: libc::c_ulong,
) -> io::Result<()> {
    if unsafe {
        libc::mount(
            source.map_or(std::ptr::null(), |value| value.as_ptr()),
            target.as_ptr(),
            std::ptr::null(),
            flags,
            std::ptr::null(),
        )
    } != 0
    {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}
