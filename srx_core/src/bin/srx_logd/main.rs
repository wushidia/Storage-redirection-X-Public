// 日志守护进程：经 socket 收包，按 tag 分流到滚动日志与统计文件
#[path = "../../monitor/query.rs"]
mod monitor_query;
mod rolling;
mod transport;

use libc::{POLLIN, c_void, poll, pollfd, recv};
use rolling::RollingLog;
use std::fs;
use std::io::{self, BufRead, BufReader, Write};
use std::mem;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};
use transport::{ClientSocket, bind_log_socket};

const LOGS_DIR: &str = "/data/adb/modules/storage.redirect.x/logs";
const RUNNING_LOG: &str = "/data/adb/modules/storage.redirect.x/logs/running.log";
const FILE_MONITOR_LOG: &str = "/data/adb/modules/storage.redirect.x/logs/file_monitor.log";
const MEDIA_STATE_LOG: &str = "/data/adb/modules/storage.redirect.x/logs/media_provider_state.log";
const APP_STATUS_LOG: &str = "/data/adb/modules/storage.redirect.x/logs/app_status.log";
const STATS_FILE: &str = "/data/adb/modules/storage.redirect.x/stats";
const MAX_RUNNING_LINES: usize = 30_000;
const MAX_MONITOR_LINES: usize = 30_000;
const MAX_MEDIA_STATE_LINES: usize = 30_000;
const MAX_APP_STATUS_LINES: usize = 30_000;
const RECV_BUFFER_SIZE: usize = 8192;
const STATS_FLUSH_INTERVAL: Duration = Duration::from_secs(2);

const TAG_FILE_MONITOR: &str = "FileMonitorOp";
const TAG_MEDIA_STATE: &str = "MediaState";
const TAG_APP_STATUS: &str = "AppStatus";
const TAG_MEDIA_STATE_FLUSH: &str = "MediaStateFlush";
const TAG_APP_STATUS_FLUSH: &str = "AppStatusFlush";
const TAG_STATS: &str = "Stats";
const TAG_CONTROL: &str = "Control";
const CONTROL_CLEAR_ALL: &str = "clear-all";
const CONTROL_FLUSH_ALL: &str = "flush-all";

fn main() {
    let command = std::env::args().nth(1);
    let arg = std::env::args().nth(2);
    let result = match command.as_deref() {
        None => run_daemon(),
        Some("monitor-query") => {
            let args: Vec<String> = std::env::args().collect();
            let before = args
                .get(2)
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0)
                .max(0);
            let after = args
                .get(3)
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0)
                .max(0);
            monitor_query::run(
                Path::new("/data/adb/modules/storage.redirect.x"),
                before,
                after,
                100,
            )
        }
        Some("emit-stream") => run_emit_stream(arg),
        Some("control") => run_control(arg),
        Some(_) => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "usage: srx_logd [emit-stream <tag> | control <command>]",
        )),
    };
    if let Err(error) = result {
        let _ = writeln!(io::stderr(), "srx_logd: {error}");
        std::process::exit(1);
    }
}

fn run_daemon() -> io::Result<()> {
    fs::create_dir_all(LOGS_DIR)?;
    let fd = bind_log_socket()?;
    let mut state = LogState::new()?;
    let mut buffer = [0u8; RECV_BUFFER_SIZE];
    let mut input = pollfd {
        fd,
        events: POLLIN,
        revents: 0,
    };

    loop {
        state.flush_stats_if_due();
        let ready = unsafe { poll(&mut input, 1, state.stats_flush_timeout_ms()) };
        if ready < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if ready == 0 {
            continue;
        }
        let size = unsafe { recv(fd, buffer.as_mut_ptr() as *mut c_void, buffer.len(), 0) };
        if size <= 0 {
            continue;
        }
        let Ok(text) = std::str::from_utf8(&buffer[..size as usize]) else {
            continue;
        };
        state.handle(text);
    }
}

fn clear_monitor_store() -> io::Result<()> {
    run_monitor_command("clear")
}

fn run_monitor_command(command: &str) -> io::Result<()> {
    let mut process = std::process::Command::new("/system/bin/app_process");
    process
        .env(
            "CLASSPATH",
            "/data/adb/modules/storage.redirect.x/monitor.dex",
        )
        .args([
            "/system/bin",
            "org.srx.monitor.MonitorServer",
            "/data/adb/modules/storage.redirect.x",
        ]);
    if let Some(user_id) = command.strip_prefix("rules:") {
        let user_id: u32 = user_id
            .parse()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid Android user"))?;
        process.args(["rules", &user_id.to_string()]);
    } else {
        process.arg(command);
    }
    process.status().and_then(|status| {
        if status.success() {
            Ok(())
        } else {
            Err(io::Error::other("monitor clear failed"))
        }
    })
}

