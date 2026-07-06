# SA05 — план улучшений

Состояние на 2026-07-06: ~6700 строк Kotlin в одном flat-пакете `com.fife.sa05`,
`XrayVpnService.kt` — 1373 строки / 58 функций, UI без ViewModel, R8 выключен,
CI собирает только xray-core и draft-release (нет сборки/тестов приложения),
androidTest пустой, зависимости AndroidX устарели.

Приоритеты: **P0** — надёжность/безопасность, **P1** — архитектура/CI, **P2** — качество жизни.

---

## P0 — Надёжность и безопасность

### 1. Защита от утечек IPv6
**Проблема.** TUN — только IPv4 (`10.10.10.1/30`). На сетях с IPv6 (мобильные операторы, включая Beeline) трафик к AAAA-адресам уходит мимо VPN → утечка реального IP и обход блокировочных режимов.
**Решение.**
- В `VpnService.Builder` добавить IPv6-адрес (`fd00::1/126`) и маршрут `::/0` как blackhole, либо честно пробрасывать IPv6 в tun2socks, если сборка badvpn это поддерживает.
- Для Local Bypass/Full Auto проверить, что QUIC-блокировка (UDP/443) действует и для IPv6.
- Тест: диагностический проб на `ipv6.google.com` в `ConnectivityDiagnostics`.

### 2. Супервизор нативных процессов
**Проблема.** До 4 нативных процессов одновременно (xray, tun2socks, byedpi, tg-ws-proxy). Падение любого из них сейчас обнаруживается только косвенно (диагностика требует «процессы живы»), автоматического восстановления нет.
**Решение.**
- Класс `ProcessSupervisor`: держит `Process` + имя + роль, слушает `onExit`, рестарт с экспоненциальным backoff (0.5s → 8s, максимум N попыток), после исчерпания — перевод `VpnRuntimeState` в состояние ошибки и уведомление.
- Зависимые процессы рестартовать каскадом (упал xray → перезапустить tun2socks после него).
- Health-check: периодический TCP-connect на SOCKS inbound вместо только проверки живости PID.
- Юнит-тесты на политику рестартов (чистая логика, без Android).

### 3. Секреты и артефакты в корне репозитория
**Проблема.** `sa05-release.jks` и `local.properties` лежат в корне рабочей копии. В git не попадают (`.gitignore`), но риск случайного `git add -f` / попадания в архивы и бэкапы остаётся.
**Решение.**
- Перенести keystore из дерева репозитория (например, `~/.android-keys/`), путь брать только из `RELEASE_KEYSTORE_FILE` (механизм уже есть в `app/build.gradle.kts`).
- Добавить pre-commit hook или CI-проверку `git ls-files | grep -E '\.jks|local\.properties'` → fail.

### 4. Включить R8/minify для release
**Проблема.** `isMinifyEnabled = false`: APK больше, весь код читаем при реверсе, dead code не вырезается.
**Решение.**
- `isMinifyEnabled = true`, `isShrinkResources = true` для `release`.
- `proguard-rules.pro`: keep-правила для JNI-энтрипоинтов (`XrayCore`, tun2socks-обвязка), рефлексии в парсинге конфигов (если есть), `VpnQuickSettingsTile`.
- Гейт в CI: собрать release-APK и прогнать smoke (установка + запуск Proxy Only на эмуляторе или хотя бы `aapt`-валидация + запуск unit-тестов против minified-логики через `testRelease`).

---

## P1 — Архитектура и CI

### 5. Декомпозиция `XrayVpnService`
**Проблема.** God-class: 1373 строки, 58 функций — владеет TUN, тремя режимами, процессами, нотификацией, состоянием.
**Решение.** Выделить по ролям, сервис оставить тонким оркестратором:
- `TunController` — создание/закрытие TUN, Builder-конфигурация, disallowed apps.
- `BackendLauncher` per-mode: `FullAutoBackend`, `LocalBypassBackend`, `ProxyOnlyBackend` — единый интерфейс `start(profile): RunningBackend` / `stop()`. Общая логика (SOCKS inbound, QUIC-блок) — в базовом классе или композицией.
- `ProcessSupervisor` из п.2.
- `VpnNotificationPresenter` — нотификация + связь с `VpnRuntimeState`.
- Порядок рефакторинга: сначала характеризационные тесты на текущую сборку конфигов (`XrayConfig`), потом извлечение по одному классу на PR (см. skill request-refactor-plan).

