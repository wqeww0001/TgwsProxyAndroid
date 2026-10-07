# ⚡ TgwsProxyAndroid

**Высокопроизводительный локальный MTProto WebSocket и Telegram Web Proxy (`tproxy-v1`) для Android**

[![GitHub Release](https://img.shields.io/github/v/release/wqeww0001/TgwsProxyAndroid?color=3b82f6&style=flat-square&logo=github)](https://github.com/wqeww0001/TgwsProxyAndroid/releases/latest)
[![Android Min SDK](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-34d399?style=flat-square&logo=android)](https://developer.android.com)
[![Core: Rust + Tokio](https://img.shields.io/badge/Core-Rust%20%2B%20Tokio-f97316?style=flat-square&logo=rust)](https://www.rust-lang.org/)
[![Protocol: tproxy-v1](https://img.shields.io/badge/Protocol-MTProto%20WS%20%7C%20tproxy--v1-10b981?style=flat-square&logo=telegram)](https://github.com/wqeww0001/TgwsProxyAndroid)
[![UI: Jetpack Compose M3](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-60a5fa?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-a855f7?style=flat-square)](LICENSE-GPLv3)
[![Downloads](https://img.shields.io/github/downloads/wqeww0001/TgwsProxyAndroid/total?style=flat-square&color=10b981)](https://github.com/wqeww0001/TgwsProxyAndroid/releases)

[**Скачать APK (Релизы)**](https://github.com/wqeww0001/TgwsProxyAndroid/releases/latest) • [**Быстрый старт**](#-быстрый-старт) • [**Возможности**](#-ключевые-возможности) • [**Настройки**](#️-настройки-и-параметры) • [**Сборка**](#️-сборка-из-исходников) • [**English Summary**](#-english-summary)

---

## 📖 О проекте

**TgwsProxyAndroid** — это клиентское Android-приложение со встроенным асинхронным **Rust + Tokio ядром** и движком **Telegram Web Proxy (`tproxy-v1`)**, которое поднимает изолированный локальный мост `127.0.0.1:1443` прямо на вашем смартфоне.

Приложение позволяет обходить фильтрацию трафика и DPI двумя способами:
1. **MTProto WebSocket Proxy (Rust + Tokio)** — туннелирование трафика датацентров Telegram (`DC 1..5`) поверх защищённых `WSS`-соединений через **Cloudflare CDN** с автоматическим прогревом пула и прозрачным fallback на прямой TCP.
2. **Telegram Web Proxy (`tproxy-v1`)** — поддержка нового официального протокола маскировки под обычный HTTPS-сайт с валидным TLS-сертификатом (`t.me/webproxy` / `tg://webproxy`). Благодаря локальному мосту `127.0.0.1:1443` ссылки Web Proxy работают во **всех** Android-клиентах Telegram.

---

## ✨ Ключевые возможности

### 🚀 Производительность и протоколы
- **Асинхронное ядро на Rust + Tokio (`libtgwsproxy.so`)**: неблокирующая обработка сокетов через JNA-мост с минимальным потреблением памяти и околонулевой задержкой.
- **Полная поддержка Telegram Web Proxy (`tproxy-v1`)**:
  - Поддержка всех транспортных режимов: `https`, `https-lanes`, `websocket` и `websocket-lanes`.
  - Криптографическая авторизация запросов `HMAC-SHA256` (схемы подписи `v1` и `v2`).
  - Автоматический перехват и импорт ссылок `https://t.me/webproxy?server=...&secret=...` и `tg://webproxy?...` из буфера обмена или браузера.
- **Пул быстрых WebSocket-соединений**: настраиваемый пул (`2`, `4` или `6` соединений), keepalive-пинги, автоматический перезапуск при переключении между Wi-Fi и мобильной сетью и умный fallback на прямой TCP.

### 🔋 Энергоэффективность и фоновая работа
- **Умный режим сна (Smart Standby)**: сервис отслеживает состояние экрана и активный входящий/исходящий трафик (`down` / `up`) — при выключенном дисплее и простое частота фонового опроса снижается до 30 секунд. При появлении трафика или включении экрана активный режим возвращается мгновенно.
- **Foreground Service & Boot Receiver**: стабильная работа в фоне без выгрузки системой Android и опциональный автозапуск после перезагрузки телефона.
- **Плитка в шторке (Quick Settings Tile)**: включение и отключение прокси в **1 тап** прямо из панели быстрых настроек Android.

### 🎨 Современный интерфейс (Material Design 3)
- **4 полноценные темы оформления**: `Светлая (Light)`, `Тёмная (Dark)`, `Аврора (Aurora)` и `Закат (Sunset)` с продуманными цветовыми палитрами и автоматической адаптацией статус-бара.
- **Высокая отзывчивость без лишних рекомпозиций**: изолированное обновление таймера аптайма, версионированный буфер логов и виртуализированный журнал отладки на `LazyColumn`.
- **Центр обновлений и Архив версий (Rollback)**:
  - Переключение между каналами **Стабильная (Stable)** и **Бета / Снапшот (Beta)**.
  - Окно обновлений с форматированным списком изменений и автоматическим перезапуском прокси после установки.
  - Встроенный **Архив всех релизов** с возможностью отката на любую предыдущую версию в 1 клик.
- **Резервное копирование настроек**: экспорт и импорт всей конфигурации прокси в формате JSON или по ссылке.
- **Статистика трафика**: учёт принятых и отправленных данных (`↓` / `↑`) за сегодня и за всё время.

### 🔒 Безопасность
- **Аппаратное хранилище ключей (Android Keystore)**: локальный MTProto-секрет генерируется на устройстве и шифруется алгоритмом **AES-GCM** с неэкспортируемым ключом.
- **Проверка целостности пакета**: перед установкой обновления проверяется валидность APK-архива и совпадение `packageName`.

---

## 🛠️ Схема работы

```mermaid
graph LR
    subgraph Android Device
        TG["Telegram / Fork Client"] -->|"MTProto 127.0.0.1:1443"| SERVICE["ProxyService (Foreground)"]
        TILE["Quick Settings Tile"] -.->|"1-Tap Toggle"| SERVICE
        SERVICE -->|"Режим MTProto WS"| RUST["Rust + Tokio Core"]
        SERVICE -->|"Режим Web Proxy"| TPROXY["WebProxyEngine (tproxy-v1)"]
    end

    subgraph Network
        RUST -->|"WSS (443) / TCP Fallback"| CF["Cloudflare CDN / Direct WSS"]
        TPROXY -->|"HTTPS / WSS (HMAC v1/v2)"| WP["Web Proxy Server (t.me/webproxy)"]
        CF --> DC["Telegram Datacenters 1..5"]
        WP --> DC
    end
```

---

## 🚀 Быстрый старт

### Вариант 1: Обычный MTProto WS Proxy (по умолчанию)
1. Скачайте актуальный APK со страницы **[Релизов (Releases)](https://github.com/wqeww0001/TgwsProxyAndroid/releases/latest)** и установите его.
2. Нажмите **«Запустить»** на главном экране и дождитесь статуса **«Подключено»**.
3. Нажмите **«Открыть в Telegram»** и подтвердите добавление прокси `127.0.0.1:1443`.

### Вариант 2: Telegram Web Proxy (`tproxy-v1`)
1. Перейдите на вкладку **«Настройки»** → карточка **Telegram Web Proxy (`tproxy-v1`)**.
2. Нажмите **«Вставить ссылку»** (если в буфере обмена скопирована ссылка `https://t.me/webproxy?server=...&secret=...`) или введите сервер и секрет вручную и включите тумблер.
3. Вернитесь на вкладку **«Главная»**, нажмите **«Запустить»**, а затем **«Открыть в Telegram»**.

> [!WARNING]
> **Если при обновлении возникает ошибка «Update signing certificate mismatch» / «Приложение не установлено»:**
> Удалите старую версию приложения с телефона и установите новый APK со страницы [Releases](https://github.com/wqeww0001/TgwsProxyAndroid/releases/latest) заново. Начиная с актуальных релизов используется единый постоянный ключ подписи, поэтому все последующие обновления будут устанавливаться поверх без удаления.

---

## ⚙️ Настройки и параметры

| Параметр | Описание | По умолчанию |
| :--- | :--- | :--- |
| **Telegram Web Proxy (`tproxy-v1`)** | Мост для протокола `t.me/webproxy` (`https`, `https-lanes`, `websocket`, `websocket-lanes`) | Выключено |
| **Cloudflare домен** | Пользовательский домен для WSS-маршрутизации (пусто = встроенный список + TCP fallback) | Встроенный |
| **Cloudflare CDN** | Приоритетное использование Cloudflare CDN перед прямым WSS | Включено |
| **Датацентры Telegram (DC → IP)** | Пользовательское перенаправление датацентров в формате `номерDC:IPv4` | По умолчанию |
| **Пул быстрых соединений** | Количество готовых параллельных WSS-соединений (`2`, `4` или `6`) | `4` |
| **Умный режим сна (Smart Standby)** | Снижение фоновой активности при выключенном экране и отсутствии трафика | Включено |
| **Канал обновлений** | Выбор между `Стабильная (Stable)` и `Бета / Снапшот (Beta)` + архив версий | `Стабильная` |
| **Оформление (Appearance)** | Выбор темы интерфейса: `Светлая`, `Тёмная`, `Аврора`, `Закат` | `Светлая` |

---

## 🏗️ Сборка из исходников

### Требования
- **Android Studio** Ladybug (или новее) / **Android SDK 37**
- **Android NDK** `27.x` или новее
- **JDK 21** (Temurin / OpenJDK)
- **Rust stable** + утилита `cargo-ndk`

### Пошаговая инструкция

1. **Клонируйте репозиторий:**
   ```bash
   git clone https://github.com/wqeww0001/TgwsProxyAndroid.git
   cd TgwsProxyAndroid
   ```

2. **Установите таргеты Rust и соберите native-библиотеки (`libtgwsproxy.so`):**
   ```powershell
   cargo install cargo-ndk --locked
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

   # Сборка .so библиотек для arm64-v8a, armeabi-v7a и x86_64:
   .\build_so.bat
   ```

3. **Запустите тесты и соберите Debug APK:**
   ```powershell
   .\gradlew.bat testDebugUnitTest assembleDebug
   ```
   *Собранный файл:* `app/build/outputs/apk/debug/app-debug.apk`

4. **Сборка подписанного Release APK:**
   Создайте файл `local.properties` в корне проекта (он исключён из Git):
   ```properties
   RELEASE_STORE_FILE=tgwsproxy-release.jks
   RELEASE_STORE_PASSWORD=your_store_password
   RELEASE_KEY_ALIAS=tgwsproxy
   RELEASE_KEY_PASSWORD=your_key_password
   ```
   Выполните:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
   *Собранный файл:* `app/build/outputs/apk/release/app-release.apk`

---

## 🌐 English Summary

**TgwsProxyAndroid** is a high-performance local proxy client for Telegram on Android powered by an asynchronous **Rust + Tokio** core and a built-in **Telegram Web Proxy (`tproxy-v1`)** bridge on `127.0.0.1:1443`.

### Highlights:
- **Dual Engine (`MTProto WS` + `tproxy-v1`)**: Supports both Cloudflare WSS / TCP fallback routing in Rust and Telegram's `tproxy-v1` protocol (`https`, `https-lanes`, `websocket`, `websocket-lanes` with HMAC-SHA256 `v1`/`v2` auth), making `t.me/webproxy` links work across all Android Telegram clients.
- **Smart Standby Battery Saver**: Automatically throttles background polling when the screen is off and no download/upload traffic is active.
- **Material 3 UI & 4 Themes**: Clean Jetpack Compose interface featuring `Light`, `Dark`, `Aurora`, and `Sunset` themes, vector iconography, and a virtualized `LazyColumn` debug log.
- **In-App Updater & Version Archive**: Supports `Stable` and `Beta` update channels, formatted Markdown changelogs, automatic service restart after update, and 1-click rollback to any previous GitHub release.
- **Hardware-Backed Security**: Local MTProto secret encrypted with **AES-GCM** via Android Keystore.

---

## 🤝 Участие в разработке

Мы рады новым идеям, баг-репортам и пул-реквестам! Перед началом работы ознакомьтесь с документами:
- [Руководство по участию (Contributing Guide)](CONTRIBUTING.md)
- [Кодекс поведения (Code of Conduct)](CODE_OF_CONDUCT.md)
- [Политика безопасности (Security Policy)](SECURITY.md)

---

## 📜 Лицензия

Проект распространяется под свободной лицензией **GNU General Public License v3.0 (GPLv3)**. Подробности в файле [LICENSE-GPLv3](LICENSE-GPLv3).