fn run_emit_stream(tag: Option<String>) -> io::Result<()> {
    let Some(tag) = tag.filter(|value| !value.is_empty()) else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "missing tag"));
    };
    let Some(socket) = ClientSocket::new() else {
        return Err(io::Error::last_os_error());
    };

    let stdin = io::stdin();
    let reader = BufReader::new(stdin.lock());
    for line in reader.lines() {
        let line = line?;
        socket.send('I', &tag, &line)?;
    }
    if let Some(flush_tag) = flush_tag_for(&tag) {
        socket.send('I', flush_tag, ".")?;
    }
    Ok(())
}

fn run_control(command: Option<String>) -> io::Result<()> {
    let Some(command) = command.filter(|value| !value.is_empty()) else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "missing control command",
        ));
    };
    if command.starts_with("rules:") {
        return run_monitor_command(&command);
    }
    if command == "monitor-policy" {
        return run_monitor_command("policy");
    }
    let Some(socket) = ClientSocket::new() else {
        return Err(io::Error::last_os_error());
    };
    socket.send('I', TAG_CONTROL, &command)
}

struct LogState {
    running: RollingLog,
    monitor: RollingLog,
    media_state: RollingLog,
    app_status: RollingLog,
    stats_total: i64,
    stats_dirty: usize,
    stats_path: PathBuf,
    last_stats_flush: Instant,
}

impl LogState {
    fn new() -> io::Result<Self> {
        Ok(Self {
            running: RollingLog::open(RUNNING_LOG, MAX_RUNNING_LINES)?,
            monitor: RollingLog::open(FILE_MONITOR_LOG, MAX_MONITOR_LINES)?,
            media_state: RollingLog::open(MEDIA_STATE_LOG, MAX_MEDIA_STATE_LINES)?,
            app_status: RollingLog::open(APP_STATUS_LOG, MAX_APP_STATUS_LINES)?,
            stats_total: read_stats_total(Path::new(STATS_FILE)),
            stats_dirty: 0,
            stats_path: PathBuf::from(STATS_FILE),
            last_stats_flush: Instant::now(),
        })
    }

    fn handle(&mut self, packet: &str) {
        let Some((level, tag, message)) = parse_packet(packet) else {
            return;
        };

        match tag {
            TAG_STATS => self.handle_stats(message),
            TAG_CONTROL => self.handle_control(message),
            TAG_FILE_MONITOR => self.monitor.append(message),
            TAG_MEDIA_STATE => self.media_state.append(message),
            TAG_APP_STATUS => self.app_status.append(message),
            TAG_MEDIA_STATE_FLUSH => self.media_state.flush(),
            TAG_APP_STATUS_FLUSH => self.app_status.flush(),
            _ => {
                let line = format_running_line(level, tag, message);
                self.running.append(&line);
            }
        }
    }

    fn handle_stats(&mut self, message: &str) {
        let Some(value) = message.strip_prefix('+') else {
            return;
        };
        let Ok(delta) = value.trim().parse::<i64>() else {
            return;
        };
        if delta <= 0 {
            return;
        }

        self.stats_total = self.stats_total.saturating_add(delta);
        self.stats_dirty += 1;
        if self.stats_dirty >= 50 {
            self.flush_stats();
        }
    }

    fn handle_control(&mut self, message: &str) {
        match message {
            CONTROL_CLEAR_ALL => self.clear_all(),
            CONTROL_FLUSH_ALL => self.flush_all(),
            "clear-monitor" => {
                let _ = clear_monitor_store();
                self.monitor.clear();
            }
            _ => {}
        }
    }

    fn clear_all(&mut self) {
        self.running.clear();
        self.monitor.clear();
        let _ = clear_monitor_store();
        self.media_state.clear();
        self.app_status.clear();
        self.stats_total = 0;
        self.stats_dirty = 1;
        self.flush_stats();
    }

    fn flush_all(&mut self) {
        self.running.flush();
        self.monitor.flush();
        self.media_state.flush();
        self.app_status.flush();
        if self.stats_dirty > 0 {
            self.flush_stats();
        }
    }

    fn flush_stats(&mut self) {
        self.last_stats_flush = Instant::now();
        if let Err(error) = fs::write(&self.stats_path, format!("{}\n", self.stats_total)) {
            let _ = writeln!(io::stderr(), "srx_logd: save stats: {error}");
            return;
        }
        self.stats_dirty = 0;
    }

    fn flush_stats_if_due(&mut self) {
        if self.stats_dirty > 0 && self.last_stats_flush.elapsed() >= STATS_FLUSH_INTERVAL {
            self.flush_stats();
        }
    }

    // 无待保存统计时阻塞等待消息，不为刷新计数额外唤醒。
    fn stats_flush_timeout_ms(&self) -> i32 {
        if self.stats_dirty == 0 {
            return -1;
        }
        let remaining = STATS_FLUSH_INTERVAL.saturating_sub(self.last_stats_flush.elapsed());
        if remaining.is_zero() {
            0
        } else {
            remaining.as_millis().max(1) as i32
        }
    }
}

