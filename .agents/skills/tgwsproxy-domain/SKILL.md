---
name: tgwsproxy-domain
description: >-
  Specialized domain and UI/UX engineering skill for TgwsProxyAndroid.
  Use this skill whenever modifying, designing, or debugging the Telegram MTProto WS Proxy,
  Telegram Web Proxy (tproxy-v1), Rust JNI core (libtgwsproxy.so),
  in-app update flow, or Jetpack Compose Material 3 interface of TgwsProxyAndroid.
---

# TgwsProxyAndroid Engineering & UI Design Skill

## 1. Domain Architecture Overview

`TgwsProxyAndroid` is a native Android local proxy application (`127.0.0.1:1443`) for bypassing DPI/TSPU restrictions in Telegram without a system-wide VPN.

### Core Subsystems
1. **Local Loopback Bridge (`127.0.0.1:1443`)**:
   - All Telegram Android clients (Official, Plus, Nekogram, AyuGram, iMe, Challegram) connect via a local MTProto proxy link (`tg://proxy?server=127.0.0.1&port=1443&secret=...`).
2. **Dual Transport Modes**:
   - **MTProto WebSocket Mode (Rust JNI Core `libtgwsproxy.so`)**:
     - Entry point: `src/lib.rs`, `src/proxy.rs`.
     - Extracts target Telegram DC (`1..5`, media/test variants) from the 64-byte MTProto obfuscated2 header and routes over `wss://kws{dc}.web.telegram.org/apiws` or Cloudflare Worker/CDN domains.
   - **Telegram Web Proxy Mode (`tproxy-v1` / Kotlin Engine)**:
     - Entry point: `app/src/main/java/com/tgwsproxy/android/webproxy/WebProxyProtocol.kt` and `WebProxyEngine.kt`.
     - Parses `https://t.me/webproxy?server=...&port=...&secret=...` and `tg://webproxy?...` links, including `0x70`-prefixed base64url secrets with embedded `base_path`.
     - Derives the 32-byte `bridge` key via `HMAC-SHA256(secret, "tproxy-v1:" + lower(host) + ":" + port)` (`64` lowercase hex chars).
     - Fetches the 16-byte `bootstrap` token (`32` hex chars) from `GET https://{host}:{port}{base_path}/` with header `X-Https-Bridge: <bridge>`.
     - Multiplexes frames (`OPEN=0x01`, `DATA=0x02`, `CLOSE=0x03`, `PING=0x04`, `PONG=0x05`, `WINDOW=0x06`, 9-byte header `[type:1][stream_id:4 BE][len:4 BE]`) across `https`, `https-lanes`, `websocket`, and `websocket-lanes` modes.
3. **Seamless In-App Updater (`UpdateChecker.kt` & `BootReceiver.kt`)**:
   - Downloads signed APK releases from GitHub (`wqeww0001/TgwsProxyAndroid`), persists `PREF_REOPEN_AFTER_UPDATE` and `PREF_WAS_RUNNING_BEFORE_UPDATE`, and automatically reopens `MainActivity` and restarts `ProxyService` upon `ACTION_MY_PACKAGE_REPLACED`.

---

## 2. UI/UX & Visual Design Guidelines (Jetpack Compose Material 3)

1. **Zero-Overhead Minimalist Surfaces**:
   - Avoid continuous infinite canvas animations in the background to preserve battery life (`0% GPU idle overhead`).
   - Use crisp matte cards (`RoundedCornerShape(16.dp)`) with subtle `1.dp` outline borders (`MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)`).
2. **Theme Modes**:
   - Support all 4 theme palettes (`Light`, `Dark`, `Aurora`, `Sunset`) with semantic status accents:
     - Active / Healthy: `SignalMint`
     - Starting / Warning: `SignalAmber`
     - Error / Beta: `MaterialTheme.colorScheme.error`
3. **Typography & Markdown Rendering**:
   - Never display raw Markdown tokens (`**`, `##`, `` ` ``) in user-facing dialogs or cards. Always render release notes and structured descriptions through `FormattedReleaseNotes` and `parseMarkdownAnnotatedString`.
   - Use `FontFamily.Monospace` for IP addresses, ports (`127.0.0.1:1443`), build numbers, ping metrics, and cryptographic secrets.
4. **Bilingual Support (RU / EN)**:
   - Every new UI component, dialog, toast, or status string must support both `AppLanguage.Ru` and `AppLanguage.En`.

---

## 3. Release & CI/CD Verification Checklist

1. **Signing Consistency**:
   - All release builds in `.github/workflows/ci.yml` must use the permanent `RELEASE_KEYSTORE_BASE64` secret (`tgwsproxy-release.jks`) so in-app updates never trigger `Update signing certificate mismatch`.
2. **Version Bumping**:
   - Increment both `versionCode` and `versionName` in `app/build.gradle.kts` and document changes in `CHANGELOG.md`.
3. **Unit Testing**:
   - Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` and `cargo test --locked` to verify both the Kotlin and Rust layers.
