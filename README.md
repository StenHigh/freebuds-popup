# Buds Popup

Карточка с зарядом при подключении Huawei FreeBuds — как на телефонах Huawei, только на **Android (Xiaomi / HyperOS)** и **macOS**.

Заряд левого и правого наушника, кейса, статус зарядки и режим шумоподавления читаются по служебному SPP-каналу Huawei. Протокол портирован из [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) (GPL-3.0), поэтому проект тоже под GPL-3.0.

```
android/   Kotlin-приложение (+ протокол на Java, покрыт тестами)
macos/     Swift menu bar-приложение (IOBluetooth + SwiftUI), сборка одной командой
.github/   CI: собирает APK и BudsPopup.app, прогоняет тесты протокола
```

## Как получить сборки без Android Studio и Xcode

1. Создай приватный репозиторий на GitHub и запушь туда эту папку.
2. Открой вкладку **Actions** → workflow `build` запустится сам.
3. Через ~5 минут в разделе **Artifacts** будут лежать `BudsPopup-android` (APK) и `BudsPopup-macos` (zip с .app).

## Android (Mi 14)

Сборка локально: открой папку `android/` в Android Studio → *Build → Build APK(s)*. Или `gradle assembleRelease`, если Android SDK уже стоит.

После установки открой **Buds Popup** и пройди пункты сверху вниз:

1. **Разрешения:** Bluetooth, показ поверх других окон, работа без ограничений батареи.
2. **HyperOS:** включи *Автозапуск*. В *Других разрешениях* включи *Всплывающие окна в фоне*. Без этого HyperOS не даст приложению проснуться.
3. **Наушники:** «Авто» подхватит любые FreeBuds. Можно выбрать конкретную пару.
4. **Показать карточку сейчас** — для проверки. Строка «Последнее событие» покажет, что пришло от наушников.

Как это работает:
- Приложение ловит системное событие `ACL_CONNECTED` в момент открытия кейса. Фоновый сервис не нужен.
- Затем показывает карточку снизу экрана и за ~1 секунду подтягивает проценты.
- Карточка закрывается сама, по тапу или свайпом вниз.

## macOS

```bash
xcode-select --install        # один раз, если нет Command Line Tools
cd macos && ./build.sh        # ARCH=universal ./build.sh — для arm64+x86_64
mv build/BudsPopup.app /Applications/ && open /Applications/BudsPopup.app
```

- В строке меню появится значок наушников.
- При подключении FreeBuds к Mac справа сверху выезжает карточка.
- В меню есть последний известный заряд и пункт «Запускать при входе».
- При первом запуске macOS спросит доступ к Bluetooth — разреши.
- Приложение подписано ad-hoc. Если Gatekeeper ругается: ПКМ → Открыть, или выполни `xattr -dr com.apple.quarantine /Applications/BudsPopup.app`.

## Известные ограничения

- **FreeBuds 7i нет в списке OpenFreebuds.** Протокол взят с 6i: общий формат пакетов, та же команда заряда `01 08`, RFCOMM-канал 1. Если заряд не читается, карточка всё равно покажется с пометкой «нет данных о заряде», а причина будет в строке «Последнее событие».
- **Huawei Audio Connect / AI Life** держит тот же служебный канал. Если оно висит в фоне, заряд может не прочитаться — закрой его.
- **Всплытие происходит в момент подключения, а не открытия крышки.** Обычно это одно и то же, потому что наушники подключаются сразу. Но если кейс открыли, а наушники не подключились (например, Bluetooth выключен), карточки не будет.
- **Карточка при открытом кейсе и multipoint:** с включённым multipoint Mac и телефон покажут карточку одновременно, каждый у себя.

## Тесты протокола

```bash
# Java (из android/app/src)
javac -d /tmp/t main/java/by/dzianis/budspopup/protocol/HuaweiSpp.java test/java/by/dzianis/budspopup/protocol/HuaweiSppTest.java
java -cp /tmp/t by.dzianis.budspopup.protocol.HuaweiSppTest

# Swift (из macos/)
swiftc Sources/HuaweiSpp.swift Tests/main.swift -o build/tests && ./build/tests
```

Эталонные байты взяты из тестов OpenFreebuds, включая реальный ответ наушников.
