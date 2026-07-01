# Добавление Beeline XHTTP-профиля в готовый Xray-клиент

## Короткий ответ

На клиенте это не новый inbound, а новый **VLESS outbound**. Существующие TUN/SOCKS/HTTP inbounds клиента менять не нужно.

Нужно:

1. заменить штатный Xray-core на пропатченную сборку либо встроить патч в используемый libXray;
2. добавить в модель профиля параметры CDN XHTTP;
3. генерировать приведённый ниже outbound;
4. направить существующий routing клиента в его tag;
5. оставить один процесс/экземпляр Xray для всего клиента.

Отдельное второе ядро для этого профиля не требуется.

## 1. Обязательная замена ядра

Стандартный Xray создаёт XHTTP session ID как UUID длиной 36 символов. Beeline CDN блокирует такой запрос кодом `403`. Одних настроек JSON недостаточно.

В готовом клиенте нужно заменить:

- desktop: исполняемый файл `xray`/`xray.exe`;
- Android: пересобрать используемый AAR/libXray из пропатченного Xray-core;
- iOS/macOS: пересобрать framework/library с тем же патчем;
- если клиент загружает core отдельно — добавить модифицированный core как поддерживаемый вариант и выбирать его для этого профиля.

Проверенный бинарник Linux x86_64:

```text
/home/sa05/xraySandbox/xray-beeline
SHA-256: eecffdb440357d2503ea3aee0581363a5ed0c75a19496064fa80c1830ba50b33
Xray base version: 26.6.1
```

Этот бинарник годится как эталон поведения, но разработчик должен собирать нужные платформы из исходников.

## 2. Патч Xray-core

Файл:

```text
transport/internet/splithttp/dialer.go
```

Добавить импорт:

```go
import "encoding/base64"
```

Заменить:

```go
sessionIdUuid := uuid.New()
sessionId = sessionIdUuid.String()
```

на:

```go
sessionIdUuid := uuid.New()
sessionId = base64.RawURLEncoding.EncodeToString(sessionIdUuid[:9])
```

Результат — 12 символов Base64URL, которые Beeline пропускает. Серверный Xray менять не требуется: session ID для него является непрозрачной строкой.

Исходники готового патча находятся здесь:

```text
/home/sa05/xraySandbox/xray-core-beeline
```

Сборка desktop Linux:

```bash
cd /home/sa05/xraySandbox/xray-core-beeline && go build -trimpath -o ../xray-beeline ./main
```

## 3. Одно ядро или отдельное

Использовать **одно пропатченное ядро для всего клиента**.

Патч выполняется только внутри XHTTP dialer и не изменяет:

- VLESS TCP/REALITY;
- Trojan;
- VMess;
- Shadowsocks;
- WebSocket;
- gRPC;
- Hysteria;
- существующие TUN/SOCKS/HTTP inbounds.

Поэтому существующий lifecycle клиента остаётся прежним:

```text
существующий TUN/SOCKS inbound -> routing -> выбранный outbound
```

Второе ядро имеет смысл только как временный fallback, если приложение технически не умеет заменить встроенный core. Для production это хуже: дополнительные порты, память, логи, управление процессами и конфликты TUN.

Для максимальной совместимости разработчик может позднее сделать патч opt-in, например через новые поля `sessionIdFormat` и `sessionIdBytes`. Для первой рабочей версии допустим проверенный hardcoded вариант.

## 4. Поля нового профиля в приложении

В модель профиля добавить или переиспользовать:

```text
protocol = vless
serverAddress = 48typmw3qq.a.trbcdn.net
serverPort = 443
userId = UUID пользователя
vlessEncryption = клиентская строка от `xray vlessenc`
flow = пустая строка
transport = xhttp
xhttpMode = packet-up
xhttpPath = /assets/api/v1/
xhttpHost = 48typmw3qq.a.trbcdn.net
xPaddingBytes = 100-500
xPaddingObfsMode = true
xPaddingPlacement = header
xPaddingMethod = tokenish
tls = true
tlsServerName = 48typmw3qq.a.trbcdn.net
alpn = h2,http/1.1
```

Клиентская модель не должна подставлять `origin.sa05.tech`. Этот домен используется только между CDN и сервером.

Строка `vlessEncryption` не является обычным значением `none`. Это клиентская половина пары, сгенерированной командой:

```bash
xray vlessenc
```

На сервер уходит `decryption`, клиент получает `encryption`.

## 5. JSON, который должен генерировать клиент

```json
{
  "tag": "proxy",
  "protocol": "vless",
  "settings": {
    "vnext": [
      {
        "address": "48typmw3qq.a.trbcdn.net",
        "port": 443,
        "users": [
          {
            "id": "<USER_UUID>",
            "encryption": "<VLESS_ENCRYPTION_KEY>",
            "flow": ""
          }
        ]
      }
    ]
  },
  "streamSettings": {
    "network": "xhttp",
    "security": "tls",
    "xhttpSettings": {
      "mode": "packet-up",
      "host": "48typmw3qq.a.trbcdn.net",
      "path": "/assets/api/v1/",
      "xPaddingBytes": "100-500",
      "xPaddingObfsMode": true,
      "xPaddingPlacement": "header",
      "xPaddingMethod": "tokenish"
    },
    "tlsSettings": {
      "serverName": "48typmw3qq.a.trbcdn.net",
      "alpn": ["h2", "http/1.1"]
    }
  }
}
```

`fingerprint: chrome` можно сохранить, если клиент всегда его добавляет, но схема была проверена и без fingerprint.

Не добавлять параметры из случайного «расширенного» конфига:

