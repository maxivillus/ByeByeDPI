<div align="center">
  <p>
    <img src="https://github.com/romanvht/ByeDPIAndroid/raw/master/.github/images/app.svg" alt="Логотип ByeDPI" width="200" />
  </p>
  <h1>ByeByeDPI Android</h1>
  <p>
    Русский |
    <a href="README-en.md">English</a> |
    <a href="README-tr.md">Türkçe</a>
  </p>
  <p>
    <a href="https://github.com/maxivillus/ByeByeDPI/actions/workflows/build.yml"><img src="https://github.com/maxivillus/ByeByeDPI/actions/workflows/build.yml/badge.svg" alt="Build Status" /></a>
    <a href="https://github.com/maxivillus/ByeByeDPI/releases"><img src="https://img.shields.io/github/downloads/maxivillus/ByeByeDPI/total" alt="Downloads" /></a>
    <a href="https://github.com/maxivillus/ByeByeDPI/blob/main/LICENSE"><img src="https://img.shields.io/github/license/maxivillus/ByeByeDPI" alt="License" /></a>
  </p>
</div>

> [!IMPORTANT]
> Это **форк** проекта [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI), в который добавлен
> **SSH-туннель** (подключение к своему серверу и раздача трафика приложений через него) и
> **управление SSH-ключами**. Всё остальное — работа ByeDPI, режимы VPN/Proxy, тест стратегий —
> осталось как в оригинале. Сообщения об ошибках базового приложения присылайте в upstream,
> вопросы по SSH-функциям — в issues этого репозитория.

Приложение для Android, которое локально запускает ByeDPI и перенаправляет весь трафик через него.

