use crate::config::*;
use crate::ws::{is_http_status_error, ws_connect_once_mode, RawWebSocket, WsError};
use crate::{ldebug, lerror, linfo, lwarn};
use serde::Deserialize;
use std::path::PathBuf;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;

use once_cell::sync::Lazy;
static CFPROXY_SEM: Lazy<Semaphore> = Lazy::new(|| Semaphore::new(CFPROXY_GLOBAL_PARALLEL));

// ---------------------------------------------------------------------------
// Domain decoding
// ---------------------------------------------------------------------------

pub fn decode_cf_domain(s: &str) -> String {
    if !s.ends_with(".com") {
        return s.to_string();
    }
    let suffix = ".co.uk";
    let p = &s[..s.len() - 4];
    let mut n = 0i32;
    for c in p.chars() {
        if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
            n += 1;
        }
    }
    let mut result: Vec<u8> = Vec::new();
    for &c in p.as_bytes() {
        if c >= b'a' && c <= b'z' {
            let v = (((c - b'a') as i32 - n % 26 + 26) % 26) as u8 + b'a';
            result.push(v);
        } else if c >= b'A' && c <= b'Z' {
            let v = (((c - b'A') as i32 - n % 26 + 26) % 26) as u8 + b'A';
            result.push(v);
        } else {
            result.push(c);
        }
    }
    let mut out = String::from_utf8_lossy(&result).to_string();
    out.push_str(suffix);
    out
}

pub fn normalize_cf_domain(s: &str) -> String {
    let raw = s.trim().to_lowercase();
    let stripped = raw
        .strip_prefix("https://")
        .or_else(|| raw.strip_prefix("http://"))
        .unwrap_or(&raw);
    let mut host = stripped
        .split('/')
        .next()
        .unwrap_or_default()
        .split(':')
        .next()
        .unwrap_or_default()
        .to_string();
    while host.ends_with('.') {
        host.pop();
    }
    let valid = host.len() <= 253
        && host.contains('.')
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label
                    .as_bytes()
                    .first()
                    .is_some_and(u8::is_ascii_alphanumeric)
                && label
                    .as_bytes()
                    .last()
                    .is_some_and(u8::is_ascii_alphanumeric)
                && label
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        });
    if !valid {
        return String::new();
    }
    host
}

pub fn decode_and_normalize_cf_domain(s: &str) -> String {
    normalize_cf_domain(&decode_cf_domain(s.trim()))
}

pub fn default_cfproxy_domains() -> Vec<String> {
    let mut domains = Vec::with_capacity(CFPROXY_ENC.len());
    for enc in CFPROXY_ENC {
        let d = decode_and_normalize_cf_domain(enc);
        if !d.is_empty() {
            domains.push(d);
        }
    }
    domains
}

pub fn merge_cfproxy_domains(lists: &[Vec<String>]) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    let mut merged = Vec::new();
    for list in lists {
        for raw in list {
            let d = normalize_cf_domain(raw);
            if d.is_empty() || seen.contains(&d) {
                continue;
            }
            seen.insert(d.clone());
            merged.push(d);
        }
    }
    merged
}

// ---------------------------------------------------------------------------
// 429 cooldown logic
// ---------------------------------------------------------------------------

pub fn clear_cfproxy_429_cooldowns() {
    CFPROXY_429.write().clear();
}

pub fn clear_cfproxy_429_cooldown(domain: &str) {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return;
    }
    CFPROXY_429.write().remove(&d);
}

pub fn retry_after_delay(err: &WsError) -> Duration {
    let h = match err.handshake() {
        Some(h) => h,
        None => return Duration::ZERO,
    };
    let retry_after = h.headers.get("retry-after").map(|s| s.trim()).unwrap_or("");
    if retry_after.is_empty() {
        return Duration::ZERO;
    }
    if let Ok(seconds) = retry_after.parse::<i64>() {
        if seconds > 0 {
            return Duration::from_secs(seconds as u64);
        }
    }
    // http date parse (best-effort): пропускаем, как маловероятный кейс
    Duration::ZERO
}

