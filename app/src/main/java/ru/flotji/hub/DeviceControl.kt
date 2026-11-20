package ru.flotji.hub

import android.app.Activity
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.util.Calendar
import java.util.Locale

/**
 * Управление устройством по голосовой команде.
 * handle() возвращает текст ответа, если команда распознана, или null — тогда вопрос уходит в ИИ.
 */
class DeviceControl(
    private val act: Activity,
    private val weatherProvider: () -> String,
    private val hubOpener: (String) -> Boolean
) {

    private var torch = false
    private var cache: List<Pair<String, String>>? = null

    private val synonyms = mapOf(
        "тг" to "telegram",
        "телега" to "telegram",
        "телеграм" to "telegram",
        "телеграмм" to "telegram",
        "вконтакте" to "vk",
        "вк" to "vk",
        "ютуб" to "youtube",
        "тик ток" to "tiktok",
        "хром" to "chrome",
        "гугл" to "chrome",
        "камера" to "@camera",
        "часы" to "@clock",
        "будильник" to "@clock",
        "настройки" to "@settings"
    )

    fun handle(raw: String): String? {
        val q = raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (q.isEmpty()) return null
        val p = " $q "

        if (p.contains(" что ты умеешь ") || p.contains(" помощь ") || p.contains(" твои команды ")) return HELP
        if (p.contains(" выключись ") || p.contains(" замолчи ") || p.contains(" хватит ")) return "Хорошо."

        // фонарик
        if (p.contains("фонарик") || p.contains("фонарь") || p.contains("вспышк")) {
            val on = when {
                p.contains("выключ") -> false
                p.contains("включ") -> true
                else -> !torch
            }
            return setTorch(on)
        }

        // время и дата
        if (p.contains("который час") || p.contains("сколько времени") || p.contains("время сейчас")) {
            val c = Calendar.getInstance()
            val hh = c.get(Calendar.HOUR_OF_DAY)
            val mm = c.get(Calendar.MINUTE)
            return "Сейчас $hh ${hourWord(hh)}" + if (mm > 0) " $mm ${minuteWord(mm)}" else " ровно"
        }
        if (p.contains("какое число") || p.contains("какая дата") || p.contains("какой сегодня день")) {
            val c = Calendar.getInstance()
            return "Сегодня " + DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " +
                c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)]
        }

        // погода
        if (p.contains("погод")) return weatherProvider()

        // громкость
        if (p.contains("громче") || p.contains("прибавь звук")) {
            volume(AudioManager.ADJUST_RAISE)
            return "Сделал громче."
        }
        if (p.contains("тише") || p.contains("убавь звук")) {
            volume(AudioManager.ADJUST_LOWER)
            return "Сделал тише."
        }
        if (p.contains("без звука") || p.contains("выключи звук") || p.contains("беззвучн")) {
            volume(AudioManager.ADJUST_MUTE)
            return "Звук выключен."
        }
        if (p.contains("верни звук") || p.contains("включи звук")) {
            volume(AudioManager.ADJUST_UNMUTE)
            return "Звук включён."
        }
        if (p.contains("на максимум") || p.contains("на полную")) {
            maxVolume()
            return "Громкость на максимуме."
        }

        // музыка
        if (p.contains("пауза") || p.contains("останови музыку") || p.contains("стоп музыка")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            return "Пауза."
        }
        if (p.contains("включи музыку") || p.contains("играй") || p.contains("продолжи музыку")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            return "Включаю."
        }
        if (p.contains("следующий трек") || p.contains("следующую песню")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            return "Переключаю."
        }

        // будильник
        Regex("будильник на (\\d{1,2})[:. ]?(\\d{2})?").find(q)?.let { m ->
            val hh = m.groupValues[1].toIntOrNull() ?: 7
            val mm = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hh)
                .putExtra(AlarmClock.EXTRA_MINUTES, mm)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            return start(i, "Будильник на $hh:${pad(mm)}")
        }

        // таймер
        Regex("таймер на (\\d+) ?(секунд|минут|час)").find(q)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            val unit = m.groupValues[2]
            val secs = when {
                unit.startsWith("час") -> n * 3600
                unit.startsWith("секунд") -> n
                else -> n * 60
            }
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return start(i, "Таймер на $n $unit")
        }

        // системные настройки
        if (p.contains("wi-fi") || p.contains("вайфай") || p.contains("вай фай")) {
            val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
            return start(i, "Открываю Wi-Fi")
        }
        if (p.contains("bluetooth") || p.contains("блютуз")) {
            return start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Открываю Bluetooth")
        }
        if (p.contains("настройк")) return start(Intent(Settings.ACTION_SETTINGS), "Открываю настройки")
        if (p.contains("камеру") || p.contains("сфотограф")) {
            return start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Открываю камеру")
        }

        // звонок
        if (q.startsWith("позвони ") || q.startsWith("набери ")) {
            val who = q.removePrefix("позвони").removePrefix("набери").trim()
            val i = Intent(Intent.ACTION_DIAL)
            if (who.any { it.isDigit() }) i.data = Uri.parse("tel:" + who.filter { it.isDigit() || it == '+' })
            return start(i, if (who.isEmpty()) "Открываю набор" else "Открываю набор: $who")
        }

        // открыть приложение
        for (prefix in listOf("открой ", "запусти ", "загрузи ")) {
            if (q.startsWith(prefix)) {
                var name = q.removePrefix(prefix).trim()
                name = name.removeSuffix(" приложение").removeSuffix(" прилу").trim()
                if (name.isEmpty()) return null
                if (hubOpener(name)) return "Открываю $name"
                return openApp(name)
            }
        }

        return null
    }

    // ---------- действия ----------

    private fun setTorch(on: Boolean): String {
        return try {
            val cm = act.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) return "На этом устройстве нет вспышки"
            cm.setTorchMode(id, on)
            torch = on
            if (on) "Фонарик включён" else "Фонарик выключен"
        } catch (e: Exception) {
            "Не удалось переключить фонарик"
        }
    }

    private fun volume(dir: Int) {
        val am = act.getSystemService(AudioManager::class.java)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun maxVolume() {
        val am = act.getSystemService(AudioManager::class.java)
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun mediaKey(code: Int) {
        val am = act.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun openApp(name: String): String {
        val key = synonyms[name] ?: name
        if (key == "@camera") return start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Открываю камеру")
        if (key == "@settings") return start(Intent(Settings.ACTION_SETTINGS), "Открываю настройки")
        if (key == "@clock") return start(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Открываю будильник")

        val list = installed()
        val target = list.firstOrNull { it.first == key }
            ?: list.firstOrNull { it.first.startsWith(key) }
            ?: list.firstOrNull { it.first.contains(key) }
        if (target == null) return "Не нашёл приложение «$name»"
        val i = act.packageManager.getLaunchIntentForPackage(target.second)
            ?: return "Не удалось открыть ${target.first}"
        return start(i, "Открываю ${target.first}")
    }

    private fun installed(): List<Pair<String, String>> {
        cache?.let { return it }
        val pm = act.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val res = pm.queryIntentActivities(i, 0)
        val list = res.map {
            it.loadLabel(pm).toString().lowercase(Locale.ROOT) to it.activityInfo.packageName
        }.distinct()
        cache = list
        return list
    }

    private fun start(i: Intent, ok: String): String {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
            ok
        } catch (e: Exception) {
            "Не получилось запустить"
        }
    }

    private fun pad(v: Int): String = if (v < 10) "0$v" else "$v"

    private fun hourWord(h: Int): String {
        val n = h % 10
        val m = h % 100
        return if (m in 11..14) "часов" else when (n) {
            1 -> "час"
            2, 3, 4 -> "часа"
            else -> "часов"
        }
    }

    private fun minuteWord(v: Int): String {
        val n = v % 10
        val m = v % 100
        return if (m in 11..14) "минут" else when (n) {
            1 -> "минута"
            2, 3, 4 -> "минуты"
            else -> "минут"
        }
    }

    companion object {
        private val DAYS = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")
        private val MONTHS = arrayOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
        )
        private const val HELP =
            "Я могу включить фонарик, открыть приложение, поставить будильник или таймер, " +
                "позвонить, изменить громкость, управлять музыкой, открыть Wi-Fi и настройки, " +
                "сказать время, дату и погоду. И отвечаю на любые вопросы."
    }
}