impl Drop for LogState {
    fn drop(&mut self) {
        self.running.flush();
        self.monitor.flush();
        self.media_state.flush();
        self.app_status.flush();
        if self.stats_dirty > 0 {
            self.flush_stats();
        }
    }
}

fn parse_packet(packet: &str) -> Option<(&str, &str, &str)> {
    let mut parts = packet.splitn(3, '\t');
    let level = parts.next()?;
    let tag = parts.next()?;
    let message = parts.next()?;
    if level.is_empty() || tag.is_empty() || message.is_empty() {
        return None;
    }
    Some((level, tag, message))
}

fn format_running_line(level: &str, tag: &str, message: &str) -> String {
    let now = timestamp_text();
    if message.starts_with("[Rs") || message.starts_with("[Kt") {
        return format!("{now} {message}");
    }

    let source = if tag == "SRX" { "Jv" } else { "Rs" };
    format!("{now} [{source}{}] {message}", level_text(level))
}

fn level_text(level: &str) -> &'static str {
    match level.as_bytes().first().copied() {
        Some(b'V') => "Verbose",
        Some(b'D') => "Debug",
        Some(b'I') => "Info",
        Some(b'W') => "Warn",
        Some(b'E') => "Error",
        _ => "Info",
    }
}

fn timestamp_text() -> String {
    let mut now: libc::time_t = 0;
    unsafe {
        libc::time(&mut now as *mut libc::time_t);
    }
    let mut tm_value: libc::tm = unsafe { mem::zeroed() };
    let tm_ptr = unsafe { libc::localtime_r(&now as *const libc::time_t, &mut tm_value) };
    if tm_ptr.is_null() {
        return "00/00 00:00:00".to_string();
    }

    let mut buffer = [0u8; 32];
    let fmt = b"%m/%d %H:%M:%S\0";
    let written = unsafe {
        libc::strftime(
            buffer.as_mut_ptr() as *mut _,
            buffer.len(),
            fmt.as_ptr() as *const _,
            &tm_value as *const _,
        )
    };
    if written == 0 {
        return "00/00 00:00:00".to_string();
    }
    String::from_utf8_lossy(&buffer[..written]).to_string()
}

fn flush_tag_for(tag: &str) -> Option<&'static str> {
    match tag {
        TAG_MEDIA_STATE => Some(TAG_MEDIA_STATE_FLUSH),
        TAG_APP_STATUS => Some(TAG_APP_STATUS_FLUSH),
        _ => None,
    }
}

fn read_stats_total(path: &Path) -> i64 {
    fs::read_to_string(path)
        .ok()
        .and_then(|text| text.trim().parse::<i64>().ok())
        .unwrap_or(0)
}

#[cfg(test)]
mod stats_tests {
    use super::*;

    fn state(directory: &Path) -> LogState {
        LogState {
            running: RollingLog::open(directory.join("running.log"), 10).unwrap(),
            monitor: RollingLog::open(directory.join("monitor.log"), 10).unwrap(),
            media_state: RollingLog::open(directory.join("media.log"), 10).unwrap(),
            app_status: RollingLog::open(directory.join("apps.log"), 10).unwrap(),
            stats_path: directory.join("stats"),
            stats_total: read_stats_total(&directory.join("stats")),
            stats_dirty: 0,
            last_stats_flush: Instant::now(),
        }
    }

    #[test]
    fn sparse_stats_are_persisted_without_another_packet() {
        let directory =
            std::env::temp_dir().join(format!("srx-sparse-stats-{}", std::process::id()));
        let mut logs = state(&directory);
        assert_eq!(logs.stats_flush_timeout_ms(), -1);
        logs.handle("I\tStats\t+1");
        assert!(logs.stats_flush_timeout_ms() > 0);
        logs.last_stats_flush -= STATS_FLUSH_INTERVAL;
        logs.flush_stats_if_due();
        assert_eq!(fs::read_to_string(directory.join("stats")).unwrap(), "1\n");
        assert_eq!(logs.stats_flush_timeout_ms(), -1);
        drop(logs);
        let reopened = state(&directory);
        assert_eq!(reopened.stats_total, 1);
        drop(reopened);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn stats_keep_values_above_the_android_int_limit() {
        let directory =
            std::env::temp_dir().join(format!("srx-large-stats-{}", std::process::id()));
        let mut logs = state(&directory);
        logs.handle("I\tStats\t+2147483648");
        logs.last_stats_flush -= STATS_FLUSH_INTERVAL;
        logs.flush_stats_if_due();
        assert_eq!(read_stats_total(&directory.join("stats")), 2147483648);
        drop(logs);
        fs::remove_dir_all(directory).unwrap();
    }
}