pub fn next_cfproxy_429_cooldown_delay(prev: &Cfproxy429State, retry_after: Duration) -> Duration {
    if retry_after > Duration::ZERO {
        if retry_after > CFPROXY_429_MAX_COOLDOWN {
            return CFPROXY_429_MAX_COOLDOWN;
        }
        return retry_after;
    }
    let mut strikes = prev.strikes;
    let expired = match prev.until {
        None => true,
        Some(u) => u.elapsed() > CFPROXY_429_MAX_COOLDOWN,
    };
    if expired {
        strikes = 0;
    }
    let mut delay = CFPROXY_429_COOLDOWN;
    for _ in 0..strikes {
        delay *= 2;
        if delay >= CFPROXY_429_MAX_COOLDOWN {
            return CFPROXY_429_MAX_COOLDOWN;
        }
    }
    if delay > CFPROXY_429_MAX_COOLDOWN {
        return CFPROXY_429_MAX_COOLDOWN;
    }
    delay
}

pub fn mark_cfproxy_429_cooldown(domain: &str, err: &WsError) {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return;
    }
    let retry_after = retry_after_delay(err);
    let mut map = CFPROXY_429.write();
    let prev = map.get(&d).cloned().unwrap_or_default();
    let delay = next_cfproxy_429_cooldown_delay(&prev, retry_after);
    let mut strikes = prev.strikes + 1;
    let expired = match prev.until {
        None => true,
        Some(u) => u.elapsed() > CFPROXY_429_MAX_COOLDOWN,
    };
    if expired {
        strikes = 1;
    }
    map.insert(
        d.clone(),
        Cfproxy429State {
            until: Some(Instant::now() + delay),
            strikes,
        },
    );
    drop(map);
    ldebug!(
        " CF cooldown {}: {:.0}s after 429",
        d,
        delay.as_secs_f64().ceil()
    );
}

pub fn cfproxy_429_cooldown_remaining(domain: &str) -> Duration {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return Duration::ZERO;
    }
    let map = CFPROXY_429.read();
    let state = match map.get(&d) {
        Some(s) => s.clone(),
        None => return Duration::ZERO,
    };
    drop(map);
    let until = match state.until {
        Some(u) => u,
        None => return Duration::ZERO,
    };
    let now = Instant::now();
    if until <= now {
        CFPROXY_429.write().remove(&d);
        return Duration::ZERO;
    }
    until - now
}

pub async fn acquire_cfproxy_attempt_slot() -> Option<tokio::sync::SemaphorePermit<'static>> {
    CFPROXY_SEM.acquire().await.ok()
}

// ---------------------------------------------------------------------------
// Cache files
// ---------------------------------------------------------------------------

fn cfproxy_cache_path() -> Option<PathBuf> {
    let dir = CFPROXY.read().cache_dir.trim().to_string();
    if dir.is_empty() {
        return None;
    }
    Some(PathBuf::from(dir).join(CFPROXY_CACHE_FILE_NAME))
}

fn cfproxy_active_domain_path() -> Option<PathBuf> {
    let dir = CFPROXY.read().cache_dir.trim().to_string();
    if dir.is_empty() {
        return None;
    }
    Some(PathBuf::from(dir).join(CFPROXY_ACTIVE_FILE_NAME))
}

fn load_cfproxy_domains_from_cache() -> Vec<String> {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return Vec::new(),
    };
    let data = match std::fs::read_to_string(&path) {
        Ok(d) => d,
        Err(_) => return Vec::new(),
    };
    let list: Vec<String> = data.split('\n').map(|s| s.to_string()).collect();
    merge_cfproxy_domains(&[list])
}

fn load_active_cfproxy_domain() -> String {
    let path = match cfproxy_active_domain_path() {
        Some(p) => p,
        None => return String::new(),
    };
    let data = match std::fs::read_to_string(&path) {
        Ok(d) => d,
        Err(_) => return String::new(),
    };
    normalize_cf_domain(&data)
}

