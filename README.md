# TgwsProxyAndroid

Android-клиент локального MTProto-прокси с асинхронным Rust/Tokio-ядром, WebSocket-маршрутизацией и Cloudflare/TCP fallback.

## Возможности

- локальный endpoint `127.0.0.1:1443`, недоступный другим устройствам сети;
- MTProto secret и Fake TLS-ссылки для Telegram;
- Rust/Tokio proxy core через небольшой JNA-мост;
- пул WebSocket-соединений, keepalive, cooldown и TCP fallback;
- foreground service со статистикой и экспортом логов;
- безопасное обновление из GitHub Releases с проверкой package name и signing certificate;
- шифрование секрета ключом Android Keystore.

## Требования

- Android Studio / Android SDK 36;
- JDK 21;
- Rust stable с `rustup`;
- Android NDK 27 или новее;
- `cargo-ndk`.

## Сборка

1. Установите Android SDK и NDK и задайте `ANDROID_SDK_ROOT` или `ANDROID_HOME`.
2. Установите Rust targets и соберите native-библиотеки:

   ```powershell
   cargo install cargo-ndk --locked
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   .\build_so.bat
   ```

3. Соберите debug APK:

   ```powershell
   .\gradlew.bat testDebugUnitTest lintDebug assembleDebug
   ```

Для release-сборки добавьте в локальный, игнорируемый Git-файл `local.properties`:

```properties
RELEASE_STORE_FILE=tgwsproxy-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=tgwsproxy
RELEASE_KEY_PASSWORD=...
```

Keystore, `local.properties`, APK и каталоги сборки не должны попадать в Git.

## Структура

- `app/src/main/java/com/tgwsproxy/android` — Compose UI, foreground service и update flow;
- `src` — Rust proxy core, TLS/WebSocket, MTProto crypto и Cloudflare fallback;
- `app/src/main/jniLibs` — собранные Android `.so`;
- `.github/workflows/ci.yml` — проверка Rust и Android в CI.

## Безопасность

TLS-соединения проверяются по публичным WebPKI roots. Загруженное обновление устанавливается только при совпадении package name и сертификата с уже установленным приложением. MTProto secret хранится в AES-GCM виде с неэкспортируемым ключом Android Keystore и исключён из Android backup.

## Лицензия

GNU GPLv3 — см. `LICENSE-GPLv3`.
