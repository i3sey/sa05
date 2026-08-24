# yctun — GET-туннель через Yandex Cloud CDN

Рабочий канал связи: SOCKS5 на клиенте → GET-запросы через CDN Яндекса →
relayd на VPS → интернет с IP VPS. Без WebSocket, без POST, без заявок
в поддержку — только GET, как обычный веб-трафик.

## Архитектура

```
[приложение] → SOCKS5 127.0.0.1:1080 → relayc
   → HTTPS GET /s/<sid>/... → Yandex Cloud CDN (домен dom.sa05.eu.cc)
   → relayd (VPS :8081, origin CDN) → интернет (IP VPS)
```

- **Крипто**: X25519 ECDH + HKDF-SHA256 + XChaCha20-Poly1305 (сквозная,
  CDN видит только шум), реплей-защита, случайный паддинг.
- **Mux**: потоки (каждый = TCP-соединение) с seq/ack, окнами 2МБ,
  реордерингом и ретрансмитом после обрыва канала.
- **Аплинк**: GET с фреймами в path (до ~49КБ URL, уникальный nonce),
  6 параллельных воркеров, батчинг.
- **Даунлинк**: 4 параллельных стримовых GET (chunked, хартбит каждые 2с,
  батчинг до 256КБ, ротация каждые 2 мин); фолбэк — поллинг в телах
  ответов аплинка (если стримы недоступны).
- **Маскировка**: браузерные User-Agent, cover-страница на корне,
  паддинг, no-store, ничего паттернового.

## Сборка

```bash
cd tunnel
go build -o relayc ./cmd/relayc
GOOS=linux GOARCH=amd64 go build -o relayd ./cmd/relayd
# Windows-клиент: GOOS=windows GOARCH=amd64 go build -o relayc.exe ./cmd/relayc
```

## Сервер (VPS) — уже развёрнут

- Бинарь: `/usr/local/bin/yctun-relayd`, сервис `yctun.service`
- Конфиг: `/etc/yctun/relayd.json`, ключ `/etc/yctun/relayd.key`
- Управление: `systemctl restart yctun`, логи: `journalctl -u yctun -f`
- Первичная генерация ключа: `yctun-relayd -genkey /etc/yctun/relayd.key`

## Клиент

`relayc.json`:
```json
{
  "base_url": "https://dom.sa05.eu.cc",
  "psk": "<hex32 из /etc/yctun/relayd.json>",
  "server_pub": "<hex64: pubkey сервера>",
  "listen": "127.0.0.1:1080",
  "stream": true,
  "streams": 4,
  "workers": 6,
  "chunk": 12288,
  "poll_ms": 400
}
```

Запуск: `./relayc -config relayc.json`
Использование: любой SOCKS5-клиент на 127.0.0.1:1080
(`curl --socks5-hostname 127.0.0.1:1080 https://ifconfig.me`
должен показать IP VPS).

## Замеры (через CDN, из песочницы с прокси)

- Выход в интернет с IP VPS: ✓ (ifconfig.me → 191.44.112.83)
- example.com 30/30 подряд ✓
- Скачивание: ~350-400 КБ/с через прокси песочницы (потолок среды;
  на прямой машине ожидаемо выше — 4 стрима + крупные батчи)

## Тюнинг

| Параметр | Что делает |
|---|---|
| `streams` | число параллельных стримов даунлинка (1-8) |
| `workers` | параллельных аплинк-запросов |
| `chunk` | байт аплинка на GET (макс ~36000, лимит URL 49КБ) |
| `poll_ms` | интервал heartbeat при простое |

## Файлы

- `cmd/relayd` — сервер (origin для CDN)
- `cmd/relayc` — клиент (SOCKS5)
- `internal/proto` — крипто + wire-формат
- `internal/session` — mux потоков
- `internal/chanhttp` — HTTP-транспорт (uplink/стрим/poll)
- `internal/socks5` — SOCKS5-сервер
