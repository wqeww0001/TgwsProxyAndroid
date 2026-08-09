pub mod cfproxy;
pub mod config;
pub mod crypto;
pub mod proxy;
pub mod router;
pub mod ws;

use config::*;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use proxy::{parse_cidr_pool, run_proxy, WsPool};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

// Глобальный рантайм — никогда не дропается
static RUNTIME: OnceCell<Runtime> = OnceCell::new();

struct ProxyState {
    pool: Arc<WsPool>,
    handle: tokio::task::JoinHandle<()>,
    cancel_tasks: CancellationToken,
}

static STATE: OnceCell<Mutex<Option<ProxyState>>> = OnceCell::new();

fn state_cell() -> &'static Mutex<Option<ProxyState>> {
    STATE.get_or_init(|| Mutex::new(None))
}

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        // Многопоточный рантайм, кол-во воркеров адекватно мобиле
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("tgwsproxy-rt")
            .enable_all()
            .build()
            .expect("failed to build global tokio runtime")
    })
}

fn cstr_to_string(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() }
}

fn ffi_guard<T>(name: &str, fallback: T, action: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(action)) {
        Ok(value) => value,
        Err(_) => {
            lerror!("panic contained at FFI boundary: {}", name);
            fallback
        }
    }
}

// ---------------------------------------------------------------------------
// Exports
// ---------------------------------------------------------------------------

/// # Safety
/// Указатели должны быть валидными C-строками (или null).
#[no_mangle]
pub unsafe extern "C" fn StartProxy(
    c_host: *const c_char,
    port: c_int,
    c_dc_ips: *const c_char,
    c_secret: *const c_char,
    verbose: c_int,
) -> c_int {
    ffi_guard("StartProxy", -100, || unsafe {
        start_proxy_impl(c_host, port, c_dc_ips, c_secret, verbose)
    })
}

unsafe fn start_proxy_impl(
    c_host: *const c_char,
    port: c_int,
    c_dc_ips: *const c_char,
    c_secret: *const c_char,
    verbose: c_int,
) -> c_int {
    let cell = state_cell();
    let mut guard = cell.lock();

    if guard.is_some() {
        return -1;
    }

    let host = cstr_to_string(c_host);
    let go_port = port as u16;
    let dc_ips_str = cstr_to_string(c_dc_ips);
    let secret_str = cstr_to_string(c_secret);
    let is_verbose = verbose != 0;

    init_logging(is_verbose);
    cfproxy::clear_cfproxy_429_cooldowns();
    router::reset();

    if secret_str.len() == 32 {
        if hex::decode(&secret_str).is_ok() {
            *PROXY_SECRET.write() = secret_str.clone();
        }
    }

    cfproxy::init_cfproxy_domains();

    let dc_opt_map: HashMap<i32, String> = parse_cidr_pool(&dc_ips_str);

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let pool = Arc::new(WsPool::new(cancel_tasks.clone()));

    // Канал готовности: ждём успешного bind перед возвратом
    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let pool_task = pool.clone();
    let host_task = host.clone();
    let map_task = dc_opt_map.clone();
    let cancel_root = cancel_tasks.clone();

    let handle = rt.spawn(async move {
        // Предварительный bind для сигнала готовности
        let addr = format!("{}:{}", host_task, go_port);
        match tokio::net::TcpListener::bind(&addr).await {
            Ok(listener) => {
                let warmup =
                    tokio::time::timeout(WS_POOL_WARMUP_TIMEOUT, pool_task.warmup(&map_task)).await;
                match warmup {
                    Ok(()) => linfo!(
                        "WS pool warmup complete: {} idle",
                        pool_task.idle_count().await
                    ),
                    Err(_) => {
                        lwarn!("WS pool warmup timed out; continuing with on-demand connections")
                    }
                }
                let _ = tx.send(Ok(()));
                if let Err(e) = run_proxy(
                    pool_task,
                    host_task,
                    go_port,
                    map_task,
                    cancel_root,
                    listener,
                )
                .await
                {
                    lerror!("listen on {}: {}", addr, e);
                }
            }
            Err(e) => {
                let _ = tx.send(Err(format!("listen on {}: {}", addr, e)));
            }
        }
    });

    // Ждём результат bind
    match rx.recv() {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            handle.abort();
            return -3;
        }
        Err(_) => {
            handle.abort();
            return -3;
        }
    }

    *guard = Some(ProxyState {
        pool,
        handle,
        cancel_tasks,
    });

    0
}

