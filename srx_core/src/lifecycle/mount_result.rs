use std::io;
use std::time::{Duration, Instant};

pub(super) fn read_result(fd: i32, timeout: Duration) -> io::Result<bool> {
    let deadline = Instant::now() + timeout;
    let mut bytes = [0u8; 4];
    let mut received = 0;
    while received < bytes.len() {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "mount result timeout",
            ));
        }
        let timeout = libc::timeval {
            tv_sec: remaining.as_secs() as libc::time_t,
            tv_usec: remaining.subsec_micros().max(1) as libc::suseconds_t,
        };
        if unsafe {
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_RCVTIMEO,
                &timeout as *const _ as *const libc::c_void,
                std::mem::size_of_val(&timeout) as libc::socklen_t,
            )
        } != 0
        {
            return Err(io::Error::last_os_error());
        }
        let count = unsafe {
            libc::recv(
                fd,
                bytes[received..].as_mut_ptr().cast(),
                bytes.len() - received,
                0,
            )
        };
        if count == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "incomplete mount result",
            ));
        }
        if count < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        received += count as usize;
    }
    Ok(i32::from_ne_bytes(bytes) == 1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixStream;

    #[test]
    fn fragmented_reply_is_a_complete_mount_barrier() {
        let (reader, mut writer) = UnixStream::pair().expect("socket pair");
        let sender = std::thread::spawn(move || {
            let bytes = 1i32.to_ne_bytes();
            writer.write_all(&bytes[..2]).expect("first fragment");
            std::thread::sleep(Duration::from_millis(10));
            writer.write_all(&bytes[2..]).expect("last fragment");
        });
        assert!(read_result(reader.as_raw_fd(), Duration::from_secs(1)).expect("mount status"));
        sender.join().expect("sender");
    }

    #[test]
    fn missing_and_failed_replies_are_not_success() {
        let (reader, mut writer) = UnixStream::pair().expect("socket pair");
        writer.write_all(&0i32.to_ne_bytes()).expect("failed reply");
        assert!(!read_result(reader.as_raw_fd(), Duration::from_secs(1)).expect("mount status"));
        writer.write_all(&[1]).expect("short reply");
        drop(writer);
        assert_eq!(
            read_result(reader.as_raw_fd(), Duration::from_secs(1))
                .expect_err("EOF")
                .kind(),
            io::ErrorKind::UnexpectedEof
        );
        let (reader, _writer) = UnixStream::pair().expect("socket pair");
        let error =
            read_result(reader.as_raw_fd(), Duration::from_millis(10)).expect_err("timeout");
        assert!(matches!(
            error.kind(),
            io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
        ));
    }
}
