# FLOTJI-chat 2.1

Панель приложений: слева иконки, справа настоящая веб-версия сервиса.

Вход в аккаунт сохраняется между запусками.

## Приложения

| Иконка | Сервис | Адрес |
|---|---|---|
| TG | Telegram | web.telegram.org/k |
| VK | VK Мессенджер | vk.com/im |
| YM | Яндекс Музыка | music.yandex.ru |
| TT | TikTok | tiktok.com |
| G | Google | google.com |
| AI | MASS AI | dsh.mass-ai.ru |

## Возможности

- Строка состояния: время, дата, погода (Open-Meteo).
- Настройки (⚙): город погоды, тёмная/светлая тема, вид «FLOTJI-chat», скрытые иконки, очистка данных.
- Долгое нажатие на иконку: обновить / открыть в браузере / выйти / скрыть.
- Кнопка ««» — свернуть панель.
- В памяти держится только один WebView — меньше лагов.

## Сборка

GitHub Actions собирает APK автоматически при push в main.

APK: Actions → Build APK → Artifacts → FlotjiHub-APK.

## Структура

```
app/src/main/java/ru/flotji/hub/MainActivity.kt   — весь код приложения
app/src/main/res/drawable/                        — векторные иконки
app/src/main/res/values/                          — строки и тема
app/src/main/AndroidManifest.xml                  — манифест
.github/workflows/build.yml                       — сборка APK
```