- `enableXmux` — такого поля в Xray нет;
- `sessionIDPlacement` — неправильное имя;
- одновременно `xmux.maxConnections` и `xmux.maxConcurrency` — запрещено;
- пустые `certificateFile`/`keyFile` на клиенте;
- `uplinkHTTPMethod: GET`;
- `stream-one`.

## 6. Встраивание outbound в существующий полный конфиг

Существующие inbounds клиента оставить без изменений. Например:

```json
"inbounds": [
  {
    "tag": "socks",
    "listen": "127.0.0.1",
    "port": 10808,
    "protocol": "socks",
    "settings": {
      "udp": true,
      "auth": "noauth"
    }
  }
]
```

Добавить Beeline outbound в `outbounds`, а существующие `direct` и `block` сохранить:

```json
"outbounds": [
  { "tag": "proxy", "protocol": "vless" },
  { "tag": "direct", "protocol": "freedom" },
  { "tag": "block", "protocol": "blackhole" }
]
```

Default route должен идти в `proxy`, либо routing rules клиента должны явно выбирать этот tag. Правила для локальных сетей, RU-direct, блокировки BitTorrent и DNS можно оставить существующими.

Если приложение позволяет переключать серверы без пересоздания core, оно должно пересоздать outbound или перезапустить core после выбора Beeline-профиля. Горячая подмена только UI-модели без обновления запущенного JSON не сработает.

## 7. Подписка и backend

Если клиент получает профиль из подписки, backend должен передать:

- пользовательский UUID;
- CDN-домен;
- path;
- VLESS Encryption client key;
- признак `short XHTTP session ID required`;
- padding settings.

Рекомендуемый внутренний объект подписки:

```json
{
  "type": "vless-xhttp-cdn",
  "server": "48typmw3qq.a.trbcdn.net",
  "port": 443,
  "uuid": "<USER_UUID>",
  "encryption": "<VLESS_ENCRYPTION_KEY>",
  "xhttp": {
    "mode": "packet-up",
    "path": "/assets/api/v1/",
    "host": "48typmw3qq.a.trbcdn.net",
    "paddingBytes": "100-500",
    "paddingObfsMode": true,
    "paddingPlacement": "header",
    "paddingMethod": "tokenish",
    "shortSessionId": true
  },
  "tls": {
    "serverName": "48typmw3qq.a.trbcdn.net",
    "alpn": ["h2", "http/1.1"]
  }
}
```

`shortSessionId` управляет выбором patched core или feature flag. Не передавать server `decryption` в подписку.

## 8. Изменения UI

Минимально добавить новый тип/вариант профиля:

```text
VLESS · XHTTP · Beeline CDN
```

Пользователю достаточно показывать:

- имя профиля;
- CDN-домен;
- задержку/статус;
- XHTTP `packet-up`;
- TLS включён;
- VLESS Encryption включён.

Не показывать и не логировать VLESS Encryption key полностью.

В диагностике полезно показывать:

```text
Core: Xray 26.6.1-beeline
XHTTP session: base64url/12
Padding: header/tokenish/100-500
CDN: 48typmw3qq.a.trbcdn.net
Last handshake status: 200/400/403
```

## 9. Проверки перед запуском

Клиент должен сначала валидировать JSON:

```bash
xray run -test -config client.json
```

После запуска проверить локальный SOCKS:

```bash
curl -i --proxy socks5h://127.0.0.1:10808 --max-time 20 https://cp.cloudflare.com/generate_204
```

Критерий успеха:

```text
HTTP 204
```

Проверка внешнего адреса:

```bash
curl -sS -L --proxy socks5h://127.0.0.1:10808 --max-time 30 https://2ip.io/
```

В проверенной конфигурации вернулся серверный IP:

```text
87.120.108.29
```

## 10. Обязательные тесты клиента

Разработчику добавить:

1. unit test генерации outbound JSON со всеми полями padding;
2. unit test, что `address`, `host` и `serverName` равны CDN-домену;
3. unit test, что VLESS Encryption не заменяется на `none`;
4. unit test patched core: session ID соответствует `^[A-Za-z0-9_-]{12}$`;
5. regression test: существующие не-XHTTP профили генерируют прежний JSON;
6. startup test `xray run -test`;
7. end-to-end тест `generate_204 == 204`;
8. negative test со штатным core: CDN возвращает `403`;
9. update test: после обновления Xray патч короткого session ID остаётся применён;
10. test переключения профиля и перезапуска активного VPN/core.

## 11. Обновление ядра

Модифицированный core становится частью продукта, поэтому каждое обновление upstream Xray должно выполняться так:

1. обновить fork до нужного тега;
2. повторно применить маленький патч `dialer.go`;
3. собрать все целевые платформы;
4. выполнить `run -test`;
5. выполнить end-to-end через реальный Beeline edge;
6. записать core version и SHA-256 в release metadata клиента.

Обновление приложения не должно молча заменять patched core официальным бинарником — профиль перестанет работать и снова получит `403`.

## 12. Критерии готовности

Функционал считается реализованным, когда:

- приложение использует один patched Xray/libXray;
- старые профили продолжают работать;
- новый профиль генерирует точный outbound из раздела 5;
- в XHTTP используется короткий 12-символьный session ID;
- клиент получает только `encryption`, а не серверный `decryption`;
- `generate_204` возвращает `204`;
- 2ip показывает IP Xray-сервера;
- после перезапуска приложения профиль подключается повторно;
- обновление подписки не теряет нестандартные XHTTP-поля;
- логи не содержат UUID пользователя и ключи VLESS Encryption.
