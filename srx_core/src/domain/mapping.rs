use super::PathMapping;
use std::collections::{HashMap, HashSet};

pub const MAX_MAPPING_DEPTH: usize = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MappingError {
    Cycle,
    DepthExceeded,
    MissingCapture,
    TooManyMatches,
    UnreadablePath,
}

pub fn map_path_once(path: &str, mapping: &PathMapping) -> Result<Option<String>, MappingError> {
    let pattern = mapping.request_path.trim_end_matches('/');
    if !pattern.contains(['*', '?']) {
        if mapping.final_path.contains(['*', '?']) {
            return Err(MappingError::MissingCapture);
        }
        let suffix = if path == pattern {
            ""
        } else if path.starts_with(pattern) && path.as_bytes().get(pattern.len()) == Some(&b'/') {
            &path[pattern.len()..]
        } else {
            return Ok(None);
        };
        return Ok(Some(format!("{}{suffix}", mapping.final_path)));
    }
    let roots: Vec<_> = pattern.split('/').collect();
    let parts: Vec<_> = path.split('/').collect();
    if roots.len() > parts.len() {
        return Ok(None);
    }
    let mut captures = Vec::new();
    for (rule, name) in roots.iter().zip(parts.iter()) {
        let Some(mut values) = capture_segment(rule, name) else {
            return Ok(None);
        };
        captures.append(&mut values);
    }
    let mut values = captures.iter();
    let mut target = String::new();
    for ch in mapping.final_path.chars() {
        if matches!(ch, '*' | '?') {
            target.push_str(values.next().ok_or(MappingError::MissingCapture)?);
        } else {
            target.push(ch);
        }
    }
    if parts.len() > roots.len() {
        target.push('/');
        target.push_str(&parts[roots.len()..].join("/"));
    }
    Ok(Some(target))
}

// 后缀匹配表保证多个 * 的捕获不会指数回溯；通配符不跨目录段。
fn capture_segment(pattern: &str, name: &str) -> Option<Vec<String>> {
    if !pattern.contains(['*', '?']) {
        return (pattern == name).then(Vec::new);
    }
    let rule: Vec<_> = pattern.chars().collect();
    let text: Vec<_> = name.chars().collect();
    let width = text.len() + 1;
    let mut suffixes = vec![false; (rule.len() + 1) * width];
    suffixes[rule.len() * width + text.len()] = true;
    for p in (0..rule.len()).rev() {
        for t in (0..=text.len()).rev() {
            suffixes[p * width + t] = match rule[p] {
                '*' => {
                    suffixes[(p + 1) * width + t] || (t < text.len() && suffixes[p * width + t + 1])
                }
                '?' => t < text.len() && suffixes[(p + 1) * width + t + 1],
                ch => t < text.len() && ch == text[t] && suffixes[(p + 1) * width + t + 1],
            };
        }
    }
    if !suffixes[0] {
        return None;
    }
    let mut t = 0;
    let mut captures = Vec::new();
    for (p, ch) in rule.iter().enumerate() {
        match ch {
            '*' => {
                let mut end = text.len();
                while !suffixes[(p + 1) * width + end] {
                    end -= 1;
                }
                captures.push(text[t..end].iter().collect());
                t = end;
            }
            '?' => {
                captures.push(text[t].to_string());
                t += 1;
            }
            _ => t += 1,
        }
    }
    Some(captures)
}

pub fn resolve_mapping_chain(
    path: &str,
    mappings: &[PathMapping],
) -> Result<Option<String>, MappingError> {
    let mut current = path.to_string();
    let mut visited = HashSet::from([current.clone()]);
    let mut changed = false;
    for _ in 0..=MAX_MAPPING_DEPTH {
        let mut best: Option<(usize, usize, String)> = None;
        for mapping in mappings {
            let Some(next) = map_path_once(&current, mapping)? else {
                continue;
            };
            let rank = (
                mapping.request_path.matches('/').count(),
                mapping
                    .request_path
                    .chars()
                    .filter(|ch| !matches!(ch, '*' | '?'))
                    .count(),
            );
            if best
                .as_ref()
                .is_none_or(|(depth, literal, _)| rank > (*depth, *literal))
            {
                best = Some((rank.0, rank.1, next));
            }
        }
        let Some((_, _, next)) = best else {
            return Ok(changed.then_some(current));
        };
        if !visited.insert(next.clone()) {
            return Err(MappingError::Cycle);
        }
        current = next;
        changed = true;
    }
    Err(MappingError::DepthExceeded)
}

// 把父映射输出中的子映射投影回请求侧，供 bind mount 按父到子顺序覆盖。
pub fn compose_mount_mappings(mappings: &[PathMapping]) -> Result<Vec<PathMapping>, MappingError> {
    validate_mount_dependencies(mappings)?;
    let mut effective: HashMap<String, String> = mappings
        .iter()
        .map(|item| (item.request_path.clone(), item.final_path.clone()))
        .collect();
    for _ in 0..MAX_MAPPING_DEPTH {
        let snapshot = effective.clone();
        let mut changed = false;
        for (request, target) in &snapshot {
            for child in mappings {
                let Some(suffix) = child.request_path.strip_prefix(target) else {
                    continue;
                };
                if !suffix.starts_with('/') {
                    continue;
                }
                let derived = format!("{request}{suffix}");
                if let std::collections::hash_map::Entry::Vacant(entry) = effective.entry(derived) {
                    entry.insert(child.final_path.clone());
                    changed = true;
                }
            }
        }
        if !changed {
            break;
        }
    }
    let mut result = Vec::with_capacity(effective.len());
    for (request, target) in effective {
        let resolved = resolve_mapping_chain(&target, mappings)?.unwrap_or(target);
        if request == resolved {
            return Err(MappingError::Cycle);
        }
        result.push(PathMapping::new(request, resolved));
    }
    result.sort_by(|a, b| {
        a.request_path
            .len()
            .cmp(&b.request_path.len())
            .then(a.request_path.cmp(&b.request_path))
    });
    Ok(result)
}

