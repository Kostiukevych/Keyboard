package com.example.virtualmouse

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.virtualmouse.KeyboardLayouts.Key

/**
 * Системная клавиатура Virtual Mouse с 3D-клавишами, стеклянными переключателями,
 * неоновой подсветкой и звуковым движком Web Audio API.
 */
class VirtualMouseIME : InputMethodService() {

    companion object {
        @Volatile
        var instance: VirtualMouseIME? = null
        private val REPEAT = setOf("BKSP", "UP", "DOWN", "LEFT", "RIGHT")
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private var sysClipListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var settings = CursorSettings()
    private lateinit var clip: AppClipboardManager
    private lateinit var sounds: SoundManager

    private var root: View? = null
    private var rowsBox: LinearLayout? = null
    private var web: WebView? = null
    private var builtStyle = 0
    private val keyViews = ArrayList<Pair<TextView, Key>>()
    private var layer = KeyboardLayouts.L_LETTERS
    private var ctrl = false
    private var alt = false
    private var win = false
    private var shiftOnce = false
    private var caps = false
    private var lastShift = 0L
    private var totalH = 0
    private var rowH = 0
    private var narrow = false
    private var repeatRun: Runnable? = null

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // ---------- Жизненный цикл ----------

    override fun onCreate() {
        super.onCreate()
        settings = PreferenceHelper.load(this)
        clip = AppClipboardManager.get(this)
        sounds = SoundManager.get(this) { settings.soundOn }
        instance = this
        clip.onChanged = { handler.post { if (clipPanelShown()) fillClip() } }
        // Всё, что копируется в системе (Ctrl+C и т.п.), попадает и в буфер приложения
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val l = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                try {
                    val c = cm.primaryClip
                    val t = if (c != null && c.itemCount > 0) c.getItemAt(0)?.coerceToText(this)?.toString() else null
                    if (!t.isNullOrBlank()) clip.add(t)
                } catch (_: Exception) {
                }
            }
            cm.addPrimaryClipChangedListener(l)
            sysClipListener = l
        } catch (_: Exception) {
        }
    }

    /** Ctrl+<клавиша> (с Shift по желанию) в текущее поле ввода: используется тачпадом для отмены/повтора. */
    fun ctrlKey(code: Int, shift: Boolean) {
        val ic = currentInputConnection ?: run { toast("Нет активного поля ввода"); return }
        var m = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, m))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, m))
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        settings = PreferenceHelper.load(this)
        builtStyle = settings.kbStyle
        return if (builtStyle == 1) createNeonView() else createClassicView()
    }

    private fun bindClipButtons(v: View) {
        v.findViewById<View>(R.id.kb_clip_close).setOnClickListener {
            sounds.beep(180)
            hideClip()
        }
        v.findViewById<View>(R.id.kb_clip_clear).setOnClickListener {
            sounds.beep(180)
            clip.clear()
            fillClip()
        }
    }

    // ---------- Клавиатура Neon Keys (HTML в WebView) ----------

    private inner class NeonBridge {
        @JavascriptInterface fun onKey(json: String) { handler.post { neonKey(json) } }
        @JavascriptInterface fun onSize(hCss: Int) { handler.post { neonResize(hCss) } }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createNeonView(): View {
        val v = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_VirtualMouse))
            .inflate(R.layout.ime_neon, null)
        root = v
        rowsBox = null
        bindClipButtons(v)
        web?.destroy()
        val w = v.findViewById<WebView>(R.id.kb_web)
        web = w
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.isLongClickable = false
        w.setOnLongClickListener { true }
        w.addJavascriptInterface(NeonBridge(), "Android")
        w.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript("setLang(${settings.language.coerceIn(0, 2)})", null)
            }
        }
        w.loadUrl("file:///android_asset/keyboard_neon.html")
        return v
    }

    private fun neonResize(hCss: Int) {
        val w = web ?: return
        val h = (hCss * density).toInt().coerceAtLeast(dp(120))
        val lp = w.layoutParams
        if (lp != null && lp.height != h) {
            lp.height = h
            w.layoutParams = lp
        }
    }

    private fun neonKey(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val code = o.optString("code")
        when (code) {
            "SETTINGS" -> {
                sounds.beep(340)
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            "HIDE" -> { sounds.beep(180); requestHideSelf(0); return }
            "LANG" -> {
                val n = o.optInt("lang", 1).coerceIn(0, 2)
                val st = PreferenceHelper.load(this)
                st.language = n
                PreferenceHelper.save(this, st)
                settings.language = n
                sounds.beep(if (n == 1) 380 else 520)
                return
            }
        }
        ctrl = o.optBoolean("ctrl"); alt = o.optBoolean("alt"); win = o.optBoolean("win")
        shiftOnce = o.optBoolean("shift"); caps = o.optBoolean("caps")
        sounds.beep(260)
        try {
            when (code) {
                "" -> {
                    val t = o.optString("text")
                    if (t.isNotEmpty()) neonText(t, o.optString("latin"))
                }
                "SPACE" -> if (ctrl || alt || win) sendKey(KeyEvent.KEYCODE_SPACE) else commit(" ")
                "BKSP" -> backspace()
                "CLIP" -> showClip()
                else -> sendKey(codeOf(code))
            }
        } finally {
            ctrl = false; alt = false; win = false; shiftOnce = false; caps = false
        }
    }

    private fun neonText(t: String, latin: String) {
        if (ctrl || alt || win) {
            val ch = (if (latin.isNotEmpty()) latin else t)[0]
            val code = charCode(ch)
            if (code != 0) {
                if (ctrl && !alt && !win && ch.lowercaseChar() in "cvxaz") shortcut(ch.lowercaseChar(), code)
                else sendKey(code)
                return
            }
        }
        commit(t)
    }

    // ---------- Классическая клавиатура ----------

    private fun createClassicView(): View {
        web?.let { try { it.destroy() } catch (_: Exception) {} }
        web = null
        val v = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_VirtualMouse))
            .inflate(R.layout.ime_keyboard, null)
        root = v
        rowsBox = v.findViewById(R.id.kb_rows)
        v.findViewById<View>(R.id.kb_btn_lang).setOnClickListener {
            cycleLang()
        }
        v.findViewById<View>(R.id.kb_btn_clip).setOnClickListener {
            sounds.beep(300)
            showClip()
        }
        v.findViewById<View>(R.id.kb_btn_settings).setOnClickListener {
            sounds.beep(340)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        v.findViewById<View>(R.id.kb_btn_hide).setOnClickListener {
            sounds.beep(180)
            requestHideSelf(0)
        }
        bindClipButtons(v)
        refreshAll()
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        settings = PreferenceHelper.load(this)
        ctrl = false; alt = false; win = false; shiftOnce = false
        if (!restarting) layer = KeyboardLayouts.L_LETTERS
        if (root != null && settings.kbStyle != builtStyle) {
            // Пользователь сменил стиль клавиатуры в настройках: пересобираем панель
            setInputView(onCreateInputView())
        }
        hideClip()
        if (rowsBox != null) refreshAll()
        web?.evaluateJavascript("setLang(${settings.language.coerceIn(0, 2)})", null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (rowsBox != null) refreshAll()
    }

    override fun onDestroy() {
        stopRepeat()
        if (instance === this) instance = null
        web?.let { try { it.destroy() } catch (_: Exception) {} }
        web = null
        clip.onChanged = null
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            sysClipListener?.let { cm.removePrimaryClipChangedListener(it) }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun refreshAll() {
        applyColors()
        applySize()
        val lang = settings.language.coerceIn(0, 2)
        val langNames = arrayOf("ENG", "РУС", "UKR")
        root?.findViewById<TextView>(R.id.kb_btn_lang)?.text = langNames[lang]
        showLayer(layer)
    }

    private fun applyColors() {
        val v = root ?: return
        (ContextCompat.getDrawable(this, R.drawable.bg_card_platform)?.mutate() as? GradientDrawable)?.let {
            it.setStroke(dp(1), settings.padColor)
            v.background = it
        }
    }

    private fun applySize() {
        val v = root ?: return
        val dm = resources.displayMetrics
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        totalH = minOf((dm.heightPixels * (if (land) 0.55f else 0.42f)).toInt(), dp(400))
        narrow = dm.widthPixels / dm.density < 600f
        val main = v.findViewById<View>(R.id.kb_main)
        val lp = main.layoutParams
        lp.height = totalH
        main.layoutParams = lp
        v.minimumHeight = totalH
        v.findViewById<View>(R.id.kb_btn_clip).visibility = if (narrow) View.VISIBLE else View.GONE
    }

    private fun cycleLang() {
        val s = PreferenceHelper.load(this)
        s.language = (settings.language + 1) % 3
        PreferenceHelper.save(this, s)
        settings.language = s.language
        sounds.beep(if (s.language == 1) 380 else 520)
        refreshAll()
    }

    // ---------- Построение слоёв ----------

    private fun showLayer(name: String) {
        val box = rowsBox ?: return
        layer = name
        stopRepeat()
        keyViews.clear()
        box.removeAllViews()
        val h = totalH - dp(46)
        if (name == KeyboardLayouts.L_EMOJI || name == KeyboardLayouts.L_UNICODE) {
            buildGrid(box, name, h)
        } else {
            val rows = KeyboardLayouts.rows(name, settings.language).filter { !(it.fRow && narrow) }
            rowH = h / rows.size
            rows.forEach { box.addView(buildRow(it.keys), LinearLayout.LayoutParams(MATCH, 0, 1f)) }
        }
        refreshKeys()
    }

    private fun buildRow(keys: List<Key>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.forEach { row.addView(makeKey(it)) }
        return row
    }

    private fun isGlassKey(k: Key): Boolean {
        return k.code.startsWith("L:") || k.code in setOf(
            "ESC", "TAB", "CAPS", "ENTER", "SPACE", "CLIP", "WIN", "CTRL", "ALT", "SHIFT"
        )
    }

    private fun makeKey(k: Key): TextView {
        val base = (rowH / density / 2.6f).coerceIn(9f, 16f)
        val t = TextView(this)
        t.layoutParams = LinearLayout.LayoutParams(0, MATCH, k.weight).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        }
        t.gravity = Gravity.CENTER
        t.includeFontPadding = false
        t.maxLines = 2
        t.textSize = if (k.label.length > 2) base * 0.75f else base

        val isGlass = isGlassKey(k)
        t.background = ContextCompat.getDrawable(
            this,
            if (isGlass) R.drawable.bg_glass_button else R.drawable.bg_3d_key
        )

        val glowSoft = ColorUtils.blendARGB(settings.keyGlowColor, Color.WHITE, 0.55f)
        if (k.code.isEmpty() && k.text.firstOrNull()?.isLetterOrDigit() == true) {
            t.setTextColor(glowSoft)
            t.setShadowLayer(dp(5).toFloat(), 0f, 0f, settings.keyGlowColor)
        } else {
            t.setTextColor(Color.WHITE)
        }

        t.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    press(k)
                    if (k.code in REPEAT) startRepeat(k)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopRepeat()
                }
            }
            true
        }
        keyViews.add(t to k)
        return t
    }

    private fun buildGrid(box: LinearLayout, name: String, h: Int) {
        rowH = h / 6
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sections = if (name == KeyboardLayouts.L_EMOJI) listOf("" to KeyboardLayouts.emojiKeys())
        else KeyboardLayouts.unicodeSections()
        for ((title, keys) in sections) {
            if (title.isNotEmpty()) {
                content.addView(TextView(this).apply {
                    text = title
                    textSize = 12f
                    setTextColor(ColorUtils.blendARGB(settings.keyGlowColor, Color.WHITE, 0.4f))
                    setPadding(dp(8), dp(6), 0, dp(4))
                })
            }
            val bidi = title == KeyboardLayouts.BIDI_TITLE
            val cols = (resources.displayMetrics.widthPixels / density / (if (bidi) 64 else 42)).toInt().coerceAtLeast(4)
            keys.chunked(cols).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { row.addView(cell(it, bidi)) }
                for (i in chunk.size until cols) row.addView(View(this), LinearLayout.LayoutParams(0, dp(42), 1f))
                content.addView(row)
            }
        }
        box.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        box.addView(buildRow(KeyboardLayouts.bottomRow(name).keys), LinearLayout.LayoutParams(MATCH, rowH))
    }

    private fun cell(k: Key, small: Boolean) = TextView(this).apply {
        text = k.label
        gravity = Gravity.CENTER
        setTextColor(ColorUtils.blendARGB(settings.keyGlowColor, Color.WHITE, 0.6f))
        textSize = if (small) 12f else 22f
        background = ContextCompat.getDrawable(
            this@VirtualMouseIME,
            if (small) R.drawable.bg_glass_button else R.drawable.bg_3d_key
        )
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        }
        setOnClickListener {
            sounds.beep(if (small) 500 else 420)
            commit(k.text)
        }
    }

    private fun isActive(k: Key) = when (k.code) {
        "CTRL" -> ctrl
        "ALT" -> alt
        "WIN" -> win
        "SHIFT" -> shiftOnce || caps
        "CAPS" -> caps
        else -> false
    }

    private fun outText(k: Key) =
        if (layer == KeyboardLayouts.L_LETTERS && k.code.isEmpty() && k.text == k.label)
            KeyboardLayouts.shifted(k.text, settings.language, shiftOnce, caps)
        else k.text

    private fun labelOf(k: Key, small: Boolean): CharSequence {
        if (k.code.isNotEmpty() || k.text != k.label) return k.label
        val main = outText(k)
        if (!small || k.latin.isEmpty() || k.latin == k.label) return main
        val ss = SpannableString(main + "\n" + k.latin.uppercase())
        ss.setSpan(RelativeSizeSpan(0.6f), main.length + 1, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ss.setSpan(ForegroundColorSpan(Color.parseColor("#7A8C9E")), main.length + 1, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    private fun refreshKeys() {
        val small = rowH >= dp(38) && settings.language != 0 && layer == KeyboardLayouts.L_LETTERS
        for ((v, k) in keyViews) {
            v.text = labelOf(k, small)
            v.isSelected = isActive(k)
        }
    }

    // ---------- Нажатия ----------

    private fun press(k: Key, snd: Boolean = true) {
        if (snd) playSoundForKey(k)
        when (k.code) {
            "" -> typeText(k)
            "SHIFT" -> toggleShift()
            "CAPS" -> { caps = !caps; shiftOnce = false; refreshKeys() }
            "CTRL" -> { ctrl = !ctrl; refreshKeys() }
            "ALT" -> { alt = !alt; refreshKeys() }
            "WIN" -> { win = !win; refreshKeys() }
            "BKSP" -> { backspace(); afterKey() }
            "SPACE" -> {
                if (ctrl || alt || win) sendKey(KeyEvent.KEYCODE_SPACE) else commit(" ")
                afterKey()
            }
            "CLIP" -> handler.post { showClip() }
            else -> if (k.code.startsWith("L:")) {
                val n = k.code.substring(2)
                handler.post { showLayer(n) }
            } else {
                sendKey(codeOf(k.code))
                afterKey()
            }
        }
    }

    private fun playSoundForKey(k: Key) {
        when {
            k.code.startsWith("L:emoji") -> sounds.beep(420)
            k.code.startsWith("L:unicode") -> sounds.beep(500)
            k.code.startsWith("L:") -> sounds.beep(340)
            k.code in setOf("CTRL", "ALT", "SHIFT", "WIN", "CAPS") -> sounds.beep(220)
            k.code == "BKSP" -> sounds.beep(200)
            k.code == "SPACE" -> sounds.beep(240)
            k.code == "ENTER" -> sounds.beep(300)
            k.code == "CLIP" -> sounds.beep(300)
            else -> sounds.beep(260)
        }
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        if (shiftOnce && now - lastShift < 350) { caps = true; shiftOnce = false }
        else if (caps) { caps = false; shiftOnce = false }
        else shiftOnce = !shiftOnce
        lastShift = now
        refreshKeys()
    }

    private fun afterKey() {
        if (ctrl || alt || win || shiftOnce) {
            ctrl = false; alt = false; win = false; shiftOnce = false
            refreshKeys()
        }
    }

    private fun typeText(k: Key) {
        val t = outText(k)
        if (ctrl || alt || win) {
            val ch = (if (k.latin.isNotEmpty()) k.latin else t)[0]
            val code = charCode(ch)
            if (code != 0) {
                if (ctrl && !alt && !win && ch.lowercaseChar() in "cvxaz") shortcut(ch.lowercaseChar(), code)
                else sendKey(code)
                afterKey()
                return
            }
        }
        commit(t)
        afterKey()
    }

    private fun commit(s: String) {
        try {
            currentInputConnection?.commitText(s, 1)
        } catch (_: Exception) {
        }
    }

    private fun shortcut(ch: Char, code: Int) {
        if (sendKey(code)) return
        val id = when (ch) {
            'c' -> android.R.id.copy
            'v' -> android.R.id.paste
            'x' -> android.R.id.cut
            'a' -> android.R.id.selectAll
            else -> android.R.id.undo
        }
        currentInputConnection?.performContextMenuAction(id)
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (ctrl || alt || win) { sendKey(KeyEvent.KEYCODE_DEL); return }
        if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(2, 0)
        if (before.isNullOrEmpty()) { sendKey(KeyEvent.KEYCODE_DEL); return }
        val pair = before.length == 2 && Character.isHighSurrogate(before[0]) && Character.isLowSurrogate(before[1])
        ic.deleteSurroundingText(if (pair) 2 else 1, 0)
    }

    private fun meta(): Int {
        var m = 0
        if (ctrl) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shiftOnce || caps) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return m
    }

    private fun sendKey(code: Int): Boolean {
        if (code == 0) return false
        val ic = currentInputConnection ?: return false
        val m = meta()
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, m))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, m))
        return true
    }

    private fun codeOf(c: String): Int = when (c) {
        "ESC" -> KeyEvent.KEYCODE_ESCAPE
        "TAB" -> KeyEvent.KEYCODE_TAB
        "ENTER" -> KeyEvent.KEYCODE_ENTER
        "UP" -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "HOME" -> KeyEvent.KEYCODE_MOVE_HOME
        "END" -> KeyEvent.KEYCODE_MOVE_END
        "PGUP" -> KeyEvent.KEYCODE_PAGE_UP
        "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN
        "DEL" -> KeyEvent.KEYCODE_FORWARD_DEL
        else -> {
            val n = if (c.startsWith("F")) c.substring(1).toIntOrNull() else null
            if (n != null && n in 1..12) KeyEvent.KEYCODE_F1 + n - 1 else 0
        }
    }

    private fun charCode(ch: Char): Int {
        val c = ch.lowercaseChar()
        return when (c) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '/' -> KeyEvent.KEYCODE_SLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '`' -> KeyEvent.KEYCODE_GRAVE
            else -> 0
        }
    }

    private fun startRepeat(k: Key) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                press(k, false)
                handler.postDelayed(this, 50)
            }
        }
        repeatRun = r
        handler.postDelayed(r, 400)
    }

    private fun stopRepeat() {
        repeatRun?.let { handler.removeCallbacks(it) }
        repeatRun = null
    }

    // ---------- Буфер обмена ----------

    private fun showClip() {
        root?.findViewById<View>(R.id.kb_clip_panel)?.visibility = View.VISIBLE
        fillClip()
    }

    private fun clipPanelShown(): Boolean =
        root?.findViewById<View>(R.id.kb_clip_panel)?.visibility == View.VISIBLE

    private fun hideClip() {
        root?.findViewById<View>(R.id.kb_clip_panel)?.visibility = View.GONE
    }

    private fun fillClip() {
        val v = root ?: return
        v.findViewById<TextView>(R.id.kb_clip_title).text =
            "📋 Буфер: ${clip.sizeCount()} шт., ${clip.sizeBytes() / 1024} КБ"
        val list = v.findViewById<LinearLayout>(R.id.kb_clip_list)
        list.removeAllViews()
        val items = clip.getHistory()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "История пуста"
                setTextColor(Color.parseColor("#7A8C9E"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        for (item in items) {
            list.addView(TextView(this).apply {
                text = item.text.replace('\n', ' ').take(160)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = ContextCompat.getDrawable(this@VirtualMouseIME, R.drawable.bg_glass_button)
                layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                setOnClickListener { commit(item.text); hideClip() }
            })
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
