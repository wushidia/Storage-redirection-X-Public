// 滚动日志：超过行数上限时丢弃文件头部批量行
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};

const TRIM_BATCH_LINES: usize = 200;
const FLUSH_BATCH_LINES: usize = 64;

pub(crate) struct RollingLog {
    path: PathBuf,
    max_lines: usize,
    line_count: usize,
    pending_flush: usize,
    writer: BufWriter<File>,
}

impl RollingLog {
    pub(crate) fn open(path: impl Into<PathBuf>, max_lines: usize) -> io::Result<Self> {
        let path = path.into();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let file = OpenOptions::new().create(true).append(true).open(&path)?;
        Ok(Self {
            line_count: count_lines(&path).unwrap_or(0),
            max_lines,
            path,
            pending_flush: 0,
            writer: BufWriter::new(file),
        })
    }

    pub(crate) fn append(&mut self, line: &str) {
        if line.is_empty() {
            return;
        }
        if writeln!(self.writer, "{line}").is_err() {
            return;
        }

        self.line_count += 1;
        self.pending_flush += 1;
        if self.pending_flush >= FLUSH_BATCH_LINES {
            self.flush();
        }
        if self.line_count > self.max_lines {
            self.trim();
        }
    }

    pub(crate) fn flush(&mut self) {
        let _ = self.writer.flush();
        self.pending_flush = 0;
    }

    fn trim(&mut self) {
        self.flush();
        let drop_lines = self
            .line_count
            .saturating_sub(self.max_lines)
            .saturating_add(TRIM_BATCH_LINES);
        if drop_lines == 0 {
            return;
        }
        if trim_file_drop_head(&self.path, drop_lines).is_err() {
            return;
        }

        self.line_count = self.line_count.saturating_sub(drop_lines);
        if let Ok(file) = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)
        {
            self.writer = BufWriter::new(file);
        }
    }

    pub(crate) fn clear(&mut self) {
        self.flush();
        if fs::write(&self.path, []).is_err() {
            return;
        }
        self.line_count = 0;
        self.pending_flush = 0;
        if let Ok(file) = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)
        {
            self.writer = BufWriter::new(file);
        }
    }
}

fn count_lines(path: &Path) -> io::Result<usize> {
    let data = fs::read(path)?;
    Ok(data.iter().filter(|byte| **byte == b'\n').count())
}

fn trim_file_drop_head(path: &Path, drop_lines: usize) -> io::Result<()> {
    if drop_lines == 0 {
        return Ok(());
    }

    let data = fs::read(path)?;
    let mut dropped = 0usize;
    let mut start = 0usize;
    while start < data.len() && dropped < drop_lines {
        if data[start] == b'\n' {
            dropped += 1;
        }
        start += 1;
    }

    let remaining = if dropped >= drop_lines {
        &data[start..]
    } else {
        &[]
    };
    let temp_path = path.with_extension("tmp");
    fs::write(&temp_path, remaining)?;
    fs::rename(temp_path, path)?;
    Ok(())
}
