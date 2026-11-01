//! Version requirements as mods write them: Fabric/Quilt predicates (`>=1.21 <1.22`, `~26.3`,
//! `1.21.x`, `^0.6`) and Forge maven ranges (`[1.21.1,1.22)`). Only plain numeric versions are
//! compared; anything else (snapshots, unusual formats) is "unknown", so a check never reports a
//! problem it can't be sure of.

use std::cmp::Ordering;

/// `1.21.4`, `26.3`, `0.6.0-beta.2+mc1.21` → `[1, 21, 4]`… (pre-release and build parts dropped).
/// `None` for versions that don't start with numbers (snapshots like `26.4-snapshot-6` are
/// rejected by the caller beforehand).
pub fn numeric(v: &str) -> Option<Vec<u64>> {
    let core = v.trim().split(['+', '-']).next()?;
    let parts: Option<Vec<u64>> = core.split('.').map(|p| p.parse().ok()).collect();
    parts.filter(|p| !p.is_empty())
}

fn cmp(a: &[u64], b: &[u64]) -> Ordering {
    for i in 0..a.len().max(b.len()) {
        let o = a.get(i).unwrap_or(&0).cmp(b.get(i).unwrap_or(&0));
        if o != Ordering::Equal {
            return o;
        }
    }
    Ordering::Equal
}

/// A Minecraft-style version that can be compared: a release, not a snapshot or pre-release.
pub fn release(v: &str) -> Option<Vec<u64>> {
    (!v.contains('-') && !v.contains(char::is_alphabetic))
        .then(|| numeric(v))
        .flatten()
}

/// One Fabric term: `>=1.2`, `~1.21`, `^0.6`, `1.21.x`, `1.21.1`.
fn fabric_term(have: &[u64], term: &str) -> Option<bool> {
    let term = term.trim();
    if term.is_empty() || term == "*" {
        return Some(true);
    }
    let (op, rest) = [">=", "<=", ">", "<", "=", "~", "^"]
        .iter()
        .find_map(|op| term.strip_prefix(op).map(|r| (*op, r.trim())))
        .unwrap_or(("", term));
    // Wildcards: `1.21.x` / `1.21.*` match that prefix.
    if let Some(prefix) = rest
        .strip_suffix(".x")
        .or_else(|| rest.strip_suffix(".X"))
        .or_else(|| rest.strip_suffix(".*"))
    {
        let p = numeric(prefix)?;
        return Some(have.len() >= p.len() && have[..p.len()] == p[..]);
    }
    // An exact snapshot or pre-release can't be compared with a release.
    if op.is_empty() && rest.contains(char::is_alphabetic) {
        return None;
    }
    let want = numeric(rest)?;
    Some(match op {
        ">=" => cmp(have, &want) != Ordering::Less,
        "<=" => cmp(have, &want) != Ordering::Greater,
        ">" => cmp(have, &want) == Ordering::Greater,
        "<" => cmp(have, &want) == Ordering::Less,
        // Same major.minor (or major alone when only that is given), at least `want`.
        "~" => {
            let keep = if want.len() > 1 { 2 } else { 1 };
            cmp(have, &want) != Ordering::Less && have.iter().take(keep).eq(want.iter().take(keep))
        }
        // Same first component, at least `want`.
        "^" => cmp(have, &want) != Ordering::Less && have.first() == want.first(),
        _ => cmp(have, &want) == Ordering::Equal,
    })
}

/// Fabric predicate: space-separated terms that must all hold.
fn fabric(have: &[u64], predicate: &str) -> Option<bool> {
    let mut all = true;
    for term in predicate.split_whitespace() {
        all &= fabric_term(have, term)?;
    }
    Some(all)
}

/// Maven range: `[1.20,1.21)`, `[1.21]`, `[1.20,)`, `(,1.21]`; a bare version is a soft
/// preference and accepts anything.
fn maven(have: &[u64], range: &str) -> Option<bool> {
    let r = range.trim();
    if !(r.starts_with('[') || r.starts_with('(')) {
        return Some(true);
    }
    let incl_lo = r.starts_with('[');
    let incl_hi = r.ends_with(']');
    let inner = &r[1..r.len().saturating_sub(1)];
    let (lo, hi) = match inner.split_once(',') {
        Some((lo, hi)) => (lo.trim(), hi.trim()),
        None => (inner.trim(), inner.trim()),
    };
    if !lo.is_empty() {
        let lo = numeric(lo)?;
        let o = cmp(have, &lo);
        if o == Ordering::Less || (o == Ordering::Equal && !incl_lo) {
            return Some(false);
        }
    }
    if !hi.is_empty() {
        let hi = numeric(hi)?;
        let o = cmp(have, &hi);
        if o == Ordering::Greater || (o == Ordering::Equal && !incl_hi) {
            return Some(false);
        }
    }
    Some(true)
}

/// Does `version` satisfy any of `requirements`? `Some(true)` with no requirements; `None` when
/// it can't be decided.
pub fn satisfies(version: &[u64], requirements: &[String]) -> Option<bool> {
    if requirements.is_empty() {
        return Some(true);
    }
    let mut unknown = false;
    for r in requirements {
        let r = r.trim();
        let res = if r.starts_with('[') || r.starts_with('(') {
            maven(version, r)
        } else {
            fabric(version, r)
        };
        match res {
            Some(true) => return Some(true),
            Some(false) => {}
            None => unknown = true,
        }
    }
    if unknown { None } else { Some(false) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(v: &str, req: &[&str]) -> Option<bool> {
        let req: Vec<String> = req.iter().map(|s| (*s).to_owned()).collect();
        satisfies(&numeric(v).unwrap(), &req)
    }

    #[test]
    fn fabric_predicates() {
        assert_eq!(ok("26.3", &[]), Some(true));
        assert_eq!(ok("26.3", &["~26.3"]), Some(true));
        assert_eq!(ok("26.3.1", &["~26.3"]), Some(true));
        assert_eq!(ok("26.4", &["~26.3"]), Some(false));
        assert_eq!(ok("1.21.11", &[">=1.21 <1.22"]), Some(true));
        assert_eq!(ok("1.20.1", &[">=1.21 <1.22"]), Some(false));
        assert_eq!(ok("1.21.4", &["1.21.x"]), Some(true));
        assert_eq!(ok("1.21", &["1.21.0"]), Some(true));
        assert_eq!(ok("26.3", &["1.21.11", "26.3"]), Some(true));
        assert_eq!(ok("0.6.2", &["^0.6"]), Some(true));
        assert_eq!(ok("0.5.9", &[">=0.6.0-beta.1"]), Some(false));
        assert_eq!(ok("26.3", &[">=1.21-alpha.25.1.a"]), Some(true));
        assert_eq!(ok("26.3", &["26.4-snapshot-6"]), None);
    }

    #[test]
    fn maven_ranges() {
        assert_eq!(ok("1.21.1", &["[1.21.1,1.22)"]), Some(true));
        assert_eq!(ok("1.22", &["[1.21.1,1.22)"]), Some(false));
        assert_eq!(ok("1.20.1", &["[1.20.1]"]), Some(true));
        assert_eq!(ok("1.20.2", &["[1.20.1]"]), Some(false));
        assert_eq!(ok("1.21", &["[1.20,)"]), Some(true));
    }

    #[test]
    fn only_releases_compare() {
        assert!(release("26.3").is_some());
        assert!(release("1.21.11").is_some());
        assert!(release("26.4-snapshot-6").is_none());
        assert!(release("25w14a").is_none());
    }
}
