<div align="center">

<img src="play_assets/play_icon_512_transparent.png" alt="ToBeVPN" width="160" height="160" />

# ToBeVPN

**Современный VPN-клиент для Android с подпиской, выбором серверов и встроенными обновлениями.**

[![Latest Release](https://img.shields.io/github/v/release/Shoolife/ToBeVPN-Android?display_name=tag&sort=semver&color=4CAF50&label=release)](https://github.com/Shoolife/ToBeVPN-Android/releases/latest)
[![Android](https://img.shields.io/badge/Android-9%2B-3A8DFF?logo=android&logoColor=white)](#)

</div>

---

## Что это

ToBeVPN — нативный Android-клиент к VPN-сети с защищённым подключением, управлением подпиской и привязкой устройств. Разделение трафика по приложениям, кросс-устройственная пара через QR, встроенный обновлятор, шифрованное локальное хранилище — всё это.

## Главное

| | |
|--|--|
| 🛡️ **Защищённое подключение** | Современный протокол с маскировкой трафика |
| 📲 **Per-app split tunneling** | Off / Whitelist / Blacklist, live-reconnect при изменении |
| 🔐 **Авторизация** | Без логина/пароля, привязка устройств |
| 💳 **Подписка** | Продление и смена тарифа |
| 📺 **Подключение ТВ** | QR-привязка Android TV или другого устройства |
| 🌍 **Выбор сервера** | Список нод с пингом, флагами, статусом online/offline |
| 🚦 **Speed test** | Замер скорости подключения |
| 🔄 **In-app updater** | Проверка и установка обновлений внутри приложения |
| 🌗 **Light / Dark / RU / EN** | Ручное переключение темы и языка |
| 🔒 **Шифрование данных** | Локальная БД зашифрована, ключ в Android Keystore |
| 🛟 **Fallback proxy** | При недоступности основного бэкенда — автоматический повтор через резервный маршрут |

## Скриншоты

<div align="center">
  <table>
    <tr>
      <td align="center"><b>Главный экран</b></td>
      <td align="center"><b>Подписка</b></td>
      <td align="center"><b>Серверы</b></td>
    </tr>
    <tr>
      <td><img src="docs/screenshots/android/home-connected.jpg" alt="Главный экран ToBeVPN Android" width="240" /></td>
      <td><img src="docs/screenshots/android/subscription.jpg" alt="Экран подписки ToBeVPN Android" width="240" /></td>
      <td><img src="docs/screenshots/android/servers-original.png" alt="Экран выбора сервера ToBeVPN Android" width="240" /></td>
    </tr>
    <tr>
      <td align="center"><b>Статистика</b></td>
      <td align="center"><b>Тест скорости</b></td>
      <td align="center"><b>Приложения VPN</b></td>
    </tr>
    <tr>
      <td><img src="docs/screenshots/android/statistics.jpg" alt="Экран статистики ToBeVPN Android" width="240" /></td>
      <td><img src="docs/screenshots/android/speed-test.jpg" alt="Экран теста скорости ToBeVPN Android" width="240" /></td>
      <td><img src="docs/screenshots/android/app-filter.jpg" alt="Экран фильтрации приложений ToBeVPN Android" width="240" /></td>
    </tr>
  </table>
</div>

## Безопасность

- Локальная БД зашифрована, ключ хранится в аппаратном Android Keystore.
- Чувствительные данные (токены, ключ подписки, email) исключены из резервного копирования.
- Backend-хосты не хранятся в исходниках — подставляются при сборке.
- Авторизация без долгоживущих токенов на устройстве.

## Связанные репозитории

- **Desktop-клиент:** [ToBeVPN-Desktop](https://github.com/Shoolife/ToBeVPN-Desktop) — Linux / Windows / macOS
- **Android TV-клиент:** [ToBeVPN-Android-TV](https://github.com/Shoolife/ToBeVPN-Android-TV) — Android TV / приставки
- *(планируется)* iOS-клиент

## Roadmap

- [ ] Поддержка протокола Hysteria
- [ ] Auto-server selection по latency
- [ ] iOS-клиент
- [ ] Виджет быстрого подключения для launcher
- [ ] Per-app traffic statistics

## Contributing

Issue welcome. PR — лучше предварительно обсудить через issue. Коммиты — present tense, conventional commits не обязательны но приветствуются.

## Лицензия

Проприетарное приложение. Исходный код предоставляется для прозрачности и self-host'инга — коммерческое использование/перепродажа запрещены.

---

<div align="center">
  <sub>Сделано с ❤️ командой <b>ToBeVPN × Meow VPN</b></sub>
</div>
