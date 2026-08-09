use once_cell::sync::Lazy;
use parking_lot::RwLock;
use std::collections::HashMap;
use std::time::{Duration, Instant};

const ROUTE_CANDIDATE_LIMIT: usize = 3;
const FAILURE_COOLDOWN_BASE: Duration = Duration::from_secs(3);
const FAILURE_COOLDOWN_MAX: Duration = Duration::from_secs(30);

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct RouteKey {
    dc: i32,
    media: bool,
}

#[derive(Clone, Debug, Default)]
struct DomainHealth {
    failures: u32,
    last_success: u64,
    cooldown_until: Option<Instant>,
}

#[derive(Default)]
struct RouteTable {
    sequence: u64,
    preferred: HashMap<RouteKey, String>,
    health: HashMap<(RouteKey, String), DomainHealth>,
}

static ROUTES: Lazy<RwLock<RouteTable>> = Lazy::new(|| RwLock::new(RouteTable::default()));

pub fn reset() {
    *ROUTES.write() = RouteTable::default();
}

pub fn ordered_domains(
    dc: i32,
    media: bool,
    domains: &[String],
    persisted_preferred: &str,
) -> Vec<String> {
    let key = RouteKey { dc, media };
    let now = Instant::now();
    let table = ROUTES.read();
    let route_preferred = table.preferred.get(&key).map(String::as_str);

    let mut ranked: Vec<(String, bool, bool, bool, u32, u64)> = domains
        .iter()
        .filter(|domain| !domain.is_empty())
        .map(|domain| {
            let health = table
                .health
                .get(&(key, domain.clone()))
                .cloned()
                .unwrap_or_default();
            let cooling = health.cooldown_until.is_some_and(|until| until > now);
            (
                domain.clone(),
                route_preferred == Some(domain.as_str()),
                domain == persisted_preferred,
                cooling,
                health.failures,
                health.last_success,
            )
        })
        .collect();

    ranked.sort_by(|left, right| {
        right
            .1
            .cmp(&left.1)
            .then_with(|| left.3.cmp(&right.3))
            .then_with(|| right.2.cmp(&left.2))
            .then_with(|| left.4.cmp(&right.4))
            .then_with(|| right.5.cmp(&left.5))
            .then_with(|| left.0.cmp(&right.0))
    });
    ranked
        .into_iter()
        .take(ROUTE_CANDIDATE_LIMIT)
        .map(|entry| entry.0)
        .collect()
}

pub fn record_success(dc: i32, media: bool, domain: &str) {
    if domain.is_empty() {
        return;
    }
    let key = RouteKey { dc, media };
    let mut table = ROUTES.write();
    table.sequence = table.sequence.saturating_add(1);
    let sequence = table.sequence;
    table.preferred.insert(key, domain.to_string());
    table.health.insert(
        (key, domain.to_string()),
        DomainHealth {
            failures: 0,
            last_success: sequence,
            cooldown_until: None,
        },
    );
}

pub fn record_failure(dc: i32, media: bool, domain: &str) {
    if domain.is_empty() {
        return;
    }
    let key = RouteKey { dc, media };
    let mut table = ROUTES.write();
    let health = table.health.entry((key, domain.to_string())).or_default();
    health.failures = health.failures.saturating_add(1).min(8);
    let multiplier = 1u32 << health.failures.saturating_sub(1).min(3);
    let cooldown = (FAILURE_COOLDOWN_BASE * multiplier).min(FAILURE_COOLDOWN_MAX);
    health.cooldown_until = Some(Instant::now() + cooldown);
    if table.preferred.get(&key).map(String::as_str) == Some(domain) {
        table.preferred.remove(&key);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn domains() -> Vec<String> {
        vec![
            "one.example".into(),
            "two.example".into(),
            "three.example".into(),
            "four.example".into(),
        ]
    }

    #[test]
    fn successful_domain_is_preferred_only_for_its_route() {
        reset();
        record_success(2, false, "three.example");
        assert_eq!(
            ordered_domains(2, false, &domains(), "")[0],
            "three.example"
        );
        assert_ne!(
            ordered_domains(4, false, &domains(), "")[0],
            "three.example"
        );
    }

    #[test]
    fn failed_preferred_domain_moves_behind_healthy_candidates() {
        reset();
        record_success(2, false, "two.example");
        record_failure(2, false, "two.example");
        let ordered = ordered_domains(2, false, &domains(), "two.example");
        assert_ne!(ordered[0], "two.example");
        assert_eq!(ordered.len(), ROUTE_CANDIDATE_LIMIT);
    }
}