### 6. Пакетная структура
**Проблема.** ~30 файлов в одном пакете; связи между подсистемами не видны, всё доступно всем.
**Решение.** Разнести по фичам без изменения логики:
```
com.fife.sa05
├── vpn/          XrayVpnService, TunController, backends, VpnRuntimeState, VpnMode
├── xray/         XrayCore, XrayConfig, XrayPingEngine
├── subscription/ SubscriptionRepository, SubscriptionAuth, SubscriptionRefresh, SubscriptionDeepLink
├── bypass/       BeelineDiagnostics, ByeDPI-обвязка, TelegramNativeProxy
├── diagnostics/  ConnectivityDiagnostics, NetworkRecoveryPolicy
├── update/       AppUpdate*
├── ui/           screens/, components/, *Explainers, *Presentation
└── prefs/        XrayPreferences
```
Механический рефакторинг средствами IDE, один PR, без переименований классов.

### 7. ViewModel + однонаправленный поток состояния
**Проблема.** `MainActivity` — 754 строки, ViewModel нет; состояние, вероятно, живёт в composable/Activity и переживает конфиг-изменения неявно.
**Решение.**
- По ViewModel на экран (`MainViewModel`, `SettingsViewModel`, `DiagnosticsViewModel`): экспонируют `StateFlow<UiState>`, принимают события.
- Связь с сервисом — через `VpnRuntimeState` как единственный источник правды (уже есть, 218 строк — расширить до Flow, если ещё не).
- Зависимости: `lifecycle-viewmodel-compose`. DI — вручную через `Sa05Application` (Hilt не обязателен при таком размере).

### 8. CI для приложения
**Проблема.** Workflows только `build-xray-core.yml` и `draft-release.yml`. PR не проверяются сборкой и тестами.
**Решение.** `app-ci.yml` на pull_request/push:
- `./gradlew lint testDevUnitTest assembleDev` с кэшем Gradle.
- Прогон `scripts/check-16kb-compat.sh` / `check-elf-16kb.py` по jniLibs — гейт (сейчас скрипты есть, но в CI не включены; регресс 16 KB уже случался — коммиты `bfe2fc9`, `6977fca`).
- Проверка секретов из п.3.

### 9. Обновление зависимостей
**Проблема.** `coreKtx 1.10.1` (2023), `lifecycleRuntimeKtx 2.6.1`, `activityCompose 1.8.0`, `espressoCore 3.5.1` — сильно отстают при `compileSdk 36` и Kotlin 2.2.10; несовпадения с Compose BOM 2026.02.01 могут давать тонкие баги lifecycle.
**Решение.** Поднять AndroidX до актуальных, прогнать полный тестовый набор + ручной smoke трёх режимов VPN. Отдельным пунктом — включить Renovate/Dependabot.

### 10. Тесты на критический путь
**Проблема.** Юнит-тесты покрывают чистую логику (политики, парсинг), но нет тестов на `XrayConfig` (сборка runtime-правил: QUIC-блок, geosite:youtube-роутинг, компат-мост), `BackendController`, и androidTest пустой.
**Решение.**
- `XrayConfigTest`: снапшот-тесты итогового JSON для каждого режима — это контракт с xray-core, ломается чаще всего.
- Характеризационные тесты до рефакторинга п.5.
- androidTest: минимум — старт/стоп `XrayVpnService` в Proxy Only на эмуляторе с фейковым профилем (без реального сервера: локальный echo + проверка, что TUN поднялся и SOCKS слушает).

---

## P2 — Качество жизни

### 11. DataStore вместо SharedPreferences
`XrayPreferences` (393 строки) перевести на Preferences DataStore: асинхронность, Flow-подписки для UI из п.7, отсутствие ANR на больших значениях (кэш подписки). Миграция `SharedPreferencesMigration`.

### 12. Экспорт диагностики
На `DiagnosticsScreen` — кнопка «поделиться логами»: кольцевой буфер логов процессов (stdout/stderr xray, byedpi, tun2socks) + результаты проб + версии, с редактированием секретов (UUID профилей, URL подписки), выгрузка через `FileProvider`/share intent. Сильно ускорит разбор полевых проблем типа Beeline 403/xPadding.

### 13. Always-on VPN и kill switch
Объявить поддержку системного Always-on (`android:supportsAlwaysOn`), корректно обрабатывать `onRevoke`, задокументировать поведение lockdown-режима с исключёнными приложениями (disallowed apps в lockdown не работают — предупредить в UI).

### 14. x86_64 для dev-сборки
`abiFilters` только arm64-v8a → dev-вариант не запускается на эмуляторе x86_64. Добавить `x86_64` только для `dev` buildType (flavored abiFilters), release оставить arm64.

### 15. Строки и локализация
Explainer-тексты (`ProfileExplainers` 634 строки, `BypassExplainers` 447) вынести в `strings.xml`/ресурсы — подготовка к ru/en локализации и уменьшение кода.

---

## Killer-фичи (новая функциональность)

