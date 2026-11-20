package ru.flotji.hub

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceControl(private val ctx: Context) {

    private var torchOn = false

    fun handle(cmd: String): String? {
        val t = cmd.lowercase(Locale("ru", "RU")).trim()

        if (t.contains("фонар")) return torchSwitch(t)

        if (t.contains("который час") || t.contains("сколько времени") || t == "время") {
            val f = SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date())
            return "Сейчас $f"
        }

        if (t.contains("какое число") || t.contains("какая дата") || t.contains("какой сегодня день")) {
            val f = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("ru", "RU")).format(Date())
            return "Сегодня $f"
        }

        if (t.contains("будильник")) {
            val m = Regex("(\\d{1,2})[:. ](\\d{2})").find(t)
                ?: return "Скажите время, например: будильник на 7:30"
            val h = m.groupValues[1].toIntOrNull() ?: 7
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
            i.putExtra(AlarmClock.EXTRA_HOUR, h)
            i.putExtra(AlarmClock.EXTRA_MINUTES, min)
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(i)
                val mm = if (min < 10) "0$min" else "$min"
                "Будильник на $h:$mm поставлен"
            } catch (e: Exception) {
                "Не удалось поставить будильник"
            }
        }

        if (t.contains("таймер")) {
            val num = Regex("(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: return "Скажите длительность, например: таймер на 5 минут"
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
            i.putExtra(AlarmClock.EXTRA_LENGTH, num * 60)
            i.putExtra(AlarmClock.EXTRA_MESSAGE, "Таймер FLOTJI")
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(i)
                "Таймер на $num мин"
            } catch (e: Exception) {
                "Не удалось поставить таймер"
            }
        }

        if (t.contains("заряд") || t.contains("батаре")) {
            return try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                "Заряд батареи $level процентов"
            } catch (e: Exception) {
                "Не удалось узнать заряд"
            }
        }

        if (t.contains("без звука") || t.contains("громче") || t.contains("тише") ||
            t.contains("на максимум") || t.contains("громкость")
        ) {
            return try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (t.contains("без звука")) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Звук выключен"
                } else if (t.contains("максимум")) {
                    am.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        0
                    )
                    "Громкость на максимум"
                } else {
                    val dir = if (t.contains("громче")) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                    if (t.contains("громче")) "Сделал громче" else "Сделал тише"
                }
            } catch (e: Exception) {
                "Не удалось изменить громкость"
            }
        }

        if (t.contains("пауза") || t.contains("играй") || t.contains("продолжи") ||
            t.contains("следующий") || t.contains("дальше")
        ) {
            return try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val key = when {
                    t.contains("пауза") -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    t.contains("играй") || t.contains("продолжи") -> KeyEvent.KEYCODE_MEDIA_PLAY
                    else -> KeyEvent.KEYCODE_MEDIA_NEXT
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                "Готово"
            } catch (e: Exception) {
                "Не удалось управлять плеером"
            }
        }

        if (t.contains("позвони") || t.contains("набери")) {
            val digits = t.filter { it.isDigit() }
            if (digits.length < 3) return "Скажите номер, например: позвони 89991234567"
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(i)
                "Набираю $digits"
            } catch (e: Exception) {
                "Не удалось открыть звонилку"
            }
        }

        if (t.contains("wi-fi") || t.contains("вайфай") || t.contains("wifi") || t.contains("вай фай")) {
            val i = Intent(Settings.ACTION_WIFI_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(i)
                "Открываю настройки Wi-Fi"
            } catch (e: Exception) {
                "Не удалось открыть настройки"
            }
        }

        if (t.contains("настройки")) {
            val i = Intent(Settings.ACTION_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(i)
                "Открываю настройки"
            } catch (e: Exception) {
                "Не удалось открыть настройки"
            }
        }

        if (t.startsWith("открой") || t.startsWith("запусти")) {
            val q = t.removePrefix("открой").removePrefix("запусти")
                .trim()
                .removePrefix("приложение")
                .trim()
            if (q.isNotEmpty()) {
                val r = openApp(q)
                if (r != null) return r
            }
        }

        return null
    }

    private fun torchSwitch(t: String): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Фонарик недоступен"

            val on = when {
                t.contains("выключ") || t.contains("отключ") || t.contains("погас") -> false
                t.contains("включ") || t.contains("вруб") -> true
                else -> !torchOn
            }
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "Фонарик включён" else "Фонарик выключен"
        } catch (e: Exception) {
            "Не удалось переключить фонарик"
        }
    }

    private fun openApp(query: String): String? {
        return try {
            val pm = ctx.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
            val hit = list.firstOrNull {
                it.loadLabel(pm).toString().lowercase(Locale("ru", "RU")).contains(query)
            } ?: return null
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            "Открываю ${hit.loadLabel(pm)}"
        } catch (e: Exception) {
            null
        }
    }
}
