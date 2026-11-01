use super::MountPlanner;
use crate::domain::PathMapping;
use crate::platform::{fs, paths};
use std::fs as std_fs;

const ALLOW_WILDCARD_EXPAND_LIMIT: usize = 512;

impl MountPlanner {
    pub fn apply_sdcard_redirect(
        &mut self,
        allowed_real_paths: &[String],
        path_mappings: &[PathMapping],
    ) -> bool {
        let resolved_target_storage =
            self.resolve_user_path(&self.normalize_path(&self.redirect_target));
        if resolved_target_storage.is_empty() {
            log::error!("redirect target empty");
            return false;
        }

        let resolved_target = self.to_data_media_backend_path(&resolved_target_storage);
        if resolved_target.is_empty() {
            log::error!(
                "redirect target not under storage/emulated: {}",
                resolved_target_storage
            );
            return false;
        }

        log::info!("redirect target={}", resolved_target_storage);
        log::debug!("redirect backend={}", resolved_target);
        log::info!(
            "mount args pkg={} uid={} user={} allow={} map={}",
            self.package_name,
            self.app_uid,
            self.user_id,
            allowed_real_paths.len(),
            path_mappings.len()
        );

        if !self.ensure_mount_namespace_prepared() {
            log::error!("mount ns init failed");
            return false;
        }

        let storage_path = format!("/storage/emulated/{}", self.user_id);
        let android_path = format!("{}/Android", storage_path);
        log::info!(
            "key mounts storage={} android={}",
            storage_path,
            android_path
        );

        let mut anchor = StorageAnchor::new(self.user_id);
        if self.ensure_directory_exists(&anchor.path, false) {
            anchor.has_directory = true;
            for source_candidate in self.expand_storage_alias_paths(&storage_path) {
                if !fs::is_directory(&source_candidate) {
                    continue;
                }
                if self.bind_mount(&source_candidate, &anchor.path, true) {
                    anchor.is_mounted = true;
                    log::info!(
                        "real storage anchored {} -> {}",
                        source_candidate,
                        anchor.path
                    );
                    break;
                }
            }
        }
        if !anchor.is_mounted {
            log::warn!(
                "real storage anchor failed, fallback to data/media: {}",
                anchor.path.as_str()
            );
        }

        let data_media_root = format!("/data/media/{}", self.user_id);
        if !fs::is_directory(&data_media_root) {
            log::error!("data/media missing: {}", data_media_root);
            return false;
        }

        let android_source_path = format!("{}/Android", data_media_root);
        if !self.ensure_directory_exists(&resolved_target, false) {
            log::error!("mkdir redirect failed: {}", resolved_target);
            return false;
        }

        if !self.ensure_writable_mapped_directory(&resolved_target, self.app_uid) {
            log::warn!("fix redirect root perm failed: {}", resolved_target);
        }
        self.repair_redirect_target_directories(&resolved_target);

        let redirect_android_path = format!("{}/Android", resolved_target);
        if !self.ensure_directory_exists(&redirect_android_path, true) {
            log::error!("mkdir android placeholder failed");
            return false;
        }

        let mut is_storage_redirect_applied = false;
        if !self.bind_mount_with_storage_aliases(
            &resolved_target,
            &storage_path,
            true,
            super::PrimaryMountFailure::AbortAll,
            Some("storage main mount failed"),
            Some("storage alias mount failed"),
            Some("storage alias mount ok"),
            Some(&mut is_storage_redirect_applied),
        ) {
            log::error!("storage redirect failed");
            return false;
        }

        if !is_storage_redirect_applied {
            log::error!("storage redirect failed (no mount point)");
            return false;
        }

        let mut is_android_restore_applied = false;
        if !self.bind_mount_with_storage_aliases(
            &android_source_path,
            &android_path,
            true,
            super::PrimaryMountFailure::AbortAll,
            Some("android restore failed"),
            Some("android alias restore failed"),
            Some("android alias restore ok"),
            Some(&mut is_android_restore_applied),
        ) {
            log::error!("android restore failed");
            return false;
        }
        if !is_android_restore_applied {
            log::error!("android restore failed (no mount point)");
            return false;
        }

        if !allowed_real_paths.is_empty() {
            let real_base_path = if anchor.is_mounted {
                anchor.path.as_str()
            } else {
                data_media_root.as_str()
            };

            let mut resolved_paths: Vec<String> = Vec::with_capacity(allowed_real_paths.len());
            for path in allowed_real_paths {
                let resolved =
                    self.resolve_user_path(&self.resolve_placeholders(&self.normalize_path(path)));
                if resolved.is_empty() {
                    continue;
                }
                if paths::has_unsafe_segments(&resolved) {
                    continue;
                }
                if resolved == storage_path {
                    log::warn!("skip allow (whole storage not supported): {}", resolved);
                    continue;
                }
                if !paths::starts_with(&resolved, &format!("{}/", storage_path)) {
                    log::warn!("skip allow (not under storage): {}", resolved);
                    continue;
                }
                resolved_paths.push(resolved);
            }

            resolved_paths.sort();
            resolved_paths.dedup();

            let effective_rules = reduce_allowed_real_path_rules(resolved_paths);
            let mut effective_paths: Vec<String> = Vec::with_capacity(effective_rules.len());
            for rule in effective_rules {
                if has_allowed_path_wildcard(&rule) {
                    let expanded = Self::expand_allowed_real_path_wildcard(
                        &rule,
                        &storage_path,
                        real_base_path,
                    );
                    if expanded.is_empty() {
                        log::warn!("allow wildcard no match: {}", rule);
                    } else if expanded.len() >= ALLOW_WILDCARD_EXPAND_LIMIT {
                        log::warn!(
                            "allow wildcard limit path={} limit={}",
                            rule,
                            ALLOW_WILDCARD_EXPAND_LIMIT
                        );
                    }
                    effective_paths.extend(expanded);
                } else {
                    effective_paths.push(rule);
                }
            }
            effective_paths.sort();
            effective_paths.dedup();

            for allowed_path in effective_paths {
                let mut relative = allowed_path[storage_path.len()..].to_string();
                if relative.starts_with('/') {
                    relative.remove(0);
                }
                if relative.is_empty() {
                    continue;
                }

                let real_source = format!("{}/{}", real_base_path, relative);
                if anchor.is_mounted {
                    if !fs::is_directory(&real_source) {
                        log::warn!("real path missing, skip: {}", real_source);
                        continue;
                    }
                } else if !self.ensure_writable_mapped_directory(&real_source, self.app_uid) {
                    log::warn!("real path missing and mkdir failed: {}", real_source);
                    continue;
                }

                if !self.ensure_directory_exists(&allowed_path, true) {
                    log::warn!("mkdir allow failed: {}", allowed_path);
                    continue;
                }

                let mut is_restored_allowed_path = false;
                let _ = self.bind_mount_with_storage_aliases(
                    &real_source,
                    &allowed_path,
                    true,
                    super::PrimaryMountFailure::StopCurrentTarget,
                    None,
                    Some("allow alias restore failed"),
                    Some("allow alias restore ok"),
                    Some(&mut is_restored_allowed_path),
                );
                if is_restored_allowed_path {
                    log::info!("allow restored {}", allowed_path);
                }
            }
        }

        if !path_mappings.is_empty() {
            let resolved_mappings = self.resolve_path_mappings(path_mappings, &storage_path);
            log::info!(
                "map resolve in={} effective={}",
                path_mappings.len(),
                resolved_mappings.len()
            );
            let mut is_any_applied = false;
            let _ = self.apply_resolved_path_mappings(
                &resolved_mappings,
                &storage_path,
                &data_media_root,
                true,
                false,
                Some(&mut is_any_applied),
            );
            let _ = is_any_applied;
        }

        log::info!("redirect done");
        true
    }