### K1. База «отпечаток сети → стратегия обхода»
**Идея.** Сейчас авто-подбор ByeDPI-пресета привязан к текущей Android-сети и кэш живёт локально. Сделать формализованный отпечаток сети: оператор (`TelephonyManager.simOperator`/`networkOperator`), тип (Wi-Fi/LTE/5G), результаты beeline-style проб (xPadding, QUIC-дроп, TLS-fragmentation чувствительность) → детерминированный ключ.
**Техника.**
- `NetworkFingerprint(mcc, mnc, transport, dpiSignature)` — dpiSignature из батареи коротких проб (доступность ClientHello-фрагментации, реакция на xPadding, RST vs timeout).
- Локальная база `fingerprint → (mode, byedpiArgs, xrayTweaks)` c TTL и счётчиком успешности.
- Опциональный импорт/экспорт базы файлом — обмен между пользователями без сервера (privacy: нет телеметрии).
- Расширяет существующие `BeelineDiagnostics` + Full Auto кэш; Beeline становится частным случаем.

### K2. Самообучающийся split-tunneling
**Идея.** Вместо ручных списков — авто-детект блокировок: домен недоступен напрямую, но доступен через туннель → в routed-список; доступен напрямую → мимо туннеля (меньше латентность, меньше нагрузки на профиль).
**Техника.**
- Xray sniffing (`routeOnly:true` уже используется) отдаёт hostname; фоновый `DirectProbeWorker` дозированно проверяет top-N доменов напрямую (HEAD с коротким timeout, вне VPN через `Network.bindSocket` на underlying network).
- Результаты — в runtime-правила Xray (`domain → direct/proxy`), персист в DataStore, ручной override в UI.
- Safety: банковские/госдомены — всегда direct-whitelist по умолчанию.

### K3. Детектор DNS-подмены + встроенный DoH
**Идея.** Операторы подменяют DNS-ответы (заглушки, NXDOMAIN). Фича: детект подмены и локальный DoH-резолвер внутри туннеля.
**Техника.**
- Проба: резолв контрольных доменов через оператора vs через DoH (Cloudflare/Google) — расхождение = подмена, показать в диагностике («ваш оператор подменяет DNS»).
- В Full Auto/Local Bypass поднять локальный DNS-форвардер на TUN-адресе, upstream — DoH через Xray-туннель; UDP DNS сейчас идёт directly в Local Bypass — сделать переключаемым.

### K4. Failover между профилями подписки
**Идея.** Подписка отдаёт массив профилей — использовать как пул. Активный профиль умер (SOCKS health-check из P0-2 фейлится N раз) → автоматический hot-swap на следующий по латентности без разрыва TUN.
**Техника.**
- `XrayPingEngine` уже меряет — держать ranked-список.
- Hot-swap: перезапуск только xray-процесса с новым конфигом, TUN и tun2socks не трогаем (SOCKS-адрес тот же) → переключение ~1-2 сек, сокеты приложений переживают через retry.
- UI: тумблер «авто-failover», бейдж «переключено на …» в нотификации.

### K5. Раздача обхода по точке доступа (hotspot sharing)
**Идея.** Телефон с SA05 = шлюз обхода для ТВ/ноутбука/приставки в той же Wi-Fi сети.
**Техника.**
- Дополнительный Xray inbound: SOCKS5 + HTTP proxy на адресе hotspot-интерфейса (не 127.0.0.1), порт фиксированный, опционально с паролем.
- UI: QR с `socks5://ip:port`, инструкции для Android TV/десктопа.
- Гейт: только когда активен hotspot/локальная сеть, предупреждение о батарее.

### K6. Автоподключение по правилам сети
**Идея.** «На этом Wi-Fi — не включать, на мобильной сети — Full Auto, на работе — Proxy Only».
**Техника.**
- `NetworkCallback` + правила `(ssid|operator|transport) → (off|mode)` в DataStore.
- Старт VPN из фона: `VpnService.prepare` уже дан → сервис стартует напрямую; иначе — нотификация-приглашение.
- Плюс intent-API (`com.fife.sa05.action.CONNECT?mode=`) для Tasker/автоматизаций.

### K7. Монитор цензуры с историей
**Идея.** Диагностика сейчас — мгновенный снимок. Сделать историю: фоновая проба раз в N часов (WorkManager, только заряд+сеть), таймлайн «когда YouTube начал резаться на этом операторе».
**Техника.**
- Переиспользовать `ConnectivityDiagnostics`, писать результат в Room/файл (кольцо, 90 дней).
- Пуш при изменении стату

| Шаг | Пункты | Зависимости |
|-----|--------|-------------|
| 1 | 3, 8 | — (быстрые, снижают риск сразу) |
| 2 | 10 (характеризационные), 1 | — |
| 3 | 5, 2 | после 10 |
| 4 | 6, 7 | после 5 |
| 5 | 4, 9 | после 8 (CI ловит регрессы) |
| 6 | 11–15 | по мере надобности |