fn save_cfproxy_domains_to_cache(domains: &[String]) {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return,
    };
    if domains.is_empty() {
        return;
    }
    if let Some(parent) = path.parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            ldebug!(" CF: кеш создать не удалось: {}", e);
            return;
        }
    }
    let data = domains.join("\n");
    if let Err(e) = std::fs::write(&path, data) {
        ldebug!(" CF: кеш сохранить не удалось: {}", e);
    }
}

fn save_active_cfproxy_domain(domain: &str) {
    let path = match cfproxy_active_domain_path() {
        Some(p) => p,
        None => return,
    };
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return;
    }
    if let Some(parent) = path.parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            ldebug!(" CF: active-domain кеш создать не удалось: {}", e);
            return;
        }
    }
    if let Err(e) = std::fs::write(&path, d) {
        ldebug!(" CF: active-domain кеш сохранить не удалось: {}", e);
    }
}

fn should_refresh_cfproxy_domains() -> bool {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return true,
    };
    let meta = match std::fs::metadata(&path) {
        Ok(m) => m,
        Err(_) => return true,
    };
    let modified = match meta.modified() {
        Ok(t) => t,
        Err(_) => return true,
    };
    match modified.elapsed() {
        Ok(elapsed) => elapsed >= CFPROXY_REFRESH_INTERVAL,
        Err(_) => true,
    }
}

fn set_active_cfproxy_domain_locked(cfg: &mut CfproxyConfig, preferred: &str) {
    if cfg.domains.is_empty() {
        cfg.active = String::new();
        return;
    }
    let preferred = normalize_cf_domain(preferred);
    for d in &cfg.domains {
        if *d == preferred {
            cfg.active = d.clone();
            return;
        }
    }
    cfg.active = cfg.domains[0].clone();
}

pub fn init_cfproxy_domains() {
    let defaults = default_cfproxy_domains();
    let cached = load_cfproxy_domains_from_cache();
    let persisted_active = load_active_cfproxy_domain();

    let mut cfg = CFPROXY.write();
    if !cfg.user_domain.is_empty() {
        let ud = cfg.user_domain.clone();
        cfg.domains = vec![ud.clone()];
        cfg.active = ud;
        return;
    }

    if !cached.is_empty() {
        let n = cached.len();
        cfg.domains = merge_cfproxy_domains(&[cached, defaults]);
        drop(cfg);
        linfo!(" CF: кеш доменов загружен ({} шт.)", n);
        let mut cfg = CFPROXY.write();
        set_active_cfproxy_domain_locked(&mut cfg, &persisted_active);
    } else {
        cfg.domains = defaults;
        set_active_cfproxy_domain_locked(&mut cfg, &persisted_active);
    }
}

pub fn start_cfproxy_refresh() {
    if !should_refresh_cfproxy_domains() {
        ldebug!(" CF: кеш свежий, пропускаю обновление списка");
        return;
    }
    tokio::spawn(async move {
        for _ in 0..3 {
            if try_refresh_cfproxy_domains().await {
                return;
            }
            tokio::time::sleep(Duration::from_secs(10)).await;
        }
        ldebug!(" CF: обновить список доменов не удалось, остаюсь на кеше/встроенном списке");
    });
}

