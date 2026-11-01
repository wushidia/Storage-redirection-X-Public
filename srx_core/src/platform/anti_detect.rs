use std::sync::atomic::{AtomicBool, Ordering};

// mremap flags（部分 target 的 libc 未导出常量，这里固定取内核值）
const MREMAP_MAYMOVE: libc::c_int = 1;
const MREMAP_FIXED: libc::c_int = 2;
static SELF_RELOCATED: AtomicBool = AtomicBool::new(false);

struct Seg {
    start: usize,
    len: usize,
    // 该段在 .so 文件内的偏移；偏移 0 的段起始处是 ELF 头
    offset: usize,
    prot: libc::c_int,
    readable: bool,
}
struct SegCopy {
    src: *mut libc::c_void,
    dst: usize,
    len: usize,
}

// 只迁移常驻模块自身的文件映射，保持虚拟地址不变。
// 请求 DLCLOSE 的进程由加载器回收，不能替换其映射。
pub fn relocate_self() -> usize {
    if SELF_RELOCATED.swap(true, Ordering::AcqRel) {
        return 0;
    }

    let segs = collect_self_segments();
    if segs.is_empty() {
        log::info!("self relocate: no file-backed module segments found");
        return 0;
    }

    // 阶段一：原段尚在，逐段拷到匿名内存并设好最终权限。失败则整体放弃、保持原状。
    let mut copies: Vec<SegCopy> = Vec::with_capacity(segs.len());
    for seg in &segs {
        match stage_segment_copy(seg) {
            Some(c) => copies.push(c),
            None => {
                for c in &copies {
                    unsafe { libc::munmap(c.src, c.len) };
                }
                log::warn!("self relocate: stage failed, aborted (mappings untouched)");
                return 0;
            }
        }
    }

    // mremap 在内核中完成同地址替换，返回后执行地址不变。
    let mut done = 0usize;
    let total = copies.len();
    for c in &copies {
        // libc 0.2 未对 android 导出 mremap 包装，直接走 syscall。
        // 成功返回新地址（== dst），失败返回 -1。
        let ret = unsafe {
            libc::syscall(
                libc::SYS_mremap,
                c.src,
                c.len,
                c.len,
                (MREMAP_MAYMOVE | MREMAP_FIXED) as libc::c_long,
                c.dst as *mut libc::c_void,
            )
        };
        if ret == -1 {
            // dst 仍是原文件映射，未被破坏；回收没用上的副本，继续其余段
            unsafe { libc::munmap(c.src, c.len) };
            continue;
        }
        done += 1;
    }

    log::info!("self relocate done segments={}/{}", done, total);
    done
}

fn collect_self_segments() -> Vec<Seg> {
    let anchor = relocate_self as *const () as usize;
    let content = match std::fs::read_to_string("/proc/self/maps") {
        Ok(c) => c,
        Err(_) => return Vec::new(),
    };

    let mut module_path = None;
    for line in content.lines() {
        if let Some((start, end, _perms, _offset, Some(path))) = parse_maps_line(line)
            && anchor >= start
            && anchor < end
            && path.starts_with('/')
        {
            module_path = Some(path);
            break;
        }
    }
    let Some(module_path) = module_path else {
        return Vec::new();
    };

    let mut segs = Vec::new();
    for line in content.lines() {
        if let Some((start, end, perms, offset, Some(path))) = parse_maps_line(line) {
            if path != module_path {
                continue;
            }
            let len = end.saturating_sub(start);
            if len == 0 {
                continue;
            }
            let (prot, readable) = perms_to_prot(perms);
            segs.push(Seg {
                start,
                len,
                offset,
                prot,
                readable,
            });
        }
    }
    segs
}

