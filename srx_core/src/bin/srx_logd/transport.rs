// 抽象命名空间 Unix DGRAM socket：守护端绑定与客户端发送
use libc::{
    AF_UNIX, SO_RCVBUF, SOCK_CLOEXEC, SOCK_DGRAM, SOCK_NONBLOCK, SOL_SOCKET, bind, c_void, close,
    sendto, setsockopt, sockaddr, sockaddr_un, socket,
};
use std::io;
use std::mem;

const SOCKET_NAME: &[u8] = b"storage.redirect.x.logd";
const SOCKET_RECV_BUFFER_BYTES: libc::c_int = 512 * 1024;

pub(crate) fn bind_log_socket() -> io::Result<i32> {
    let fd = unsafe { socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    configure_socket_recv_buffer(fd);

    let mut addr: sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = AF_UNIX as _;
    if SOCKET_NAME.len() + 1 > addr.sun_path.len() {
        unsafe {
            close(fd);
        }
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "socket name too long",
        ));
    }
    addr.sun_path[0] = 0;
    for (index, byte) in SOCKET_NAME.iter().enumerate() {
        addr.sun_path[index + 1] = *byte as _;
    }

    let len = sockaddr_un_len(SOCKET_NAME.len() + 1);
    let ret = unsafe { bind(fd, &addr as *const _ as *const sockaddr, len) };
    if ret != 0 {
        let error = io::Error::last_os_error();
        unsafe {
            close(fd);
        }
        return Err(error);
    }
    Ok(fd)
}

fn configure_socket_recv_buffer(fd: i32) {
    let recv_buffer = SOCKET_RECV_BUFFER_BYTES;
    unsafe {
        let _ = setsockopt(
            fd,
            SOL_SOCKET,
            SO_RCVBUF,
            &recv_buffer as *const _ as *const _,
            mem::size_of_val(&recv_buffer) as libc::socklen_t,
        );
    }
}

fn sockaddr_un_len(path_len: usize) -> libc::socklen_t {
    (mem::size_of::<libc::sa_family_t>() + path_len) as libc::socklen_t
}

pub(crate) struct ClientSocket {
    fd: i32,
    addr: sockaddr_un,
    addr_len: libc::socklen_t,
}

impl ClientSocket {
    pub(crate) fn new() -> Option<Self> {
        let fd = unsafe { socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0) };
        if fd < 0 {
            return None;
        }

        let mut addr: sockaddr_un = unsafe { mem::zeroed() };
        addr.sun_family = AF_UNIX as _;
        if SOCKET_NAME.len() + 1 > addr.sun_path.len() {
            unsafe {
                close(fd);
            }
            return None;
        }
        addr.sun_path[0] = 0;
        for (index, byte) in SOCKET_NAME.iter().enumerate() {
            addr.sun_path[index + 1] = *byte as _;
        }

        Some(Self {
            fd,
            addr,
            addr_len: sockaddr_un_len(SOCKET_NAME.len() + 1),
        })
    }

    pub(crate) fn send(&self, level: char, tag: &str, message: &str) -> io::Result<()> {
        let message = sanitize_transport_field(message);
        if tag.is_empty() || message.is_empty() {
            return Ok(());
        }

        let packet = format!("{level}\t{tag}\t{message}");
        let ret = unsafe {
            sendto(
                self.fd,
                packet.as_ptr() as *const c_void,
                packet.len(),
                0,
                &self.addr as *const _ as *const sockaddr,
                self.addr_len,
            )
        };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
}

impl Drop for ClientSocket {
    fn drop(&mut self) {
        unsafe {
            close(self.fd);
        }
    }
}

// 制表符是包分隔符，换行是行分隔符，正文出现时替换为空格
fn sanitize_transport_field(message: &str) -> String {
    if !message.contains(['\n', '\r', '\t']) {
        return message.to_string();
    }

    message
        .chars()
        .map(|ch| match ch {
            '\n' | '\r' | '\t' => ' ',
            _ => ch,
        })
        .collect()
}