pub async fn try_refresh_cfproxy_domains() -> bool {
    let has_user = !CFPROXY.read().user_domain.is_empty();
    if has_user {
        return true;
    }

    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
    {
        Ok(c) => c,
        Err(_) => return false,
    };

    let resp = match client
        .get(CFPROXY_DOMAINS_URL)
        .header("User-Agent", "Mozilla/5.0 tg-ws-proxy-android")
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => {
            ldebug!(" CF: GitHub недоступен: {}", e);
            return false;
        }
    };
    if resp.status().as_u16() != 200 {
        ldebug!(" CF: GitHub вернул {}", resp.status().as_u16());
        return false;
    }
    let body = match resp.text().await {
        Ok(b) if b.len() <= 64 * 1024 => b,
        Ok(_) => {
            ldebug!(" CF: список доменов превышает лимит 64 КБ");
            return false;
        }
        Err(e) => {
            ldebug!(" CF: список доменов прочитать не удалось: {}", e);
            return false;
        }
    };

    let mut new_domains = Vec::new();
    for line in body.lines().take(100) {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let d = decode_and_normalize_cf_domain(line);
        if !d.is_empty() {
            new_domains.push(d);
        }
    }

    if !new_domains.is_empty() {
        let merged = merge_cfproxy_domains(&[new_domains.clone(), default_cfproxy_domains()]);
        {
            let mut cfg = CFPROXY.write();
            if !cfg.user_domain.is_empty() {
                return true;
            }
            let current_active = cfg.active.clone();
            cfg.domains = merged.clone();
            set_active_cfproxy_domain_locked(&mut cfg, &current_active);
        }
        let merged_for_cache = merged.clone();
        let _ = tokio::task::spawn_blocking(move || {
            save_cfproxy_domains_to_cache(&merged_for_cache);
        })
        .await;
        linfo!(" CF: список доменов обновлен ({} шт.)", new_domains.len());
        return true;
    }
    false
}

// ---------------------------------------------------------------------------
// DNS over HTTPS (DoH) resolve
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct DohAnswer {
    #[serde(rename = "data")]
    data: String,
    #[serde(rename = "type")]
    type_: i32,
}
#[derive(Deserialize)]
struct DohResponse {
    #[serde(rename = "Answer", default)]
    answer: Vec<DohAnswer>,
}

static DOH_CACHE: Lazy<parking_lot::RwLock<std::collections::HashMap<String, (String, Instant)>>> =
    Lazy::new(|| parking_lot::RwLock::new(std::collections::HashMap::new()));

fn pick_preferred_ip(candidates: &[String]) -> String {
    let mut fallback_v6 = String::new();
    for c in candidates {
        let c = c.trim();
        if let Ok(ip) = c.parse::<std::net::IpAddr>() {
            match ip {
                std::net::IpAddr::V4(v4) => return v4.to_string(),
                std::net::IpAddr::V6(v6) => {
                    if fallback_v6.is_empty() {
                        fallback_v6 = v6.to_string();
                    }
                }
            }
        }
    }
    fallback_v6
}

pub async fn resolve_doh(domain: &str) -> Option<String> {
    if let Some((ip, exp)) = DOH_CACHE.read().get(domain).cloned() {
        if Instant::now() < exp {
            return Some(ip);
        }
    }

    let endpoints = [
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query",
        "https://dns.quad9.net/dns-query",
        "https://dns.adguard-dns.com/dns-query",
    ];

    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(1500))
        .build()
        .ok()?;

    let (tx, mut rx) = tokio::sync::mpsc::channel(endpoints.len() + 1);
    let mut tasks = Vec::new();

    for u in endpoints {
        let client = client.clone();
        let domain = domain.to_string();
        let tx = tx.clone();
        tasks.push(tokio::spawn(async move {
            let full = format!("{}?name={}&type=A", u, domain);
            if let Ok(resp) = client
                .get(&full)
                .header("Accept", "application/dns-json")
                .send()
                .await
            {
                if resp.status().as_u16() == 200 {
                    if let Ok(r) = resp.json::<DohResponse>().await {
                        for ans in r.answer {
                            if ans.type_ == 1 {
                                let _ = tx.send(Some(ans.data)).await;
                                return;
                            }
                        }
                    }
                }
            }
            let _ = tx.send(None).await;
        }));
    }

    // UDP-резолв через системный resolver как дополнительный кандидат
    {
        let domain2 = domain.to_string();
        let tx = tx.clone();
        tasks.push(tokio::spawn(async move {
            let host = format!("{}:443", domain2);
            if let Ok(Ok(addrs)) =
                tokio::time::timeout(Duration::from_millis(1500), tokio::net::lookup_host(host))
                    .await
            {
                let ips: Vec<String> = addrs.map(|a| a.ip().to_string()).collect();
                let p = pick_preferred_ip(&ips);
                if !p.is_empty() {
                    let _ = tx.send(Some(p)).await;
                    return;
                }
            }
            let _ = tx.send(None).await;
        }));
    }

    drop(tx); // Чтобы rx.recv() завершился, когда все таски завершатся

    let deadline = tokio::time::sleep(Duration::from_millis(1500));
    tokio::pin!(deadline);

    let mut final_ip = None;
    loop {
        tokio::select! {
            _ = &mut deadline => {
                break;
            }
            msg = rx.recv() => {
                match msg {
                    Some(Some(ip)) => {
                        final_ip = Some(ip);
                        break;
                    }
                    Some(None) => {} // Таска ничего не нашла
                    None => break,   // Все таски завершились
                }
            }
        }
    }

    // Отменяем все незавершенные фоновые таски (исправление утечки)
    for t in tasks {
        t.abort();
    }

    if let Some(ip) = &final_ip {
        DOH_CACHE.write().insert(
            domain.to_string(),
            (ip.clone(), Instant::now() + Duration::from_secs(300)),
        );
    }

    final_ip
}