fn stage_segment_copy(seg: &Seg) -> Option<SegCopy> {
    let src = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            seg.len,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
            -1,
            0,
        )
    };
    if src == libc::MAP_FAILED {
        return None;
    }

    if !seg.readable
        && unsafe {
            libc::mprotect(
                seg.start as *mut libc::c_void,
                seg.len,
                seg.prot | libc::PROT_READ,
            )
        } != 0
    {
        unsafe { libc::munmap(src, seg.len) };
        return None;
    }
    unsafe { std::ptr::copy_nonoverlapping(seg.start as *const u8, src as *mut u8, seg.len) };
    if !seg.readable
        && unsafe { libc::mprotect(seg.start as *mut libc::c_void, seg.len, seg.prot) } != 0
    {
        unsafe { libc::munmap(src, seg.len) };
        log::warn!(
            "self relocate source protection restore failed: {}",
            std::io::Error::last_os_error()
        );
        return None;
    }

    // 仅清除副本的 ELF 文件头，保留加载器正在使用的程序头和重定位内容。
    if seg.offset == 0 && seg.len >= 64 {
        let p = src as *mut u8;
        unsafe {
            if *p == 0x7f && *p.add(1) == b'E' && *p.add(2) == b'L' && *p.add(3) == b'F' {
                std::ptr::write_bytes(p, 0, 64);
            }
        }
    }

    let r = unsafe { libc::mprotect(src, seg.len, seg.prot) };
    if r != 0 {
        unsafe { libc::munmap(src, seg.len) };
        return None;
    }

    Some(SegCopy {
        src,
        dst: seg.start,
        len: seg.len,
    })
}

fn parse_maps_line(line: &str) -> Option<(usize, usize, &str, usize, Option<&str>)> {
    let mut it = line.split_whitespace();
    let range = it.next()?;
    let perms = it.next()?;
    let offset_str = it.next()?;
    let _dev = it.next()?;
    let _inode = it.next()?;
    let path = it.next();
    let dash = range.find('-')?;
    let start = usize::from_str_radix(&range[..dash], 16).ok()?;
    let end = usize::from_str_radix(range.get(dash + 1..)?, 16).ok()?;
    let offset = usize::from_str_radix(offset_str, 16).ok()?;
    Some((start, end, perms, offset, path))
}

fn perms_to_prot(perms: &str) -> (libc::c_int, bool) {
    let b = perms.as_bytes();
    let r = b.first() == Some(&b'r');
    let w = b.get(1) == Some(&b'w');
    let x = b.get(2) == Some(&b'x');
    (
        (if r { libc::PROT_READ } else { 0 })
            | (if w { libc::PROT_WRITE } else { 0 })
            | (if x { libc::PROT_EXEC } else { 0 }),
        r,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn staging_preserves_source_and_clears_only_the_copied_header() {
        let len = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as usize;
        let source = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        assert_ne!(source, libc::MAP_FAILED);
        unsafe {
            std::ptr::write_bytes(source as *mut u8, 0x5a, len);
            std::ptr::copy_nonoverlapping(b"\x7fELF".as_ptr(), source as *mut u8, 4);
        }
        let segment = Seg {
            start: source as usize,
            len,
            offset: 0,
            prot: libc::PROT_READ | libc::PROT_WRITE,
            readable: true,
        };
        let copied = stage_segment_copy(&segment).expect("owned mapping staging");
        unsafe {
            assert_eq!(*(source as *const u8), 0x7f);
            assert_eq!(*(copied.src as *const u8), 0);
            assert_eq!(*((copied.src as *const u8).add(64)), 0x5a);
            libc::munmap(copied.src, len);
            libc::munmap(source, len);
        }
    }

    #[test]
    fn staging_restores_non_readable_source_permissions() {
        let len = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as usize;
        let source = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_NONE,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        assert_ne!(source, libc::MAP_FAILED);
        let segment = Seg {
            start: source as usize,
            len,
            offset: 1,
            prot: libc::PROT_NONE,
            readable: false,
        };
        let copied = stage_segment_copy(&segment).expect("owned inaccessible mapping staging");
        let maps = std::fs::read_to_string("/proc/self/maps").expect("process maps");
        let (_, _, perms, _, _) = maps
            .lines()
            .filter_map(parse_maps_line)
            .find(|(start, end, _, _, _)| *start <= segment.start && segment.start < *end)
            .expect("source mapping");
        assert_eq!(&perms[..3], "---");
        unsafe {
            libc::munmap(copied.src, len);
            libc::munmap(source, len);
        }
    }
}