#[no_mangle]
pub extern "C" fn StopProxy() -> c_int {
    ffi_guard("StopProxy", -100, stop_proxy_impl)
}

fn stop_proxy_impl() -> c_int {
    let cell = state_cell();
    let mut guard = cell.lock();

    let state = match guard.take() {
        Some(s) => s,
        None => return -1,
    };

    // graceful shutdown — НЕ дропаем рантайм
    linfo!("StopProxy: cancelling all tasks");
    state.cancel_tasks.cancel();

    let rt = runtime();
    let pool = state.pool.clone();
    let handle = state.handle;
    rt.block_on(async move {
        linfo!("StopProxy: waiting for proxy tasks to finish (max 2s)");
        let _ = tokio::time::timeout(std::time::Duration::from_secs(2), handle).await;
        linfo!("StopProxy: closing pool connections");
        pool.close_all().await;
        linfo!("StopProxy: done");
    });

    STATS.reset();
    WS_BLACKLIST.write().clear();
    DC_FAIL_UNTIL.write().clear();
    cfproxy::clear_cfproxy_429_cooldowns();
    router::reset();

    linfo!("StopProxy: exit");
    0
}

#[no_mangle]
pub extern "C" fn SetPoolSize(size: c_int) {
    ffi_guard("SetPoolSize", (), || set_pool_size_impl(size));
}

fn set_pool_size_impl(size: c_int) {
    let mut n = size;
    if n < 2 {
        n = 2;
    }
    if n > 6 {
        n = 6;
    }
    POOL_SIZE.store(n, Ordering::Relaxed);
}

/// # Safety
/// `c_cache_dir` — валидная C-строка или null.
#[no_mangle]
pub unsafe extern "C" fn SetCfProxyCacheDir(c_cache_dir: *const c_char) {
    ffi_guard("SetCfProxyCacheDir", (), || unsafe {
        set_cfproxy_cache_dir_impl(c_cache_dir)
    });
}

unsafe fn set_cfproxy_cache_dir_impl(c_cache_dir: *const c_char) {
    let dir = cstr_to_string(c_cache_dir);
    CFPROXY.write().cache_dir = dir.trim().to_string();
}

/// # Safety
/// `c_user_domain` — валидная C-строка или null.
#[no_mangle]
pub unsafe extern "C" fn SetCfProxyConfig(
    enabled: c_int,
    _priority: c_int,
    c_user_domain: *const c_char,
) {
    ffi_guard("SetCfProxyConfig", (), || unsafe {
        set_cfproxy_config_impl(enabled, _priority, c_user_domain)
    });
}

unsafe fn set_cfproxy_config_impl(enabled: c_int, _priority: c_int, c_user_domain: *const c_char) {
    CFPROXY_ENABLED.store(enabled != 0, Ordering::Relaxed);
    let user_domain = cstr_to_string(c_user_domain);
    let mut cfg = CFPROXY.write();
    cfg.user_domain = user_domain.clone();
    if !user_domain.is_empty() {
        cfg.domains = vec![user_domain.clone()];
        cfg.active = user_domain;
    }
}

/// # Safety
/// `c_secret` — валидная C-строка или null.
#[no_mangle]
pub unsafe extern "C" fn SetSecret(c_secret: *const c_char) {
    ffi_guard("SetSecret", (), || unsafe { set_secret_impl(c_secret) });
}

unsafe fn set_secret_impl(c_secret: *const c_char) {
    let s = cstr_to_string(c_secret);
    if s.len() != 32 {
        return;
    }
    if hex::decode(&s).is_err() {
        return;
    }
    *PROXY_SECRET.write() = s;
}

#[no_mangle]
pub extern "C" fn GetStats() -> *mut c_char {
    ffi_guard("GetStats", std::ptr::null_mut(), || {
        let s = STATS.summary();
        CString::new(s).unwrap_or_default().into_raw()
    })
}

#[no_mangle]
pub extern "C" fn GetSecretWithPrefix() -> *mut c_char {
    ffi_guard("GetSecretWithPrefix", std::ptr::null_mut(), || {
        let sec = PROXY_SECRET.read().clone();
        CString::new(format!("dd{}", sec))
            .unwrap_or_default()
            .into_raw()
    })
}

/// # Safety
/// `p` должен быть указателем, ранее возвращённым из этой библиотеки.
#[no_mangle]
pub unsafe extern "C" fn FreeString(p: *mut c_char) {
    ffi_guard("FreeString", (), || unsafe {
        if !p.is_null() {
            let _ = CString::from_raw(p);
        }
    });
}
