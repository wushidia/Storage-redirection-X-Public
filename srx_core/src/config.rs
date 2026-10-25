use crate::domain::PathMapping;
use crate::platform::module_paths;
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

mod fingerprint;
mod ingest;
mod inspect;
mod merge;
mod raw_scan;
mod source;
pub mod watcher;

#[derive(Clone, Default)]
pub struct UserProfile {
    pub is_enabled: bool,
    pub is_mapping_mode_only: bool,
    pub allowed_real_paths: Vec<String>,
    pub excluded_real_paths: Vec<String>,
    pub sandboxed_paths: Vec<String>,
    pub read_only_paths: Vec<String>,
    pub path_mappings: Vec<PathMapping>,
}

impl UserProfile {
    pub fn resolved(&self, user_id: i32, app_data_dir: &str, redirect_target: &str) -> Self {
        let resolve = |raw: &str| {
            crate::platform::paths::resolve_user_path(
                &crate::platform::paths::resolve_placeholders(raw, app_data_dir, redirect_target),
                user_id,
            )
        };
        let rules = |items: &[String]| {
            items
                .iter()
                .map(|raw| {
                    if let Some(body) = raw.strip_prefix('!') {
                        format!("!{}", resolve(body))
                    } else {
                        resolve(raw)
                    }
                })
                .collect()
        };
        Self {
            is_enabled: self.is_enabled,
            is_mapping_mode_only: self.is_mapping_mode_only,
            allowed_real_paths: rules(&self.allowed_real_paths),
            excluded_real_paths: rules(&self.excluded_real_paths),
            sandboxed_paths: rules(&self.sandboxed_paths),
            read_only_paths: rules(&self.read_only_paths),
            path_mappings: self
                .path_mappings
                .iter()
                .map(|mapping| {
                    PathMapping::new(resolve(&mapping.request_path), resolve(&mapping.final_path))
                })
                .collect(),
        }
    }
}

#[derive(Clone)]
pub struct AppProfile {
    pub user_profiles: HashMap<i32, UserProfile>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RawUserEnabledState {
    Enabled,
    Disabled,
    Unavailable,
}

struct SettingsState {
    config_dir: String,
    is_file_monitor_enabled: bool,
    is_fuse_fixer_enabled: bool,
    apps: HashMap<String, AppProfile>,
    is_loaded: bool,
    should_log_summary: bool,
    last_fingerprint: u64,
    invalid_packages: HashSet<String>,
}

impl SettingsState {
    fn new() -> Self {
        Self {
            config_dir: module_paths::CONFIG_DIR.to_string(),
            is_file_monitor_enabled: false,
            is_fuse_fixer_enabled: false,
            apps: HashMap::new(),
            is_loaded: false,
            should_log_summary: true,
            last_fingerprint: 0,
            invalid_packages: HashSet::new(),
        }
    }
}

pub struct SettingsHub {
    state: Mutex<SettingsState>,
    config_version: AtomicU64,
    is_fuse_fixer_enabled: AtomicBool,
    has_read_only_rules: AtomicBool,
}

impl SettingsHub {
    fn new() -> Self {
        Self {
            state: Mutex::new(SettingsState::new()),
            config_version: AtomicU64::new(0),
            is_fuse_fixer_enabled: AtomicBool::new(false),
            has_read_only_rules: AtomicBool::new(false),
        }
    }

    pub fn instance() -> &'static SettingsHub {
        &SETTINGS_HUB
    }

    pub fn has_read_only_rules(&self) -> bool {
        self.has_read_only_rules.load(Ordering::Relaxed)
    }

    pub fn config_version(&self) -> u64 {
        self.config_version.load(Ordering::Relaxed)
    }

    fn bump_config_version(&self) {
        self.config_version.fetch_add(1, Ordering::Relaxed);
    }
}

static SETTINGS_HUB: Lazy<SettingsHub> = Lazy::new(SettingsHub::new);
