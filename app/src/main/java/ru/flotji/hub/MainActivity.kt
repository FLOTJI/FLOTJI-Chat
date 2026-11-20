package ru.flotji.hub

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class App(
        val id: String,
        val title: String,
        val short: String,
        val url: String,
        val color: Int,
        val iconRes: Int = 0
    )

    private val apps = listOf(
        App("tg", "Telegram", "TG", "https://web.telegram.org/k/", 0xFF2AABEE.toInt(), R.drawable.ic_tg),
        App("vk", "VK Мессенджер", "VK", "https://vk.com/im", 0xFF0077FF.toInt(), R.drawable.ic_vk),
        App("ym", "Яндекс Музыка", "YM", "https://music.yandex.ru", 0xFFFFCC00.toInt(), R.drawable.ic_ym),
        App("tt", "TikTok", "TT", "https://www.tiktok.com", 0xFFEE1D52.toInt(), R.drawable.ic_tt),
        App("gg", "Google", "G", "https://www.google.com/", 0xFF4285F4.toInt(), R.drawable.ic_google),
        App("ai", "MASS AI", "AI", "https://dsh.mass-ai.ru/", 0xFF6C5CE7.toInt(), R.drawable.ic_ai)
    )

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var rail: LinearLayout
    private lateinit var railScroll: ScrollView
    private lateinit var railWrapper: FrameLayout
    private lateinit var webFrame: FrameLayout
    private lateinit var statusBar: LinearLayout
    private lateinit var timeTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var weatherTv: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var voiceTv: TextView
    private lateinit var answerTv: TextView

    private lateinit var voice: VoiceAssistant
    private lateinit var commands: DeviceControl

    private val buttons = HashMap<String, View>()
    private var web: WebView? = null
    private var currentId: String? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var weatherCity = "Москва"
    private var weatherLat = 55.7558
    private var weatherLon = 37.6173
    private var vkFixed = false

    private val hideAnswer = Runnable { if (this::answerTv.isInitialized) answerTv.visibility = View.GONE }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            ui.postDelayed(this, 1000)
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            loadWeather()
            ui.postDelayed(this, 15 * 60 * 1000)
        }
    }

    // ---------- тема ----------

    private val isDark: Boolean
        get() = getSharedPreferences("fh", MODE_PRIVATE).getString("theme", "dark") == "dark"

    private val isCamo: Boolean
        get() = getSharedPreferences("fh", MODE_PRIVATE).getBoolean("camo", false)

    private val voiceOn: Boolean
        get() = getSharedPreferences("fh", MODE_PRIVATE).getBoolean("voiceOn", true)

    private val cBg: Int get() = if (isDark) 0xFF1E1F22.toInt() else 0xFFFFFFFF.toInt()
    private val cRail: Int get() = if (isDark) 0xFF2B2D31.toInt() else 0xFFF2F3F5.toInt()
    private val cText: Int get() = if (isDark) 0xFFDBDEE1.toInt() else 0xFF060607.toInt()
    private val cSub: Int get() = if (isDark) 0xFF949BA4.toInt() else 0xFF5C5E66.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
    )

    private fun rounded(fill: Int, radiusDp: Int, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(fill)
        if (strokePx > 0) d.setStroke(strokePx, strokeColor)
        return d
    }

    private fun onColor(c: Int): Int {
        val lum = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
        return if (lum > 165000) 0xFF1E1F22.toInt() else Color.WHITE
    }

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        commands = DeviceControl(this, { weatherTv.text.toString() }, { openHub(it) })
        voice = VoiceAssistant(this, ::onVoiceState, ::onVoiceAnswer, ::onVoiceCommand)
        voice.init()

        buildUi()
        restoreCity()
        renderRail()
        restoreLast()
        updateClock()
        ui.post(tick)
        ui.post(weatherTick)
    }

    override fun onResume() {
        super.onResume()
        web?.resumeTimers()
        updateClock()
        maybeStartVoice()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        web?.pauseTimers()
        if (this::voice.isInitialized) voice.stop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (this::voice.isInitialized) voice.shutdown()
        destroyWeb()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("deprecated in API 33")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 7001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                voice.start()
            } else {
                Toast.makeText(this, "Без доступа к микрофону FLOTJI не слышит", Toast.LENGTH_LONG).show()
                onVoiceState("Микрофон запрещён")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // ---------- интерфейс ----------

    private fun buildUi() {
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg)
        }

        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setBackgroundColor(cRail)
        }
        railScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(rail, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        railWrapper = FrameLayout(this).apply {
            addView(railScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        rootView.addView(railWrapper, LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
        }

        micBtn = TextView(this).apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15f))
            setPadding(dp(4), 0, dp(8), 0)
            setOnClickListener { toggleVoice() }
            setOnLongClickListener {
                askTextQuestion()
                true
            }
        }

        voiceTv = TextView(this).apply {
            setTextColor(cSub)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(11f))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        weatherTv = TextView(this).apply {
            setTextColor(cSub)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12f))
            text = "⛅ —°"
            setPadding(0, 0, dp(8), 0)
            setOnClickListener { showSettings() }
        }
        dateTv = TextView(this).apply {
            setTextColor(cSub)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12f))
            setPadding(0, 0, dp(8), 0)
        }
        timeTv = TextView(this).apply {
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12f))
            setTypeface(typeface, Typeface.BOLD)
        }

        statusBar.addView(micBtn)
        statusBar.addView(voiceTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusBar.addView(weatherTv)
        statusBar.addView(dateTv)
        statusBar.addView(timeTv)
        content.addView(statusBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))

        webFrame = FrameLayout(this)
        content.addView(webFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        answerTv = TextView(this).apply {
            setBackgroundColor(0xF0202124.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13f))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        val ap = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        ap.setMargins(dp(10), dp(10), dp(10), dp(10))
        webFrame.addView(answerTv, ap)

        rootView.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(rootView)
    }

    private fun renderRail() {
        rail.removeAllViews()
        buttons.clear()

        val hidden = getSharedPreferences("fh", MODE_PRIVATE).getStringSet("hidden", emptySet()) ?: emptySet()
        val camo = isCamo

        if (camo) {
            val head = TextView(this).apply {
                text = "FLOTJI\nCHAT"
                gravity = Gravity.CENTER
                setTextColor(cSub)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(9f))
                setTypeface(typeface, Typeface.BOLD)
            }
            rail.addView(head, LinearLayout.LayoutParams(dp(52), dp(36)).apply { topMargin = dp(2) })
        }

        for (a in apps) {
            if (hidden.contains(a.id)) continue
            val fill = if (camo) (if (isDark) 0xFF3B3D43.toInt() else 0xFFE6E8EB.toInt()) else a.color
            val txtColor = if (camo) cSub else onColor(a.color)
            val showIcon = !camo && a.iconRes != 0

            val tile = FrameLayout(this).apply {
                background = rounded(fill, if (camo) 10 else 16)
                isClickable = true
                isFocusable = true
                setOnClickListener { open(a) }
                setOnLongClickListener {
                    showAppMenu(a)
                    true
                }
            }

            if (showIcon) {
                val img = ImageView(this).apply {
                    setImageResource(a.iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                tile.addView(img, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            } else {
                val tv = TextView(this).apply {
                    text = a.short
                    gravity = Gravity.CENTER
                    setTextColor(txtColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13f))
                    setTypeface(typeface, Typeface.BOLD)
                }
                tile.addView(tv, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
            }

            val lp = LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(8) }
            rail.addView(tile, lp)
            buttons[a.id] = tile
        }

        val collapse = TextView(this).apply {
            text = "«"
            gravity = Gravity.CENTER
            setTextColor(cSub)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(18f))
            setOnClickListener { toggleRail() }
        }
        rail.addView(collapse, LinearLayout.LayoutParams(dp(52), dp(40)).apply { topMargin = dp(14) })

        settingsBtn = TextView(this).apply {
            text = "⚙"
            gravity = Gravity.CENTER
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(20f))
            setOnClickListener { showSettings() }
        }
        rail.addView(settingsBtn, LinearLayout.LayoutParams(dp(52), dp(44)).apply { topMargin = dp(6) })
    }

    private fun highlight(id: String?) {
        val camo = isCamo
        for ((key, view) in buttons) {
            val app = apps.firstOrNull { it.id == key } ?: continue
            val fill = if (camo) (if (isDark) 0xFF3B3D43.toInt() else 0xFFE6E8EB.toInt()) else app.color
            val r = if (camo) 10 else 16
            view.background = if (key == id)
                rounded(fill, r, dp(2), Color.WHITE)
            else
                rounded(fill, r)
        }
    }

    private fun toggleRail() {
        val visible = railWrapper.visibility == View.VISIBLE
        railWrapper.visibility = if (visible) View.GONE else View.VISIBLE
        getSharedPreferences("fh", MODE_PRIVATE).edit().putBoolean("railHidden", visible).apply()
    }

    // ---------- WebView ----------

    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        @Suppress("DEPRECATION")
        s.allowFileAccess = false
        @Suppress("DEPRECATION")
        s.allowContentAccess = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = s.userAgentString.replace("; wv", "")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return openExternallyIfNeeded(url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                fixVkLanding(view, url)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback = callback
                return try {
                    val i = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, 4501)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }
        w.setDownloadListener { url, _, _, _, _ -> openUrl(url) }
    }

    private fun fixVkLanding(view: WebView, url: String) {
        if (currentId != "vk" || vkFixed) return
        val u = url.lowercase(Locale.ROOT)
        if (!u.contains("vk.com")) return
        val wrongPage = u.contains("/feed") || u.contains("/club") ||
            u.contains("/public") || u.contains("/group") ||
            u.contains("/wall") || u.contains("/away")
        if (wrongPage) {
            vkFixed = true
            view.loadUrl("https://vk.com/im")
        }
    }

    private fun openExternallyIfNeeded(url: String): Boolean {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            false
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun open(app: App) {
        if (currentId == app.id && web != null) {
            highlight(app.id)
            return
        }
        destroyWeb()
        vkFixed = false
        val w = WebView(this)
        configure(w)
        webFrame.addView(
            w, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        if (this::answerTv.isInitialized) webFrame.bringChildToFront(answerTv)
        w.loadUrl(app.url)
        web = w
        currentId = app.id
        getSharedPreferences("fh", MODE_PRIVATE).edit().putString("last", app.id).apply()
        highlight(app.id)
    }

    private fun destroyWeb() {
        val w = web ?: return
        try {
            webFrame.removeView(w)
            w.stopLoading()
            w.loadUrl("about:blank")
            w.removeAllViews()
            w.destroy()
        } catch (e: Exception) {
            // ignore
        }
        web = null
        currentId = null
    }

    private fun restoreLast() {
        val last = getSharedPreferences("fh", MODE_PRIVATE).getString("last", "tg")
        val app = apps.firstOrNull { it.id == last } ?: apps.first()
        open(app)
        if (getSharedPreferences("fh", MODE_PRIVATE).getBoolean("railHidden", false)) {
            railWrapper.visibility = View.GONE
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 4501) {
            var results: Array<Uri>? = null
            if (resultCode == RESULT_OK && data?.data != null) {
                results = arrayOf(data.data!!)
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ---------- голосовой помощник ----------

    private fun maybeStartVoice() {
        if (!this::voice.isInitialized) return
        if (!voiceOn) {
            onVoiceState("Микрофон выключен")
            micBtn.text = "🔇"
            return
        }
        micBtn.text = "🎤"
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7001)
            return
        }
        voice.start()
    }

    private fun toggleVoice() {
        val p = getSharedPreferences("fh", MODE_PRIVATE)
        if (voiceOn) {
            p.edit().putBoolean("voiceOn", false).apply()
            voice.stop()
            micBtn.text = "🔇"
            onVoiceState("Микрофон выключен")
        } else {
            p.edit().putBoolean("voiceOn", true).apply()
            micBtn.text = "🎤"
            maybeStartVoice()
        }
    }

    private fun onVoiceState(s: String) {
        if (this::voiceTv.isInitialized) voiceTv.text = s
    }

    private fun onVoiceAnswer(text: String) {
        showAnswer("FLOTJI: $text")
    }

    private fun onVoiceCommand(cmd: String): String? {
        return try {
            commands.handle(cmd)
        } catch (e: Exception) {
            null
        }
    }

    private fun showAnswer(text: String) {
        if (!this::answerTv.isInitialized) return
        answerTv.text = text
        answerTv.visibility = View.VISIBLE
        ui.removeCallbacks(hideAnswer)
        ui.postDelayed(hideAnswer, 15000)
    }

    private fun askTextQuestion() {
        val input = EditText(this).apply {
            hint = "Спросите FLOTJI…"
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("FLOTJI")
            .setView(input)
            .setPositiveButton("Спросить") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) runQuestion(q)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun runQuestion(q: String) {
        val local = onVoiceCommand(q)
        if (local != null) {
            showAnswer("FLOTJI: $local")
            voice.speak(local)
            return
        }
        showAnswer("FLOTJI думает…")
        io.execute {
            val a = try {
                AiClient.ask(q)
            } catch (e: Exception) {
                ""
            }
            ui.post {
                if (a.isBlank()) {
                    showAnswer("FLOTJI: не смог получить ответ, проверьте интернет.")
                } else {
                    showAnswer("FLOTJI: $a")
                    voice.speak(a)
                }
            }
        }
    }

    /** Открывает приложения внутри хаба по названию. */
    private fun openHub(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT).trim()
        val id = when {
            n.contains("телеграм") || n.contains("тг") || n.contains("телега") -> "tg"
            n.contains("вк") || n.contains("вконтакте") -> "vk"
            n.contains("музык") || n.contains("яндекс") -> "ym"
            n.contains("тик") -> "tt"
            n.contains("гугл") || n.contains("google") -> "gg"
            n.contains("масс") || n.contains("mass") || n.contains("ии") -> "ai"
            else -> return false
        }
        val app = apps.firstOrNull { it.id == id } ?: return false
        open(app)
        return true
    }

    // ---------- меню приложения ----------

    private fun showAppMenu(app: App) {
        val items = arrayOf(
            "Обновить",
            "Открыть в браузере",
            "Выйти из аккаунта",
            "Скрыть иконку"
        )
        AlertDialog.Builder(this)
            .setTitle(app.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> web?.reload()
                    1 -> openUrl(app.url)
                    2 -> clearAppData(app)
                    3 -> hideApp(app)
                }
            }
            .show()
    }

    private fun clearAppData(app: App) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.clearHistory()
        web?.clearFormData()
        destroyWeb()
        open(app)
        Toast.makeText(this, "Данные ${app.title} очищены", Toast.LENGTH_SHORT).show()
    }

    private fun hideApp(app: App) {
        val p = getSharedPreferences("fh", MODE_PRIVATE)
        val set = HashSet(p.getStringSet("hidden", emptySet()) ?: emptySet())
        set.add(app.id)
        p.edit().putStringSet("hidden", set).apply()
        if (currentId == app.id) destroyWeb()
        renderRail()
        highlight(currentId)
        Toast.makeText(this, "Иконка скрыта — вернуть можно в настройках", Toast.LENGTH_SHORT).show()
        if (currentId == null) restoreLast()
    }

    // ---------- настройки ----------

    private fun showSettings() {
        val items = arrayOf(
            "Город для погоды",
            if (isDark) "Светлая тема" else "Тёмная тема",
            if (isCamo) "Обычный вид" else "Вид «FLOTJI-chat»",
            "Голосовой помощник",
            "Вернуть скрытые иконки",
            "Очистить все данные",
            "О приложении"
        )
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askCity()
                    1 -> toggleTheme()
                    2 -> toggleCamo()
                    3 -> showVoiceSettings()
                    4 -> showHiddenApps()
                    5 -> clearAllData()
                    6 -> about()
                }
            }
            .show()
    }

    private fun showVoiceSettings() {
        val items = arrayOf(
            if (voiceOn) "Выключить микрофон" else "Включить микрофон",
            "Проверить голос",
            "Что умеет",
            "Задать вопрос текстом"
        )
        AlertDialog.Builder(this)
            .setTitle("Голосовой помощник FLOTJI")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleVoice()
                    1 -> voice.test()
                    2 -> showAnswer(VOICE_HELP)
                    3 -> askTextQuestion()
                }
            }
            .show()
    }

    private fun toggleCamo() {
        getSharedPreferences("fh", MODE_PRIVATE).edit().putBoolean("camo", !isCamo).apply()
        renderRail()
        highlight(currentId)
    }

    private fun toggleTheme() {
        getSharedPreferences("fh", MODE_PRIVATE)
            .edit().putString("theme", if (isDark) "light" else "dark").apply()
        recreate()
    }

    private fun showHiddenApps() {
        val p = getSharedPreferences("fh", MODE_PRIVATE)
        val hidden = p.getStringSet("hidden", emptySet()) ?: emptySet()
        if (hidden.isEmpty()) {
            Toast.makeText(this, "Скрытых иконок нет", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = hidden.map { id -> apps.firstOrNull { it.id == id }?.title ?: id }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Скрытые приложения")
            .setItems(titles) { _, which ->
                val id = hidden.toList()[which]
                val set = HashSet(hidden)
                set.remove(id)
                p.edit().putStringSet("hidden", set).apply()
                renderRail()
                highlight(currentId)
            }
            .show()
    }

    private fun clearAllData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        destroyWeb()
        Toast.makeText(this, "Все данные приложений очищены", Toast.LENGTH_SHORT).show()
        restoreLast()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("FLOTJI-chat 2.2")
            .setMessage(
                "Панель приложений с настоящими веб-версиями сервисов.\n\n" +
                    "Вход в аккаунт сохраняется между запусками.\n\n" +
                    "Голосовой помощник FLOTJI: скажите «Флотжи» и команду. " +
                    "Микрофон — кнопка 🎤 вверху, долгое нажатие — вопрос текстом.\n\n" +
                    "Долгое нажатие на иконку — меню приложения.\n\n" +
                    "Настройки (⚙) — город, тема, голос, скрытые иконки, очистка данных."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun askCity() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Например: Казань"
            setText(weatherCity)
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Город для погоды")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) geocode(name)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Нет приложения для ссылки", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- время и погода ----------

    private fun updateClock() {
        val now = Date()
        timeTv.text = SimpleDateFormat("HH:mm", Locale("ru")).format(now)
        val raw = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now)
        dateTv.text = raw.replaceFirstChar { it.uppercase(Locale("ru")) }
    }

    private fun loadWeather() {
        val lat = weatherLat
        val lon = weatherLon
        io.execute {
            val text = try {
                httpGet(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code&timezone=auto"
                )
            } catch (e: Exception) {
                null
            }
            var result = "⛅ —°"
            if (text != null) {
                try {
                    val cur = JSONObject(text).getJSONObject("current")
                    val t = Math.round(cur.getDouble("temperature_2m"))
                    val code = cur.getInt("weather_code")
                    result = emojiFor(code) + " " + (if (t > 0) "+$t" else "$t") + "°"
                } catch (e: Exception) {
                    result = "⛅ —°"
                }
            }
            val shown = result
            ui.post { weatherTv.text = shown }
        }
    }

    private fun geocode(name: String) {
        io.execute {
            val q = try {
                URLEncoder.encode(name, "UTF-8")
            } catch (e: Exception) {
                name
            }
            val text = try {
                httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=ru&format=json")
            } catch (e: Exception) {
                null
            }
            var ok = false
            if (text != null) {
                try {
                    val arr = JSONObject(text).optJSONArray("results")
                    if (arr != null && arr.length() > 0) {
                        val o = arr.getJSONObject(0)
                        weatherCity = o.optString("name", name)
                        weatherLat = o.getDouble("latitude")
                        weatherLon = o.getDouble("longitude")
                        getSharedPreferences("fh", MODE_PRIVATE).edit()
                            .putString("city", weatherCity)
                            .putString("lat", weatherLat.toString())
                            .putString("lon", weatherLon.toString())
                            .apply()
                        ok = true
                    }
                } catch (e: Exception) {
                    ok = false
                }
            }
            ui.post {
                if (ok) {
                    Toast.makeText(this, "Город: $weatherCity", Toast.LENGTH_SHORT).show()
                    loadWeather()
                } else {
                    Toast.makeText(this, "Город не найден", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun restoreCity() {
        val p = getSharedPreferences("fh", MODE_PRIVATE)
        weatherCity = p.getString("city", "Москва") ?: "Москва"
        weatherLat = (p.getString("lat", "55.7558") ?: "55.7558").toDoubleOrNull() ?: 55.7558
        weatherLon = (p.getString("lon", "37.6173") ?: "37.6173").toDoubleOrNull() ?: 37.6173
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun emojiFor(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "⛅"
    }

    companion object {
        private const val VOICE_HELP =
            "ФЛОТДЖИ умеет: фонарик, открыть любое приложение, будильник, таймер, звонок, " +
                "громкость, музыка, Wi-Fi, настройки, камера, время, дата, погода. " +
                "А на любые другие вопросы отвечает сам."
    }
}
