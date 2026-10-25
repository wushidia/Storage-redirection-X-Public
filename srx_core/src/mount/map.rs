use super::MountPlanner;
use crate::domain::{MappingError, PathMapping, compose_mount_mappings, map_path_once};
use crate::platform::{fs, paths};
use std::fs as std_fs;
use std::path::Path;

const MAPPING_EXPANSION_LIMIT: usize = 512;

impl MountPlanner {
    pub(super) fn resolve_path_mappings(
        &self,
        mappings: &[PathMapping],
        storage_root: &str,
    ) -> Vec<PathMapping> {
        match self.resolve_mapping_plan(mappings, storage_root) {
            Ok(plan) => plan,
            Err(error) => {
                log::error!("mapping plan failed: {:?}", error);
                Vec::new()
            }
        }
    }

    pub(super) fn resolve_mapping_plan(
        &self,
        mappings: &[PathMapping],
        storage_root: &str,
    ) -> Result<Vec<PathMapping>, MappingError> {
        let mut concrete = Vec::new();
        for mapping in mappings {
            let resolve = |raw: &str| {
                let path =
                    self.resolve_user_path(&self.resolve_placeholders(&paths::normalize(raw)));
                if paths::is_absolute(&path) {
                    path
                } else {
                    paths::join(storage_root, &path)
                }
            };
            let mapping =
                PathMapping::new(resolve(&mapping.request_path), resolve(&mapping.final_path));
            if paths::has_unsafe_segments(&mapping.request_path)
                || paths::has_unsafe_segments(&mapping.final_path)
                || mapping.request_path == storage_root
                || mapping.final_path == storage_root
            {
                continue;
            }
            if !mapping.request_path.contains(['*', '?']) {
                concrete.push(mapping);
                continue;
            }
            let shared = mapping
                .request_path
                .starts_with(&format!("{storage_root}/"));
            let mut requests = if shared {
                Vec::new()
            } else {
                expand_mapping_glob(&mapping.request_path)?
            };
            if let Some(relative) = mapping
                .request_path
                .strip_prefix(&format!("{storage_root}/"))
            {
                for backend_root in [
                    format!("/data/media/{}", self.user_id),
                    self.to_data_media_backend_path(&self.redirect_target),
                ] {
                    for backend in expand_mapping_glob(&format!("{backend_root}/{relative}"))? {
                        if let Some(relative) = backend.strip_prefix(&backend_root) {
                            requests.push(format!("{storage_root}{relative}"));
                        }
                    }
                }
            }
            requests.sort();
            requests.dedup();
            for request in requests {
                if let Some(target) = map_path_once(&request, &mapping)? {
                    concrete.push(PathMapping::new(request, target));
                }
            }
        }
        compose_mount_mappings(&concrete)
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn apply_resolved_path_mappings(
        &self,
        mappings: &[PathMapping],
        storage_root: &str,
        source_root: &str,
        should_chown_current_dirs: bool,
        should_use_existing_target_source_only: bool,
        is_any_applied_out: Option<&mut bool>,
    ) -> bool {
        let mut applied = false;
        for mapping in mappings {
            let source = if let Some(relative) =
                mapping.final_path.strip_prefix(&format!("{storage_root}/"))
            {
                paths::join(source_root, relative)
            } else {
                mapping.final_path.clone()
            };
            if !Path::new(&source).exists() {
                if should_use_existing_target_source_only {
                    return false;
                }
                if !self.ensure_writable_mapped_directory(&source, self.app_uid) {
                    return false;
                }
            } else if source.starts_with(&format!("/data/media/{}/", self.user_id)) {
                if fs::is_directory(&source) {
                    if !self.ensure_writable_mapped_directory(&source, self.app_uid) {
                        return false;
                    }
                } else {
                    let Ok(path) = std::ffi::CString::new(source.as_str()) else {
                        return false;
                    };
                    if unsafe { libc::chown(path.as_ptr(), self.app_uid as u32, 1023) } != 0
                        || unsafe { libc::chmod(path.as_ptr(), 0o660) } != 0
                    {
                        log::error!(
                            "prepare shared mapping file failed: {}",
                            std::io::Error::last_os_error()
                        );
                        return false;
                    }
                }
            }
            if !self.prepare_mapping_endpoint(
                &source,
                &mapping.request_path,
                should_chown_current_dirs,
            ) {
                return false;
            }
            let mut mounted = false;
            if !self.bind_mount_with_storage_aliases(
                &source,
                &mapping.request_path,
                true,
                super::PrimaryMountFailure::AbortAll,
                Some("mapping mount failed"),
                Some("mapping alias failed"),
                None,
                Some(&mut mounted),
            ) || !mounted
            {
                return false;
            }
            applied = true;
            log::info!("map {} -> {}", mapping.request_path, mapping.final_path);
        }
        if let Some(output) = is_any_applied_out {
            *output = applied;
        }
        true
    }

    fn prepare_mapping_endpoint(&self, source: &str, target: &str, should_chown: bool) -> bool {
        if fs::is_directory(source) {
            return self.ensure_directory_exists(target, should_chown);
        }
        if Path::new(target).is_file() {
            return true;
        }
        if !self.ensure_directory_exists(&paths::parent(target), should_chown) {
            return false;
        }
        match std_fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(false)
            .open(target)
        {
            Ok(_) => true,
            Err(error) => {
                log::error!(
                    "prepare mapping file failed path={} error={}",
                    target,
                    error
                );
                false
            }
        }
    }
}

pub(super) fn expand_mapping_glob(pattern: &str) -> Result<Vec<String>, MappingError> {
    if !pattern.contains(['*', '?']) {
        return Ok(vec![pattern.to_string()]);
    }
    let parts: Vec<_> = pattern.split('/').filter(|item| !item.is_empty()).collect();
    let mut candidates = vec![String::from("/")];
    for part in parts {
        let mut next = Vec::new();
        for parent in candidates {
            if !part.contains(['*', '?']) {
                let path = paths::join(&parent, part);
                if Path::new(&path).exists() {
                    next.push(path);
                }
                continue;
            }
            let entries = match std_fs::read_dir(&parent) {
                Ok(entries) => entries,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
                Err(error) => {
                    log::warn!(
                        "mapping wildcard read failed path={} error={}",
                        parent,
                        error
                    );
                    return Err(MappingError::UnreadablePath);
                }
            };
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if paths::matches(part, &name, false) {
                    next.push(paths::join(&parent, &name));
                    if next.len() > MAPPING_EXPANSION_LIMIT {
                        return Err(MappingError::TooManyMatches);
                    }
                }
            }
        }
        candidates = next;
    }
    Ok(candidates)
}
