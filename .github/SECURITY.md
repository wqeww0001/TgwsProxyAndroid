# Security Policy

Безопасность и конфиденциальность данных пользователей являются ключевыми приоритетами проекта **TgwsProxyAndroid**.

---

## Supported Versions

| Version | Supported |
| :--- | :--- |
| `2.4.x` | :white_check_mark: Yes (Активно поддерживается) |
| `< 2.4.0` | :x: No (Устарело, требуется обновление) |

---

## Reporting a Vulnerability

If you have discovered a security vulnerability, please report it responsibly:

1. **Do NOT open a public issue.** (Пожалуйста, не создавайте публичный Issue).
2. Report the vulnerability privately via [GitHub Private Vulnerability Reporting](https://github.com/wqeww0001/TgwsProxyAndroid/security/advisories/new) or contact the project maintainer directly.
3. Provide detailed steps to reproduce the issue and any relevant logs or context.

Мы рассмотрим ваш отчет в кратчайшие сроки и выпустим соответствующее исправление.

---

## 🔒 Built-in Security Architecture (Встроенная защита)

1. **Android Keystore Hardware Protection:**
   - Секретный ключ MTProto шифруется с использованием алгоритма AES-GCM и неэкспортируемого аппаратного ключа Android Keystore.
   - Секреты исключены из резервных копий Android Cloud Backup (`allowBackup="false"`).

2. **Tamper-proof Auto Updates:**
   - Каждое загруженное обновление перед установкой верифицирует `packageName` и SHA-256 цифровой сертификат подписи разработчика (`signingCertificateHistory`), исключая подмену APK.

3. **Strict TLS & Local Isolation:**
   - Все исходящие TLS/WebSocket соединения валидируются по доверенным корневым сертификатам публичной WebPKI.
   - Локальный прокси по умолчанию привязан строго к петлевому интерфейсу `127.0.0.1`, делая его недоступным извне без явного включения пользователем режима раздачи LAN.
