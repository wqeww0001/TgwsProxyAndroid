use once_cell::sync::Lazy;
use parking_lot::RwLock;
use rand::seq::SliceRandom;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub const ROUTE_CANDIDATE_LIMIT: usize = 12;
const FAILURE_COOLDOWN_BASE: Duration = Duration::from_secs(5);
const FAILURE_COOLDOWN_MAX: Duration = Duration::from_secs(90);

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
    global_health: HashMap<String, DomainHealth>,
}

static ROUTES: Lazy<RwLock<RouteTable>> = Lazy::new(|| RwLock::new(RouteTable::default()));
static PLAIN_WS_PREFERRED: AtomicBool = AtomicBool::new(false);

pub fn reset() {
    *ROUTES.write() = RouteTable::default();
    PLAIN_WS_PREFERRED.store(false, Ordering::Relaxed);
}

pub fn is_plain_ws_preferred() -> bool {
    PLAIN_WS_PREFERRED.load(Ordering::Relaxed)
}

pub fn set_plain_ws_preferred(prefer: bool) {
    PLAIN_WS_PREFERRED.store(prefer, Ordering::Relaxed);
}

pub fn has_healthy_preferred(dc: i32, media: bool, domain: &str) -> bool {
    if domain.is_empty() {
        return false;
    }
    let key = RouteKey { dc, media };
    let now = Instant::now();
    let table = ROUTES.read();
    if table.preferred.get(&key).map(String::as_str) != Some(domain) {
        return false;
    }
    let route_cooling = table
        .health
        .get(&(key, domain.to_string()))
        .and_then(|h| h.cooldown_until)
        .is_some_and(|u| u > now);
    let global_cooling = table
        .global_health
        .get(domain)
        .and_then(|h| h.cooldown_until)
        .is_some_and(|u| u > now);
    !route_cooling && !global_cooling
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

    // Shuffle base order first so equally-ranked domains are sampled across all IP subnets
    // instead of repeatedly hitting the first few alphabetical entries.
    let mut shuffled: Vec<String> = domains
        .iter()
        .filter(|d| !d.is_empty())
        .cloned()
        .collect();
    shuffled.shuffle(&mut rand::thread_rng());

    let mut ranked: Vec<(String, bool, bool, bool, u32, u64, u64)> = shuffled
        .into_iter()
        .map(|domain| {
            let route_h = table
                .health
                .get(&(key, domain.clone()))
                .cloned()
                .unwrap_or_default();
            let global_h = table
                .global_health
                .get(&domain)
                .cloned()
                .unwrap_or_default();
            let cooling = route_h.cooldown_until.is_some_and(|until| until > now)
                || global_h.cooldown_until.is_some_and(|until| until > now);
            let failures = route_h.failures.max(global_h.failures);
            let is_route_pref = !cooling && route_preferred == Some(domain.as_str());
            let is_persisted_pref = !cooling && failures == 0 && domain == persisted_preferred;
            (
                domain,
                is_route_pref,
                cooling,
                is_persisted_pref,
                failures,
                route_h.last_success,
                global_h.last_success,
            )
        })
        .collect();

    // Stable sort preserves the randomized tie-break order within each rank tier:
    // 1. Healthy route-preferred domain first
    // 2. Non-cooling domains before cooling ones
    // 3. Untried persisted domain before generic untried ones
    // 4. Fewer failures first
    // 5. Most recently succeeded (route, then global) first
    ranked.sort_by(|left, right| {
        right
            .1
            .cmp(&left.1)
            .then_with(|| left.2.cmp(&right.2))
            .then_with(|| right.3.cmp(&left.3))
            .then_with(|| left.4.cmp(&right.4))
            .then_with(|| right.5.cmp(&left.5))
            .then_with(|| right.6.cmp(&left.6))
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
    let healthy = DomainHealth {
        failures: 0,
        last_success: sequence,
        cooldown_until: None,
    };
    table.health.insert((key, domain.to_string()), healthy.clone());
    table.global_health.insert(domain.to_string(), healthy);
}

pub fn record_failure(dc: i32, media: bool, domain: &str) {
    if domain.is_empty() {
        return;
    }
    let key = RouteKey { dc, media };
    let mut table = ROUTES.write();
    let now = Instant::now();

    let route_h = table.health.entry((key, domain.to_string())).or_default();
    route_h.failures = route_h.failures.saturating_add(1).min(8);
    let route_mult = 1u32 << route_h.failures.saturating_sub(1).min(4);
    let route_cd = (FAILURE_COOLDOWN_BASE * route_mult).min(FAILURE_COOLDOWN_MAX);
    route_h.cooldown_until = Some(now + route_cd);

    let global_h = table.global_health.entry(domain.to_string()).or_default();
    global_h.failures = global_h.failures.saturating_add(1).min(8);
    let global_mult = 1u32 << global_h.failures.saturating_sub(1).min(4);
    let global_cd = (FAILURE_COOLDOWN_BASE * global_mult).min(FAILURE_COOLDOWN_MAX);
    global_h.cooldown_until = Some(now + global_cd);

    if table.preferred.get(&key).map(String::as_str) == Some(domain) {
        table.preferred.remove(&key);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use parking_lot::Mutex;

    // Serialize unit tests that mutate the process-global ROUTES/PLAIN_WS_PREFERRED state.
    static TEST_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

    fn domains() -> Vec<String> {
        (1..=16).map(|i| format!("d{}.example", i)).collect()
    }

    #[test]
    fn successful_domain_is_preferred_for_its_route() {
        let _guard = TEST_LOCK.lock();
        reset();
        record_success(2, false, "d3.example");
        assert_eq!(
            ordered_domains(2, false, &domains(), "")[0],
            "d3.example"
        );
        assert!(has_healthy_preferred(2, false, "d3.example"));
        assert!(!has_healthy_preferred(4, false, "d3.example"));
    }

    #[test]
    fn failed_domain_moves_behind_healthy_candidates_globally() {
        let _guard = TEST_LOCK.lock();
        reset();
        let list = vec![
            "one.example".into(),
            "two.example".into(),
            "three.example".into(),
            "four.example".into(),
        ];
        record_success(2, false, "two.example");
        record_failure(2, false, "two.example");

        let ordered_dc2 = ordered_domains(2, false, &list, "two.example");
        assert_eq!(ordered_dc2.last().map(String::as_str), Some("two.example"));
        assert_ne!(ordered_dc2[0], "two.example");

        let ordered_dc5 = ordered_domains(5, false, &list, "two.example");
        assert_eq!(ordered_dc5.last().map(String::as_str), Some("two.example"));
        assert_ne!(ordered_dc5[0], "two.example");
    }

    #[test]
    fn returns_up_to_route_candidate_limit() {
        let _guard = TEST_LOCK.lock();
        reset();
        let ordered = ordered_domains(2, false, &domains(), "");
        assert_eq!(ordered.len(), ROUTE_CANDIDATE_LIMIT);
        assert!(ordered.len() > 3);
    }

    #[test]
    fn plain_ws_preference_resets_cleanly() {
        let _guard = TEST_LOCK.lock();
        reset();
        assert!(!is_plain_ws_preferred());
        set_plain_ws_preferred(true);
        assert!(is_plain_ws_preferred());
        reset();
        assert!(!is_plain_ws_preferred());
    }
}
