use super::MountPlanner;
use super::map::expand_mapping_glob;
use crate::domain::{MappingError, PathMapping};
use crate::lifecycle::companion_request::CompanionMountRequest;
use crate::platform::paths;
use std::ffi::CString;

impl MountPlanner {
    pub fn apply_rules(&mut self, request: &CompanionMountRequest) -> bool {
        let storage_root = format!("/storage/emulated/{}", self.user_id);
        let mappings = match self.resolve_mapping_plan(&request.path_mappings, &storage_root) {
            Ok(mappings) => mappings,
            Err(error) => {
                log::error!("invalid mapping plan: {:?}", error);
                return false;
            }
        };
        let (read_only, _) = split_rules(&request.read_only_paths);
        if request.is_mapping_mode_only {
            if !self.apply_path_mappings_only(&[]) {
                return false;
            }
            let (sandboxed, exclusions) = split_rules(&request.sandboxed_paths);
            if !self.bind_policy_paths(&sandboxed, true, &storage_root)
                || !self.bind_policy_paths(&exclusions, false, &storage_root)
            {
                return false;
            }
        } else {
            let mut allowed = request.allowed_real_paths.clone();
            allowed.extend(read_only);
            if !self.apply_sdcard_redirect(&allowed, &[]) {
                return false;
            }
            if !self.bind_policy_paths(&request.excluded_real_paths, true, &storage_root) {
                return false;
            }
        }
        let source_root = format!("/data/media/{}", self.user_id);
        if !self.apply_resolved_path_mappings(
            &mappings,
            &storage_root,
            &source_root,
            true,
            false,
            None,
        ) {
            return false;
        }
        self.apply_read_only_rules(&request.read_only_paths, &mappings, &storage_root)
    }

    fn expand_policy_paths(
        &self,
        rules: &[String],
        storage_root: &str,
    ) -> Result<Vec<String>, MappingError> {
        let mut expanded = Vec::new();
        for rule in rules {
            let normalized =
                self.resolve_user_path(&self.resolve_placeholders(&paths::normalize(rule)));
            let canonical = if paths::is_absolute(&normalized) {
                normalized
            } else {
                paths::join(storage_root, &normalized)
            };
            expanded.extend(expand_mapping_glob(&canonical)?);
            if canonical.contains(['*', '?'])
                && let Some(relative) = canonical.strip_prefix(&format!("{storage_root}/"))
            {
                let backend = format!("/data/media/{}", self.user_id);
                for path in expand_mapping_glob(&paths::join(&backend, relative))? {
                    if let Some(relative) = path.strip_prefix(&backend) {
                        expanded.push(format!("{storage_root}{relative}"));
                    }
                }
            }
        }
        expanded.sort_by(|a, b| a.len().cmp(&b.len()).then(a.cmp(b)));
        expanded.dedup();
        Ok(expanded)
    }

    fn bind_policy_paths(&self, rules: &[String], sandboxed: bool, storage_root: &str) -> bool {
        let paths = match self.expand_policy_paths(rules, storage_root) {
            Ok(paths) => paths,
            Err(error) => {
                log::error!("policy expansion failed: {:?}", error);
                return false;
            }
        };
        let sandbox_root = self.to_data_media_backend_path(&self.redirect_target);
        for path in paths {
            let Some(relative) = path.strip_prefix(&format!("{storage_root}/")) else {
                continue;
            };
            let source = if sandboxed {
                paths::join(&sandbox_root, relative)
            } else {
                format!("/data/media/{}/{relative}", self.user_id)
            };
            if !self.ensure_writable_mapped_directory(&source, self.app_uid)
                || !self.ensure_directory_exists(&path, true)
            {
                return false;
            }
            if !self.bind_mount_with_storage_aliases(
                &source,
                &path,
                true,
                super::PrimaryMountFailure::AbortAll,
                Some("policy mount failed"),
                Some("policy alias mount failed"),
                None,
                None,
            ) {
                return false;
            }
        }
        true
    }

