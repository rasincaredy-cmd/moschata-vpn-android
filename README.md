# 🦫 Moschata VPN for Android

[![Release](https://img.shields.io/github/v/release/rasincaredy-cmd/moschata-vpn-android?style=flat-square&color=blue)](https://github.com/rasincaredy-cmd/moschata-vpn-android/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/rasincaredy-cmd/moschata-vpn-android/android-release.yml?branch=master&style=flat-square)](https://github.com/rasincaredy-cmd/moschata-vpn-android/actions)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-green.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg?style=flat-square)](https://android.com)
[![Telegram](https://img.shields.io/badge/Telegram-@MoschataVPN__bot-2CA5E0?style=flat-square&logo=telegram)](https://t.me/MoschataVPN_bot)

Официальный Android-клиент для сервиса **Moschata VPN** ([@MoschataVPN_bot](https://t.me/MoschataVPN_bot)).

Приложение объединяет в одном интерфейсе скоростной WireGuard-туннель, специальный режим **«Обход Белого Списка»** (маскировка трафика под медиапотоки звонков VK TURN) для условий жестких сетевых ограничений, а также встроенный инструмент экспресс-диагностики блокировок (**White-List Test**).

---

## 🚀 Ключевые возможности

- ⚡ **Два режима подключения в одном клиенте:**
  - **Прямой туннель (WireGuard):** максимальная скорость и минимальный пинг для повседневного использования.
  - **Обход Белого Списка (qWDTT over VK TURN):** туннелирование трафика через WebRTC-инфраструктуру медиасерверов VK. Для оператора связи трафик выглядит как стандартный голосовой или видеозвонок, что позволяет выходить в открытый интернет даже в условиях работы мобильной сети исключительно по «белым спискам».
- 🔍 **Встроенный White-List Test:**
  - Мгновенная проверка доступности сети (открытый интернет, изоляция белым списком или отсутствие связи).
  - Встроенный доверенный профиль сертификатов Минцифры для корректной проверки отечественных шлюзов без ложных срабатываний.
- 🔄 **Автоматические подписки:**
  - Поддержка динамических подписок: достаточно один раз добавить ссылку из [@MoschataVPN_bot](https://t.me/MoschataVPN_bot). Приложение автоматически синхронизирует и обновляет конфигурации серверов.
- 📲 **Обновление в один клик:**
  - Встроенная проверка релизов прямо в приложении: при выходе новой версии клиент сам скачает APK и предложит обновиться без необходимости вручную искать файлы на GitHub.
- 🛡️ **Полный контроль и приватность:**
  - **Никаких скрытых авто-подключений:** соединение инициируется строго по кнопке пользователя.
  - **Раздельное туннелирование (Split Tunneling):** гибкий выбор приложений, работающих через VPN или напрямую.
  - **Замер пинга и диагностика:** быстрый замер задержки серверов и удобный отчет для отладки.

---

## 📥 Скачать приложение

Готовые подписанные APK-файлы доступны на странице [**GitHub Releases**](https://github.com/rasincaredy-cmd/moschata-vpn-android/releases):

| Версия | Архитектура | Ссылка на скачивание |
| :--- | :--- | :--- |
| **v1.5.9 (Актуальная)** | **arm64-v8a** *(Рекомендуется для большинства смартфонов)* | [⬇️ **Скачать APK (arm64-v8a)**](https://github.com/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.5.9/moschata-vpn-1.5.9-arm64-v8a.apk) |
| **v1.5.9 (Актуальная)** | **Universal** *(Для всех типов процессоров и эмуляторов)* | [⬇️ **Скачать APK (Universal)**](https://github.com/rasincaredy-cmd/moschata-vpn-android/releases/download/v1.5.9/moschata-vpn-1.5.9-universal.apk) |

---

## 📖 Как начать пользоваться

1. **Скачайте и установите** актуальный `.apk` файл из таблицы выше (или со страницы релизов).
2. **Получите ссылку подписки:**
   - Откройте Telegram-бота [**@MoschataVPN_bot**](https://t.me/MoschataVPN_bot).
   - Скопируйте персональную ссылку на подписку.
3. **Импортируйте подписку в приложение:**
   - На вкладке «Профили» нажмите кнопку **`+`** и выберите **«Подписка»** (или **«Из буфера»**).
   - Приложение автоматически загрузит список доступных серверов.
4. **Подключитесь:**
   - Нажмите кнопку питания на главном экране и разрешите создание VPN-соединения в системе Android.

---

## 🛠️ Сборка из исходников

Для самостоятельной сборки приложения требуется:
- JDK 17+
- Android SDK (API 34)
- Go 1.21+ (для компиляции GoBackend)

```bash
git clone https://github.com/rasincaredy-cmd/moschata-vpn-android.git
cd moschata-vpn-android

# Сборка релизного APK
./gradlew assembleRelease
```
Собранные файлы будут доступны в каталоге `app/build/outputs/apk/release/`.

---

## 💬 Поддержка и обратная связь

- Бот управления сервисом и поддержка: [**@MoschataVPN_bot**](https://t.me/MoschataVPN_bot)
- Сообщить о проблеме: [GitHub Issues](https://github.com/rasincaredy-cmd/moschata-vpn-android/issues)

---

## 📄 Лицензия и происхождение

Проект распространяется под свободной лицензией [GNU GPL v3](LICENSE).

Проект развивает технологии туннелирования через медиапротоколы WebRTC / TURN, заложенные в проектах WDTT и qWDTT, адаптируя их для экосистемы **Moschata VPN** со встроенным управлением подписками, тестированием белых списков и поддержкой протоколов нового поколения.