// ---------------------------------------------------------------------------
// cfConnectDomain
// ---------------------------------------------------------------------------

fn new_timed_attempt_timeout(base: Duration, phase: Duration) -> Duration {
    let mut eff = base;
    if eff <= Duration::ZERO {
        eff = Duration::from_secs(5);
    }
    if eff > phase {
        eff = phase;
    }
    eff
}

async fn cf_connect_domain_mode(
    domain: &str,
    path: &str,
    phase_timeout: Duration,
    secure: bool,
    cached_ip: Option<&str>,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    let host_timeout = new_timed_attempt_timeout(phase_timeout, phase_timeout);
    match ws_connect_once_mode(domain, domain, path, host_timeout, secure).await {
        Ok(ws) => return (Some(ws), String::new(), None),
        Err(host_err) => {
            if is_http_status_error(&host_err, 429) {
                return (None, String::new(), Some(host_err));
            }
            let resolved_ip = match cached_ip {
                Some(ip) if !ip.is_empty() => ip.to_string(),
                _ => resolve_doh(domain).await.unwrap_or_default(),
            };
            if resolved_ip.is_empty() {
                ldebug!(" CF DNS {} -> no result", domain);
                return (None, String::new(), Some(host_err));
            }
            ldebug!(" CF DNS {} -> {}", domain, resolved_ip);
            let ip_timeout = new_timed_attempt_timeout(phase_timeout, phase_timeout);
            match ws_connect_once_mode(&resolved_ip, domain, path, ip_timeout, secure).await {
                Ok(ws) => (Some(ws), resolved_ip, None),
                Err(e) => (None, resolved_ip, Some(e)),
            }
        }
    }
}