Для стабильной работы может потребоваться изменить настройки. Подробнее о различных настройках можно прочитать в [документации ByeDPI](https://github.com/hufrea/byedpi/blob/main/README.md).

Приложение не является VPN. Оно использует VPN-режим на Android для перенаправления трафика, но не передает ничего на удаленный сервер. Оно не шифрует трафик и не скрывает ваш IP-адрес.

> В режиме **SSH-туннеля** это утверждение меняется: трафик уходит на указанный вами сервер,
> поэтому выходной IP будет адресом сервера, а владелец сервера видит незашифрованные
> имена хостов (SNI). Без включённого SSH-туннеля приложение работает как обычно.

У приложения есть единственный официальный сайт -> https://byebyedpi.xyz

---

### Возможности
* Автозапуск сервиса при старте устройства
* Сохранение списков параметров командной строки
* Улучшена совместимость с Android TV/BOX
* Раздельное туннелирование приложений
* Импорт/экспорт настроек
* SSH-туннель через свой сервер (список хостов, вход по паролю или ключу)

### SSH-туннель
Настройки → «SSH-туннель» → «SSH-хосты»: добавьте сервер, отметьте его и включите «Использовать SSH-туннель».

Трафик приложений идёт через SSH-сервер, а само SSH-соединение — через ByeDPI (десинк применяется к потоку SSH):
```
приложения → VPN(tun2socks) → SSH-форвардер → ByeDPI (desync) → SSH-сервер → интернет
```
* Вход по паролю или приватному ключу (OpenSSH, включая Ed25519; ключ импортируется из файла)
* Управление ключами как в ConnectBot: генерация RSA/ECDSA/Ed25519/DSA, импорт (файл или буфер), шифрование паролем, экспорт в OpenSSH/PEM, публичный ключ для authorized_keys
* Ключ сервера запоминается при первом подключении (TOFU), отпечаток виден в редакторе хоста
* Экран «Логи приложения» (Настройки → SSH-туннель): диагностика туннеля с выгрузкой в файл
* DNS работает через mapdns (по TCP), UDP и QUIC через SSH не проходят — приложения переключаются на TCP
* Рекомендуется держать SSH на порту 443: так соединение меньше похоже на SSH для DPI
* Если нужен работающий десинк внутри туннеля — снимите ограничение протоколов в настройках
  десинхронизации (сейчас по умолчанию стоит `-Kt,h`, то есть только TLS/HTTP, а внутри
  туннеля трафик для ByeDPI выглядит как SSH)

### Использование
* Для работы автозапуска активируйте пункт в настройках.
* Рекомендуется подключится один раз к VPN, чтобы принять запрос.
* После этого, при загрузке устройства, приложение автоматически запустит сервис в зависимости от настроек (VPN/Proxy)
* Комплексная инструкция от комьюнити [ByeByeDPI-Manual](https://byebyedpi.xyz)

### Установка готовых APK

Скачать собранные APK можно в [Releases](../../releases) или со страницы
[Actions](../../actions/workflows/build.yml) (артефакт последней сборки).

* `*-debug.apk` — **устанавливаются сразу** (подписаны debug-ключом), самый простой вариант.
* `*-release.apk` — появляются только если в репозитории настроены секреты подписи
  (см. ниже); эти APK меньше и быстрее, и именно их стоит использовать постоянно.

> Приложение не публикуется ни в Google Play, ни в IzzyOnDroid — только сборки из этого репозитория.

### Сборка

Требования: **JDK 17**, Android SDK (platform 36, build-tools 36.0.0),
**NDK 27.0.12077973** и **CMake 3.22.1** (версии закреплены в проекте).

1. Клонируйте репозиторий вместе с сабмодулями:
```bash
git clone --recurse-submodules https://github.com/maxivillus/ByeByeDPI.git
cd ByeByeDPI
```
2. **Только на Windows** — один раз почините симлинки сабмодуля (см. пояснение ниже):
```bash
bash scripts/fix_windows_symlinks.sh
```
3. Соберите APK:
```bash
./gradlew assembleDebug      # отладочная сборка, подписана debug-ключом
./gradlew assembleRelease    # релизная сборка (без секретов будет unsigned)
```
4. APK появятся в `app/build/outputs/apk/debug/` и `app/build/outputs/apk/release/`.

Файл `local.properties` с путём к SDK (`sdk.dir=...`) создаётся Android Studio автоматически
либо задаётся вручную.

#### Почему на Windows нужен скрипт

В репозиториях `hev-socks5-tunnel`, `hev-task-system` и `yaml` часть заголовков — это
симлинки. Git на Windows (при `core.symlinks=false`, а это значение по умолчанию без
Developer Mode) выкачивает их как текстовые файлы, содержащие путь. Компилятор видит
пустые заголовки и сборка падает с `unknown type name 'HevObjectAtomic'`.
`scripts/fix_windows_symlinks.sh` заменяет такие заглушки реальным содержимым файлов.
На Linux и macOS скрипт ничего не делает — там симлинки и так корректные.

#### Подписанные релизы (необязательно)

Чтобы CI собирал подписанные release-APK, добавьте в
**Settings → Secrets and variables → Actions** четыре секрета:

| Секрет | Значение |
| --- | --- |
| `BYEDPI_KEYSTORE_BASE64` | ваш keystore в base64: `base64 -w0 release.keystore` |
| `BYEDPI_KEYSTORE_PASSWORD` | пароль хранилища |
| `BYEDPI_KEY_ALIAS` | alias ключа |
| `BYEDPI_KEY_PASSWORD` | пароль ключа |

Без этих секретов сборка тоже проходит, но release-APK остаются неподписанными
(их нельзя установить), а в артефакты попадают debug-APK.

### Автоматическая сборка (GitHub Actions)

`.github/workflows/build.yml` собирает APK при каждом push в `main`/`master`,
в pull request и по кнопке **Run workflow**. Артефакты (`byebyedpi-apk-<sha>`,
хранятся 30 дней) доступны на вкладке **Actions**.

При пуше тега вида `v1.7.9` создаётся GitHub Release с APK: подписанными, если
настроены секреты, иначе — с debug-сборками.

### Зависимости
- [ByeDPI](https://github.com/hufrea/byedpi)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [sshlib](https://github.com/connectbot/sshlib) - SSH-движок ConnectBot (для SSH-туннеля).
  **Встроен в исходники** (`app/src/sshlib-java`, Apache-2.0) вместо Maven-зависимости, чтобы
  логировать отказы каналов, которые оригинальная библиотека молча проглатывает.

### Хеш подписи
SHA-256 (debug-ключ, сборки из этого репозитория):
`77:45:10:75:AC:EA:40:64:06:47:5D:74:D4:59:88:3A:49:A6:40:51:FA:F3:2E:42:F7:18:F3:F9:77:7A:8D:FB`

> Хеш относится к оригинальной подписи upstream. Если вы собираете релиз со своим ключом,
> подпись будет другой.

### Благодарность
- [hufrea](https://github.com/hufrea) - за [ByeDPI](https://github.com/hufrea/byedpi)
- [dovecoteescapee](https://github.com/dovecoteescapee) - за изначальную реализацию [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid)