    fn expand_allowed_real_path_wildcard(
        rule_path: &str,
        storage_path: &str,
        real_base_path: &str,
    ) -> Vec<String> {
        let Some(mut relative) = rule_path.strip_prefix(storage_path) else {
            return Vec::new();
        };
        if let Some(stripped) = relative.strip_prefix('/') {
            relative = stripped;
        }
        if relative.is_empty() {
            return Vec::new();
        }

        let segments: Vec<&str> = relative
            .split('/')
            .filter(|segment| !segment.is_empty())
            .collect();
        if segments.is_empty() {
            return Vec::new();
        }

        let mut expanded = Vec::new();
        Self::expand_allowed_real_path_segments(
            real_base_path,
            storage_path,
            &segments,
            0,
            &mut expanded,
        );
        expanded.sort();
        expanded.dedup();
        expanded
    }

    fn expand_allowed_real_path_segments(
        real_current: &str,
        storage_current: &str,
        segments: &[&str],
        index: usize,
        expanded: &mut Vec<String>,
    ) {
        if expanded.len() >= ALLOW_WILDCARD_EXPAND_LIMIT {
            return;
        }

        if index >= segments.len() {
            if fs::is_directory(real_current) {
                expanded.push(storage_current.to_string());
            }
            return;
        }

        let segment = segments[index];
        if has_allowed_path_wildcard(segment) {
            let Ok(entries) = std_fs::read_dir(real_current) else {
                return;
            };
            for entry in entries.flatten() {
                if expanded.len() >= ALLOW_WILDCARD_EXPAND_LIMIT {
                    return;
                }
                let Ok(file_type) = entry.file_type() else {
                    continue;
                };
                if !file_type.is_dir() || file_type.is_symlink() {
                    continue;
                }

                let name = entry.file_name().to_string_lossy().to_string();
                if !paths::matches(segment, &name, false) {
                    continue;
                }

                let real_next = paths::join(real_current, &name);
                let storage_next = paths::join(storage_current, &name);
                Self::expand_allowed_real_path_segments(
                    &real_next,
                    &storage_next,
                    segments,
                    index + 1,
                    expanded,
                );
            }
            return;
        }

        let real_next = paths::join(real_current, segment);
        if !fs::is_directory(&real_next) {
            return;
        }
        let storage_next = paths::join(storage_current, segment);
        Self::expand_allowed_real_path_segments(
            &real_next,
            &storage_next,
            segments,
            index + 1,
            expanded,
        );
    }

