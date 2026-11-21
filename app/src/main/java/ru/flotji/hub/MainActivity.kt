package ru.flotji.hub

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private data class App(
        val id: String,
        val title: String,
        val short: String,
        val url: String,
        val color: Int,
        val iconRes: Int
    )

    private val apps = listOf(
        App("tg", "Telegram", "TG", "https://web.telegram.org/k/", 0xFF2AABEE.toInt(), R.drawable.ic_tg),
        App("vk", "VK Мессенджер", "VK", "https://vk.com/im", 0xFF0077FF.toInt(), R.drawable.ic_vk),
        App("ym", "Яндекс Музыка", "YM", "https://music.yandex.ru", 0xFFF5A623.toInt(), R.drawable.ic_ym),
        App("tt", "TikTok", "TT", "https://www.tiktok.com", 0xFFEE1D52.toInt(), R.drawable.ic_tt),
        App("gg", "Google", "G", "https://www.google.com/", 0xFF4285F4.toInt(), R.drawable.ic_google),
        App("ai", "MASS AI", "AI", "https://dsh.mass-ai.ru/", 0xFF6C5CE7.toInt(), R.drawable.ic_ai)
    )

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var rail: LinearLayout
    private lateinit var webFrame: FrameLayout
    private lateinit var weatherTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var micBtn: TextView
    private lateinit var bubble: TextView

    private val buttons = HashMap<String, View>()
    private var web: WebView? = null
    private var currentId: String? = null
    private var voice: VoiceAssistant? = null

    private var lat = 55.7558
    private var lon = 37.6173
    private var city = "Москва"

    private val isDark: Boolean
        get() = prefs.getString("theme", "dark") == "dark"

    private val isCamo: Boolean
        get() = prefs.getBoolean("camo", false)

    private val cBg: Int get() = if (isDark) 0xFF1E1F22.toInt() else 0xFFFFFFFF.toInt()
    private val cRail: Int get() = if (isDark) 0xFF2B2D31.toInt() else 0xFFF2F3F5.toInt()
    private val cText: Int get() = if (isDark) 0xFFDBDEE1.toInt() else 0xFF060607.toInt()
    private val cSub: Int get() = if (isDark) 0xFF949BA4.toInt() else 0xFF5C5E66.toInt()

    private val hideBubble = Runnable { bubble.visibility = View.GONE }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, r: Int, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(r).toFloat()
        d.setColor(fill)
        if (strokePx > 0) d.setStroke(strokePx, strokeColor)
        return d
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("fh", Context.MODE_PRIVATE)
        lat = prefs.getFloat("lat", 55.7558f).toDouble()
        lon = prefs.getFloat("lon", 37.6173f).toDouble()
        city = prefs.getString("city", "Москва") ?: "Москва"

        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        buildUi()
        renderRail()
        restoreLast()
        updateClock()

        ui.postDelayed(object : Runnable {
            override fun run() {
                updateClock()
                ui.postDelayed(this, 20000)
            }
        }, 1000)

        loadWeather()
    }

    override fun onResume() {
        super.onResume()
        web?.resumeTimers()
        updateClock()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        web?.pauseTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        destroyWeb()
        voice?.shutdown()
        voice = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleVoice()
            } else {
                Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.HORIZONTAL
        root.setBackgroundColor(cBg)

        rail = LinearLayout(this)
        rail.orientation = LinearLayout.VERTICAL
        rail.gravity = Gravity.CENTER_HORIZONTAL
        rail.setPadding(dp(7), dp(10), dp(7), dp(10))
        rail.setBackgroundColor(cRail)

        val railScroll = ScrollView(this)
        railScroll.isVerticalScrollBarEnabled = false
        railScroll.addView(
            rail,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(railScroll, LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(cBg)

        val status = LinearLayout(this)
        status.orientation = LinearLayout.HORIZONTAL
        status.gravity = Gravity.CENTER_VERTICAL
        status.setPadding(dp(10), dp(2), dp(10), dp(2))

        micBtn = TextView(this)
        micBtn.text = "🎤"
        micBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        micBtn.setPadding(dp(6), dp(4), dp(6), dp(4))
        micBtn.setOnClickListener { toggleVoice() }
        status.addView(micBtn)

        weatherTv = TextView(this)
        weatherTv.text = "⛅ —°"
        weatherTv.setTextColor(cSub)
        weatherTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        weatherTv.setPadding(dp(4), 0, dp(8), 0)
        weatherTv.setOnClickListener { showSettings() }
        status.addView(weatherTv)

        status.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        dateTv = TextView(this)
        dateTv.setTextColor(cSub)
        dateTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        dateTv.setPadding(0, 0, dp(8), 0)
        status.addView(dateTv)

        timeTv = TextView(this)
        timeTv.setTextColor(cText)
        timeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        timeTv.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        status.addView(timeTv)

        content.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
        )

        webFrame = FrameLayout(this)
        content.addView(webFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val host = FrameLayout(this)
        host.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        bubble = TextView(this)
        bubble.setTextColor(0xFFFFFFFF.toInt())
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8))
        bubble.background = rounded(0xFF2B2D31.toInt(), 12)
        bubble.visibility = View.GONE
        val bLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        bLp.bottomMargin = dp(16)
        bLp.leftMargin = dp(16)
        bLp.rightMargin = dp(16)
        host.addView(bubble, bLp)

        setContentView(host)
    }

    private fun renderRail() {
        rail.removeAllViews()
        buttons.clear()

        val hidden: Set<String> = prefs.getStringSet("hidden", HashSet()) ?: HashSet()
        val camo = isCamo

        if (camo) {
            val head = TextView(this)
            head.text = "FLOTJI\nCHAT"
            head.gravity = Gravity.CENTER
            head.setTextColor(cSub)
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            head.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            rail.addView(head, LinearLayout.LayoutParams(dp(54), dp(38)))
        }

        for (a in apps) {
            if (hidden.contains(a.id)) continue

            val tile = FrameLayout(this)
            val fill = if (camo) {
                if (isDark) 0xFF3B3D43.toInt() else 0xFFE6E8EB.toInt()
            } else {
                a.color
            }
            tile.background = rounded(fill, 16)
            tile.isClickable = true

            val lp = LinearLayout.LayoutParams(dp(54), dp(54))
            lp.topMargin = dp(8)
            tile.layoutParams = lp

            if (camo) {
                val tv = TextView(this)
                tv.text = a.short
                tv.setTextColor(cSub)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                tv.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                tile.addView(
                    tv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            } else {
                val iv = ImageView(this)
                iv.setImageResource(a.iconRes)
                tile.addView(iv, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            }

            tile.setOnClickListener { selectApp(a.id) }
            tile.setOnLongClickListener {
                showAppMenu(a.id)
                true
            }

            rail.addView(tile)
            buttons[a.id] = tile
        }

        val gear = TextView(this)
        gear.text = "⚙"
        gear.gravity = Gravity.CENTER
        gear.setTextColor(cSub)
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        val glp = LinearLayout.LayoutParams(dp(48), dp(48))
        glp.topMargin = dp(16)
        gear.layoutParams = glp
        gear.setOnClickListener { showSettings() }
        rail.addView(gear)
    }

    private fun highlight() {
        val camo = isCamo
        for (a in apps) {
            val v = buttons[a.id] ?: continue
            val active = a.id == currentId
            val fill = if (camo) {
                if (isDark) 0xFF3B3D43.toInt() else 0xFFE6E8EB.toInt()
            } else {
                a.color
            }
            val strokeColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1E1F22.toInt()
            v.background = rounded(fill, 16, if (active) dp(2) else 0, if (active) strokeColor else 0)
        }
    }

    private fun selectApp(id: String) {
        val a = apps.firstOrNull { it.id == id } ?: return
        currentId = id
        prefs.edit().putString("last", id).apply()
        highlight()
        destroyWeb()

        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = s.userAgentString.replace("; wv", "")

        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        webFrame.addView(
            w,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        web = w
        w.loadUrl(a.url)
    }

    private fun destroyWeb() {
        val w = web ?: return
        web = null
        webFrame.removeAllViews()
        w.stopLoading()
        w.clearHistory()
        w.removeAllViews()
        w.destroy()
    }

    private fun restoreLast() {
        val hidden: Set<String> = prefs.getStringSet("hidden", HashSet()) ?: HashSet()
        val last = prefs.getString("last", "tg") ?: "tg"
        val target = if (hidden.contains(last)) {
            apps.firstOrNull { !hidden.contains(it.id) }?.id
        } else {
            last
        }
        if (target != null) selectApp(target)
    }

    private fun showAppMenu(id: String) {
        val a = apps.firstOrNull { it.id == id } ?: return
        val items = arrayOf("Обновить", "Открыть в браузере", "Выйти из аккаунта", "Скрыть иконку")
        AlertDialog.Builder(this)
            .setTitle(a.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> web?.reload()
                    1 -> try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)))
                    } catch (e: Exception) {
                    }
                    2 -> {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        web?.clearCache(true)
                        web?.clearHistory()
                        web?.reload()
                        Toast.makeText(this, "Вышли из аккаунта", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val hs = HashSet(prefs.getStringSet("hidden", HashSet()) ?: HashSet<String>())
                        hs.add(id)
                        prefs.edit().putStringSet("hidden", hs).apply()
                        renderRail()
                        highlight()
                        if (currentId == id) restoreLast()
                    }
                }
            }
            .show()
    }

    private fun updateClock() {
        val now = Date()
        timeTv.text = SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(now)
        val d = SimpleDateFormat("d MMMM, EEEE", Locale("ru", "RU")).format(now)
        dateTv.text = d.replaceFirstChar { ch -> ch.uppercase() }
    }

    private fun weatherEmoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67 -> "🌦️"
        in 71..77 -> "❄️"
        in 80..82 -> "🌧️"
        in 85..86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    private fun loadWeather() {
        Thread {
            var out = "⛅ —°"
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code"
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val text = c.inputStream.bufferedReader().readText()
                val cur = JSONObject(text).getJSONObject("current")
                val temp = cur.getDouble("temperature_2m")
                val code = cur.getInt("weather_code")
                val sign = if (temp > 0) "+" else ""
                out = weatherEmoji(code) + " " + sign + Math.round(temp) + "°"
            } catch (e: Exception) {
            }
            val result = out
            ui.post { weatherTv.text = result }
        }.start()
    }

    private fun geocode(q: String) {
        Toast.makeText(this, "Ищу город…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val url = URL(
                    "https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" +
                        URLEncoder.encode(q, "UTF-8")
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val text = c.inputStream.bufferedReader().readText()
                val arr = JSONObject(text).optJSONArray("results")
                if (arr != null && arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val la = o.getDouble("latitude")
                    val lo = o.getDouble("longitude")
                    val nm = o.optString("name", q)
                    prefs.edit()
                        .putFloat("lat", la.toFloat())
                        .putFloat("lon", lo.toFloat())
                        .putString("city", nm)
                        .apply()
                    lat = la
                    lon = lo
                    city = nm
                    ui.post {
                        loadWeather()
                        Toast.makeText(this, "Город: $nm", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    ui.post { Toast.makeText(this, "Город не найден", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "Нет связи с интернетом", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun showSettings() {
        val hidden: Set<String> = prefs.getStringSet("hidden", HashSet()) ?: HashSet()
        val items = arrayOf(
            if (isDark) "☀ Светлая тема" else "🌙 Тёмная тема",
            if (isCamo) "Обычный вид" else "Вид «FLOTJI-chat»",
            "Город для погоды: $city",
            if (voice != null && voice!!.isRunning) "🎤 Микрофон: выключить" else "🎤 Микрофон: включить",
            "Вернуть скрытые иконки (${hidden.size})",
            "Очистить все данные",
            "О приложении"
        )
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putString("theme", if (isDark) "light" else "dark").apply()
                        recreate()
                    }
                    1 -> {
                        prefs.edit().putBoolean("camo", !isCamo).apply()
                        recreate()
                    }
                    2 -> askCity()
                    3 -> toggleVoice()
                    4 -> {
                        prefs.edit().putStringSet("hidden", HashSet()).apply()
                        renderRail()
                        highlight()
                    }
                    5 -> clearAll()
                    6 -> about()
                }
            }
            .show()
    }

    private fun askCity() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.setText(city)
        et.setSelection(et.text.length)
        AlertDialog.Builder(this)
            .setTitle("Город для погоды")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty()) geocode(q)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toggleVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        if (voice == null) {
            voice = VoiceAssistant(
                this,
                onStatus = { s -> micBtn.text = s },
                onAnswer = { a -> showBubble(a) }
            )
        }
        voice?.toggle()
    }

    private fun showBubble(text: String) {
        bubble.text = text
        bubble.visibility = View.VISIBLE
        ui.removeCallbacks(hideBubble)
        ui.postDelayed(hideBubble, 8000)
    }

    private fun clearAll() {
        AlertDialog.Builder(this)
            .setTitle("Очистить все данные?")
            .setMessage("Будут удалены cookies, кэш сайтов и настройки приложения.")
            .setPositiveButton("Очистить") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                web?.clearCache(true)
                web?.clearHistory()
                prefs.edit().clear().apply()
                Toast.makeText(this, "Данные очищены", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("FLOTJI-chat 5.0")
            .setMessage(
                "Панель сервисов и голосовой помощник.\n\n" +
                    "Скажите «Флотжи, …» — например:\n" +
                    "• Флотжи, который час\n" +
                    "• Флотжи, включи фонарик\n" +
                    "• Флотжи, будильник на 7:30\n" +
                    "• Флотжи, открой ютуб\n\n" +
                    "Микрофон работает, пока приложение открыто."
            )
            .setPositiveButton("Ок", null)
            .show()
    }
}