    fn apply_read_only_rules(
        &self,
        rules: &[String],
        mappings: &[PathMapping],
        storage_root: &str,
    ) -> bool {
        if rules.is_empty() {
            return true;
        }
        let (includes, excludes) = split_rules(rules);
        let includes = match self.expand_policy_paths(&includes, storage_root) {
            Ok(paths) => paths,
            Err(error) => {
                log::error!("readonly expansion failed: {:?}", error);
                return false;
            }
        };
        let excludes = match self.expand_policy_paths(&excludes, storage_root) {
            Ok(paths) => paths,
            Err(error) => {
                log::error!("readonly exclusion failed: {:?}", error);
                return false;
            }
        };
        let mut concrete = includes;
        concrete.extend(excludes.into_iter().map(|path| format!("!{path}")));
        let effective = crate::domain::project_read_only_rules(&concrete, mappings);
        let (mut includes, mut excludes) = split_rules(&effective);
        includes.retain(|path| {
            !excludes
                .iter()
                .any(|parent| path == parent || path.starts_with(&format!("{parent}/")))
        });
        excludes.retain(|path| {
            includes
                .iter()
                .any(|parent| path == parent || path.starts_with(&format!("{parent}/")))
        });
        // 子目录必须在父挂载变成只读前准备，之后按排除规则恢复其独立可写挂载。
        for path in includes.iter().chain(excludes.iter()) {
            if !self.ensure_directory_exists(path, true) {
                return false;
            }
        }
        for (roots, read_only) in [(&includes, true), (&excludes, false)] {
            for root in roots {
                for path in self.expand_storage_alias_paths(root) {
                    if !std::path::Path::new(&path).exists() {
                        continue;
                    }
                    if !self.bind_mount(&path, &path, true) || !remount_tree(&path, read_only) {
                        return false;
                    }
                }
            }
        }
        true
    }
}

fn split_rules(rules: &[String]) -> (Vec<String>, Vec<String>) {
    let mut includes = Vec::new();
    let mut excludes = Vec::new();
    for rule in rules {
        if let Some(path) = rule.strip_prefix('!') {
            excludes.push(path.to_string());
        } else {
            includes.push(rule.clone());
        }
    }
    (includes, excludes)
}

fn remount_tree(root: &str, read_only: bool) -> bool {
    let content = match std::fs::read_to_string("/proc/self/mountinfo") {
        Ok(content) => content,
        Err(error) => {
            log::error!("read mount tree failed: {}", error);
            return false;
        }
    };
    let mut targets = vec![root.to_string()];
    for line in content.lines() {
        let Some(path) = line.split_whitespace().nth(4) else {
            continue;
        };
        let path = path
            .replace("\\040", " ")
            .replace("\\011", "\t")
            .replace("\\012", "\n")
            .replace("\\134", "\\");
        if path.starts_with(&format!("{root}/")) {
            targets.push(path);
        }
    }
    targets.sort();
    targets.dedup();
    for target in targets {
        let Ok(target) = CString::new(target) else {
            return false;
        };
        let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
        if unsafe { libc::statvfs(target.as_ptr(), stat.as_mut_ptr()) } != 0 {
            log::error!(
                "read mount flags failed: {}",
                std::io::Error::last_os_error()
            );
            return false;
        }
        let current = unsafe { stat.assume_init() }.f_flag;
        let mut flags = libc::MS_BIND | libc::MS_REMOUNT;
        for (source, destination) in [
            (libc::ST_NOSUID, libc::MS_NOSUID),
            (libc::ST_NODEV, libc::MS_NODEV),
            (libc::ST_NOEXEC, libc::MS_NOEXEC),
            (libc::ST_NOATIME, libc::MS_NOATIME),
            (libc::ST_NODIRATIME, libc::MS_NODIRATIME),
            (libc::ST_RELATIME, libc::MS_RELATIME),
        ] {
            if current & source != 0 {
                flags |= destination;
            }
        }
        if read_only {
            flags |= libc::MS_RDONLY;
        }
        let result = unsafe {
            libc::mount(
                std::ptr::null(),
                target.as_ptr(),
                std::ptr::null(),
                flags,
                std::ptr::null(),
            )
        };
        if result != 0 {
            log::error!(
                "readonly remount failed path={:?} error={}",
                target,
                std::io::Error::last_os_error()
            );
            return false;
        }
    }
    true
}
