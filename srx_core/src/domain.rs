#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PathMapping {
    pub request_path: String,
    pub final_path: String,
}

impl PathMapping {
    pub fn new(request_path: String, final_path: String) -> Self {
        Self {
            request_path,
            final_path,
        }
    }
}
#[path = "domain/mapping.rs"]
mod mapping;
pub use mapping::{MappingError, compose_mount_mappings, map_path_once, resolve_mapping_chain};

pub fn matches_policy_paths(rules: &[String], path: &str) -> bool {
    let mut included = false;
    for rule in rules {
        if let Some(excluded) = rule.strip_prefix('!') {
            if crate::platform::paths::matches(excluded, path, true) {
                return false;
            }
        } else if crate::platform::paths::matches(rule, path, true) {
            included = true;
        }
    }
    included
}

pub fn project_read_only_rules(rules: &[String], mappings: &[PathMapping]) -> Vec<String> {
    let includes: Vec<_> = rules.iter().filter(|rule| !rule.starts_with('!')).collect();
    let mut effective = Vec::new();
    for rule in rules {
        let (excluded, body) = rule
            .strip_prefix('!')
            .map_or((false, rule.as_str()), |body| (true, body));
        if excluded
            && !includes.iter().any(|include| {
                include.as_str() == body
                    || include.starts_with(&format!("{body}/"))
                    || body.starts_with(&format!("{include}/"))
            })
        {
            continue;
        }
        effective.push(rule.clone());
        for mapping in mappings {
            let projected = if crate::platform::paths::matches(body, &mapping.final_path, true) {
                Some(mapping.request_path.clone())
            } else {
                body.strip_prefix(&format!("{}/", mapping.final_path))
                    .map(|suffix| format!("{}/{suffix}", mapping.request_path))
            };
            if let Some(path) = projected {
                effective.push(if excluded { format!("!{path}") } else { path });
            }
        }
    }
    effective
}

#[cfg(test)]
mod policy_tests {
    use super::*;

    #[test]
    fn readonly_exclusions_and_mapped_views_share_policy() {
        let rules = vec!["/Pictures".into(), "!/Pictures/Allowed".into()];
        let projected = project_read_only_rules(
            &rules,
            &[PathMapping::new("/Download".into(), "/Pictures".into())],
        );
        assert!(matches_policy_paths(&projected, "/Download/a"));
        assert!(!matches_policy_paths(&projected, "/Download/Allowed/a"));
    }

    #[test]
    fn unrelated_exclusion_cannot_cancel_a_readonly_mapping() {
        let rules = vec!["/Download".into(), "!/Pictures/Allowed".into()];
        let projected = project_read_only_rules(
            &rules,
            &[PathMapping::new(
                "/Download".into(),
                "/Pictures/Allowed".into(),
            )],
        );
        assert!(matches_policy_paths(&projected, "/Download/file"));
    }
}
