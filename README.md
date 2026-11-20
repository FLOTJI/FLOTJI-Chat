# FLOTJI-chat 5.0

Панель сервисов (Telegram, VK, Яндекс Музыка, TikTok, Google, MASS AI)
плюс голосовой помощник FLOTJI.

## Сборка

GitHub Actions собирает APK автоматически при push в `main`.

Артефакт: Actions → последний запуск → `FlotjiChat-APK` → `app-debug.apk`

Локально:

```
gradle wrapper --gradle-version 8.7
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Голос

Скажите «Флотжи, …». Микрофон работает, пока приложение открыто.

Примеры команд:

- «Флотжи, который час»
- «Флотжи, включи фонарик»
- «Флотжи, будильник на 7:30»
- «Флотжи, громче» / «тише» / «без звука»
- «Флотжи, заряд батареи»
- «Флотжи, открой ютуб»
- «Флотжи, пауза» / «играй» / «следующий»

Всё, что не распознано как локальная команда, уходит в AI-ответчик.

## Структура

```
app/src/main/java/ru/flotji/hub/
  MainActivity.kt    — UI, WebView, погода, настройки
  VoiceAssistant.kt  — распознавание речи + TTS
  DeviceControl.kt   — локальные команды устройства
  AiClient.kt        — запрос к AI
```

Зависимостей нет: только Android SDK и Kotlin.