    pub fn apply_path_mappings_only(&mut self, path_mappings: &[PathMapping]) -> bool {
        if !self.ensure_mount_namespace_prepared() {
            log::error!("mount ns init failed");
            return false;
        }

        if path_mappings.is_empty() {
            log::info!("map-only: no mappings, skip");
            return true;
        }

        let storage_path = format!("/storage/emulated/{}", self.user_id);
        let data_media_root = format!("/data/media/{}", self.user_id);
        if !fs::is_directory(&data_media_root) {
            log::error!("data/media missing: {}", data_media_root);
            return false;
        }

        let resolved_mappings = self.resolve_path_mappings(path_mappings, &storage_path);
        log::info!(
            "map-only resolve in={} effective={}",
            path_mappings.len(),
            resolved_mappings.len()
        );

        let mut is_any_applied = false;
        let _ = self.apply_resolved_path_mappings(
            &resolved_mappings,
            &storage_path,
            &data_media_root,
            false,
            false,
            Some(&mut is_any_applied),
        );

        if !is_any_applied {
            log::warn!("map-only: nothing applied");
        } else {
            log::info!("map-only done");
        }

        true
    }
}

fn reduce_allowed_real_path_rules(rules: Vec<String>) -> Vec<String> {
    let mut effective: Vec<String> = Vec::with_capacity(rules.len());
    for rule in rules {
        let mut is_redundant = false;
        for kept in &effective {
            if paths::matches(kept, &rule, true) {
                is_redundant = true;
                break;
            }
        }
        if !is_redundant {
            effective.push(rule);
        }
    }
    effective
}

fn has_allowed_path_wildcard(path: &str) -> bool {
    path.contains('*') || path.contains('?')
}

// 锚点只在规则恢复期间存在；独立进程路径避免并发应用共用同一目录。
struct StorageAnchor {
    path: String,
    has_directory: bool,
    is_mounted: bool,
}

impl StorageAnchor {
    fn new(user_id: i32) -> Self {
        Self {
            path: format!("/storage/.srx_anchor_{}_{}", user_id, unsafe {
                libc::getpid()
            }),
            has_directory: false,
            is_mounted: false,
        }
    }
}

impl Drop for StorageAnchor {
    fn drop(&mut self) {
        if !self.has_directory {
            return;
        }
        let Ok(path) = std::ffi::CString::new(self.path.as_str()) else {
            return;
        };
        if self.is_mounted && unsafe { libc::umount2(path.as_ptr(), libc::MNT_DETACH) } != 0 {
            log::warn!(
                "anchor detach failed path={} error={}",
                self.path,
                std::io::Error::last_os_error()
            );
            return;
        }
        if let Err(error) = std_fs::remove_dir(&self.path) {
            log::warn!("anchor remove failed path={} error={}", self.path, error);
        }
    }
}
