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
        let mut event = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        // specialize 后无权修改 zygote socket 的选项，按同一截止时间等待可读。
        let wait_ms = remaining.as_millis().clamp(1, i32::MAX as u128) as i32;
        let ready = unsafe { libc::poll(&mut event, 1, wait_ms) };
        if ready == 0 {
            continue;
        }
        if ready < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        let count = unsafe {
            libc::recv(
                fd,
                bytes[received..].as_mut_ptr().cast(),
                bytes.len() - received,
                libc::MSG_DONTWAIT,
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
            if matches!(
                error.kind(),
                io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock
            ) {
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