fn validate_mount_dependencies(mappings: &[PathMapping]) -> Result<(), MappingError> {
    fn overlaps(left: &str, right: &str) -> bool {
        left == right
            || left.starts_with(&format!("{right}/"))
            || right.starts_with(&format!("{left}/"))
    }
    fn visit(
        index: usize,
        depth: usize,
        edges: &[Vec<usize>],
        states: &mut [u8],
        heights: &mut [usize],
    ) -> Result<(), MappingError> {
        if states[index] == 1 {
            return Err(MappingError::Cycle);
        }
        if depth >= MAX_MAPPING_DEPTH {
            return Err(MappingError::DepthExceeded);
        }
        if states[index] == 2 {
            return if depth + heights[index] > MAX_MAPPING_DEPTH {
                Err(MappingError::DepthExceeded)
            } else {
                Ok(())
            };
        }
        states[index] = 1;
        let mut height = 1;
        for child in &edges[index] {
            visit(*child, depth + 1, edges, states, heights)?;
            height = height.max(heights[*child] + 1);
        }
        if depth + height > MAX_MAPPING_DEPTH {
            return Err(MappingError::DepthExceeded);
        }
        heights[index] = height;
        states[index] = 2;
        Ok(())
    }
    let edges: Vec<Vec<usize>> = mappings
        .iter()
        .map(|mapping| {
            mappings
                .iter()
                .enumerate()
                .filter_map(|(index, candidate)| {
                    overlaps(&mapping.final_path, &candidate.request_path).then_some(index)
                })
                .collect()
        })
        .collect();
    let mut states = vec![0; mappings.len()];
    let mut heights = vec![0; mappings.len()];
    for index in 0..mappings.len() {
        visit(index, 0, &edges, &mut states, &mut heights)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mapping(from: &str, to: &str) -> PathMapping {
        PathMapping::new(from.into(), to.into())
    }

    #[test]
    fn chains_and_nested_mount_projections_agree() {
        let rules = vec![mapping("/A", "/B"), mapping("/B/sub", "/C")];
        assert_eq!(
            resolve_mapping_chain("/A/sub/file", &rules),
            Ok(Some("/C/file".into()))
        );
        let mounts = compose_mount_mappings(&rules).unwrap();
        assert!(mounts.contains(&mapping("/A/sub", "/C")));
    }

    #[test]
    fn wildcards_capture_unicode_without_crossing_directories() {
        let rule = mapping("/Download/*/item?", "/Archive/*/saved?");
        assert_eq!(
            map_path_once("/Download/图片/item甲/a", &rule),
            Ok(Some("/Archive/图片/saved甲/a".into()))
        );
        assert_eq!(map_path_once("/Download/a/b/item甲", &rule), Ok(None));
    }

    #[test]
    fn cycles_and_expansion_limits_are_errors() {
        assert_eq!(
            resolve_mapping_chain("/A/file", &[mapping("/A", "/B"), mapping("/B", "/A")]),
            Err(MappingError::Cycle)
        );
        assert_eq!(
            resolve_mapping_chain("/A/file", &[mapping("/A", "/A/more")]),
            Err(MappingError::DepthExceeded)
        );
        assert_eq!(
            compose_mount_mappings(&[mapping("/A", "/B"), mapping("/B/sub", "/A")]),
            Err(MappingError::Cycle)
        );
    }

    #[test]
    fn longest_concrete_rule_wins_over_a_wildcard() {
        let rules = [mapping("/A/*", "/generic"), mapping("/A/name", "/specific")];
        assert_eq!(
            resolve_mapping_chain("/A/name/f", &rules),
            Ok(Some("/specific/f".into()))
        );
    }

    #[test]
    fn mount_and_lookup_share_the_same_chain_depth() {
        let rules: Vec<_> = (0..MAX_MAPPING_DEPTH)
            .map(|index| mapping(&format!("/p{index}"), &format!("/p{}", index + 1)))
            .collect();
        assert!(compose_mount_mappings(&rules).is_ok());
        assert_eq!(
            resolve_mapping_chain("/p0/f", &rules),
            Ok(Some(format!("/p{MAX_MAPPING_DEPTH}/f")))
        );
        let mut too_deep = rules;
        too_deep.push(mapping(&format!("/p{MAX_MAPPING_DEPTH}"), "/last"));
        assert_eq!(
            compose_mount_mappings(&too_deep),
            Err(MappingError::DepthExceeded)
        );
        too_deep.reverse();
        assert_eq!(
            compose_mount_mappings(&too_deep),
            Err(MappingError::DepthExceeded)
        );
    }
}