pub async fn cf_connect_domain(
    domain: &str,
    path: &str,
    timeout: f64,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    let path = if path.is_empty() { "/apiws" } else { path };

    let attempt_timeout = crate::ws::ws_connect_timeout(timeout);
    let mut phase_timeout = attempt_timeout;
    if phase_timeout > CFPROXY_DIAL_PHASE_TIMEOUT {
        phase_timeout = CFPROXY_DIAL_PHASE_TIMEOUT;
    }

    let force_plain = CFPROXY_DISABLE_TLS.load(Ordering::Relaxed);
    if force_plain {
        return cf_connect_domain_mode(domain, path, phase_timeout, false, None).await;
    }

    let prefer_plain = crate::router::is_plain_ws_preferred();
    let first_secure = !prefer_plain;

    let (ws, resolved_ip, first_err) =
        cf_connect_domain_mode(domain, path, phase_timeout, first_secure, None).await;
    if ws.is_some() {
        return (ws, resolved_ip, None);
    }

    // Do not retry alternate port on HTTP 429 rate-limit.
    if let Some(ref e) = first_err {
        if is_http_status_error(e, 429) {
            return (None, resolved_ip, first_err);
        }
    }

    // Automatic transport fallback: if TLS :443 is blocked/throttled by DPI, try plain WS :80
    // (MTProto payload inside WS is already end-to-end AES-256-CTR encrypted).
    let second_secure = !first_secure;
    let cached_ip = if resolved_ip.is_empty() {
        None
    } else {
        Some(resolved_ip.as_str())
    };
    let (ws2, resolved_ip2, second_err) =
        cf_connect_domain_mode(domain, path, phase_timeout, second_secure, cached_ip).await;
    if ws2.is_some() {
        if !second_secure && !prefer_plain {
            crate::router::set_plain_ws_preferred(true);
            linfo!(" CF: TLS :443 недоступен, автоматический переход на WS :80");
        } else if second_secure && prefer_plain {
            crate::router::set_plain_ws_preferred(false);
            linfo!(" CF: восстановлен TLS :443");
        }
        return (ws2, resolved_ip2, None);
    }

    let final_ip = if !resolved_ip2.is_empty() {
        resolved_ip2
    } else {
        resolved_ip
    };
    let err_out = match (first_err, second_err) {
        (Some(e1), Some(e2)) => {
            let p1 = if first_secure { "443" } else { "80" };
            let p2 = if second_secure { "443" } else { "80" };
            Some(WsError::Other(format!(
                "p{}:{} / p{}:{}",
                p1,
                e1.compact(),
                p2,
                e2.compact()
            )))
        }
        (Some(e), None) | (None, Some(e)) => Some(e),
        (None, None) => None,
    };
    (None, final_ip, err_out)
}

pub fn log_cf_conn_error(msg: &str, err: &WsError) {
    if let WsError::Io(ref e) = err {
        if e.kind() == std::io::ErrorKind::ConnectionReset {
            return;
        }
    }
    if is_http_status_error(err, 429) {
        lwarn!("{}", msg);
    } else {
        lerror!("{}", msg);
    }
}

// активный домен set/save
pub fn set_active_domain_and_save(chosen: &str) {
    let prev_active = CFPROXY.read().active.clone();
    if !chosen.is_empty() && chosen != prev_active {
        {
            let mut cfg = CFPROXY.write();
            cfg.active = chosen.to_string();
        }
        let chosen_owned = chosen.to_string();
        if tokio::runtime::Handle::try_current().is_ok() {
            tokio::task::spawn_blocking(move || {
                save_active_cfproxy_domain(&chosen_owned);
            });
        } else {
            save_active_cfproxy_domain(&chosen_owned);
        }
        linfo!(" CF домен  {}", chosen);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_domains_without_paths_or_ports() {
        assert_eq!(
            normalize_cf_domain("https://Example.COM/path"),
            "example.com"
        );
        assert_eq!(normalize_cf_domain("example.com:443"), "example.com");
        assert_eq!(normalize_cf_domain("example.com"), "example.com");
        assert_eq!(normalize_cf_domain("my-worker.com"), "my-worker.com");
    }

    #[test]
    fn builtin_domains_decode_to_valid_co_uk() {
        let builtins = default_cfproxy_domains();
        assert_eq!(builtins.len(), 20);
        assert!(builtins.iter().all(|d| d.ends_with(".co.uk")));
        assert!(builtins.iter().any(|d| d == "fixtelega.co.uk"));
        assert!(builtins.iter().any(|d| d == "stopblocking.co.uk"));
    }

    #[test]
    fn merges_domains_in_stable_order() {
        let merged = merge_cfproxy_domains(&[
            vec!["A.example".into(), "b.example".into()],
            vec!["a.example".into(), "c.example".into()],
        ]);
        assert_eq!(merged, vec!["a.example", "b.example", "c.example"]);
    }
}
