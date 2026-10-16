package com.example.virtualmouse

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.virtualmouse.KeyboardLayouts.Key

/** Системная клавиатура на всю ширину экрана. Текст вводится через InputConnection. */
class VirtualMouseIME : InputMethodService() {

    private companion object {
        val REPEAT = setOf("BKSP", "UP", "DOWN", "LEFT", "RIGHT")
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val TEXT = 0xFFE0F7FF.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var settings = CursorSettings()
    private lateinit var clip: AppClipboardManager
    private var root: View? = null
    private var rowsBox: LinearLayout? = null
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
        clip = AppClipboardManager(this)
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val v = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_VirtualMouse))
            .inflate(R.layout.ime_keyboard, null)
        root = v
        rowsBox = v.findViewById(R.id.kb_rows)
        v.findViewById<View>(R.id.kb_btn_lang).setOnClickListener { cycleLang() }
        v.findViewById<View>(R.id.kb_btn_clip).setOnClickListener { showClip() }
        v.findViewById<View>(R.id.kb_btn_settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        v.findViewById<View>(R.id.kb_btn_hide).setOnClickListener { requestHideSelf(0) }
        v.findViewById<View>(R.id.kb_clip_close).setOnClickListener { hideClip() }
        v.findViewById<View>(R.id.kb_clip_copy).setOnClickListener { clipCopy() }
        v.findViewById<View>(R.id.kb_clip_sys).setOnClickListener { clipSystem() }
        v.findViewById<View>(R.id.kb_clip_clear).setOnClickListener { clip.clear(); fillClip() }
        refreshAll()
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        settings = PreferenceHelper.load(this)
        ctrl = false; alt = false; win = false; shiftOnce = false
        if (!restarting) layer = KeyboardLayouts.L_LETTERS
        hideClip()
        if (root != null) refreshAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root != null) refreshAll()
    }

    override fun onDestroy() {
        stopRepeat()
        super.onDestroy()
    }

    private fun refreshAll() {
        applyColors()
        applySize()
        val lang = settings.language.coerceIn(0, 2)
        root?.findViewById<TextView>(R.id.kb_btn_lang)?.text = arrayOf("ENG", "РУС", "UKR")[lang]
        showLayer(layer)
    }

    private fun applyColors() {
        val v = root ?: return
        (ContextCompat.getDrawable(this, R.drawable.bg_kb_body)?.mutate() as? GradientDrawable)?.let {
            it.setStroke(dp(2), settings.padColor)
            v.background = it
        }
        (ContextCompat.getDrawable(this, R.drawable.bg_kb_panel)?.mutate() as? GradientDrawable)?.let {
            it.setStroke(dp(2), settings.padColor)
            v.findViewById<View>(R.id.kb_clip_panel).background = it
        }
    }

    /** Высота: 42% экрана в портрете, 55% в ландшафте, максимум 400dp; F-ряд скрыт при ширине < 600dp. */
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
        refreshAll()
    }

    // ---------- Построение слоёв ----------

    private fun showLayer(name: String) {
        val box = rowsBox ?: return
        layer = name
        stopRepeat()
        keyViews.clear()
        box.removeAllViews()
        val h = totalH - dp(44)
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

    private fun makeKey(k: Key): TextView {
        val base = (rowH / density / 2.6f).coerceIn(9f, 17f)
        val t = TextView(this)
        t.layoutParams = LinearLayout.LayoutParams(0, MATCH, k.weight).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }
        t.gravity = Gravity.CENTER
        t.includeFontPadding = false
        t.maxLines = 2
        t.textSize = if (k.label.length > 2) base * 0.75f else base
        if (k.code.isEmpty() && k.text.firstOrNull()?.isLetterOrDigit() == true) {
            t.setTextColor(settings.keyGlowColor)
            t.setShadowLayer(dp(4).toFloat(), 0f, 0f, settings.keyGlowColor)
        } else {
            t.setTextColor(TEXT)
        }
        t.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.6f
                    press(k)
                    if (k.code in REPEAT) startRepeat(k)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    stopRepeat()
                }
            }
            true
        }
        keyViews.add(t to k)
        return t
    }

    /** Emoji и Unicode: прокручиваемая сетка + нижний ряд. Вставка по клику, чтобы не мешать прокрутке. */
    private fun buildGrid(box: LinearLayout, name: String, h: Int) {
        rowH = h / 6
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sections = if (name == KeyboardLayouts.L_EMOJI) listOf("" to KeyboardLayouts.emojiKeys())
        else KeyboardLayouts.unicodeSections()
        for ((title, keys) in sections) {
            if (title.isNotEmpty()) {
                content.addView(TextView(this).apply {
                    text = title
                    textSize = 13f
                    setTextColor(0xFF00BFFF.toInt())
                    setPadding(dp(6), dp(6), 0, dp(2))
                })
            }
            val bidi = title == KeyboardLayouts.BIDI_TITLE
            val cols = (resources.displayMetrics.widthPixels / density / (if (bidi) 70 else 44)).toInt().coerceAtLeast(4)
            keys.chunked(cols).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { row.addView(cell(it, bidi)) }
                for (i in chunk.size until cols) row.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f))
                content.addView(row)
            }
        }
        box.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        box.addView(buildRow(KeyboardLayouts.bottomRow(name).keys), LinearLayout.LayoutParams(MATCH, rowH))
    }

    private fun cell(k: Key, small: Boolean) = TextView(this).apply {
        text = k.label
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        textSize = if (small) 12f else 22f
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        setOnClickListener { sound(""); commit(k.text) }
    }

    // ---------- Оформление клавиш ----------

    private fun isActive(k: Key) = when (k.code) {
        "CTRL" -> ctrl
        "ALT" -> alt
        "WIN" -> win
        "SHIFT" -> shiftOnce || caps
        "CAPS" -> caps
        else -> false
    }

    private fun keyBg(active: Boolean): Drawable {
        if (active) ContextCompat.getDrawable(this, R.drawable.bg_key_active)?.mutate()?.let { return it }
        return GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(ColorUtils.setAlphaComponent(settings.padColor, 0x22))
            setStroke(dp(1), ColorUtils.setAlphaComponent(settings.padColor, 0xAA))
        }
    }

    private fun outText(k: Key) =
        if (layer == KeyboardLayouts.L_LETTERS && k.code.isEmpty() && k.text == k.label)
            KeyboardLayouts.shifted(k.text, settings.language, shiftOnce, caps)
        else k.text

    private fun labelOf(k: Key, small: Boolean): CharSequence {
        if (k.code.isNotEmpty() || k.text != k.label) return k.label
        val main = outText(k)
        if (!small || k.latin.isEmpty() || k.latin == k.label) return main
        return SpannableString(main + "\n" + k.latin).apply {
            setSpan(RelativeSizeSpan(0.55f), main.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun refreshKeys() {
        val small = rowH >= dp(38) && settings.language != 0 && layer == KeyboardLayouts.L_LETTERS
        for ((v, k) in keyViews) {
            v.text = labelOf(k, small)
            v.background = keyBg(isActive(k))
        }
    }

    // ---------- Нажатия ----------

    private fun press(k: Key, snd: Boolean = true) {
        if (snd) sound(k.code)
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

    /** Ctrl+C/V/X/A/Z: KeyEvent с META_CTRL_ON, запасной вариант performContextMenuAction. */
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
        if (shiftOnce) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (win) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (caps) m = m or KeyEvent.META_CAPS_LOCK_ON
        return m
    }

    private fun sendKey(code: Int): Boolean {
        if (code == 0) return false
        val ic = currentInputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        val m = meta()
        val d = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, m))
        val u = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, m))
        return d && u
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

    private fun sound(code: String) {
        if (!settings.soundOn) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.playSoundEffect(
            when (code) {
                "SPACE" -> AudioManager.FX_KEYPRESS_SPACEBAR
                "ENTER" -> AudioManager.FX_KEYPRESS_RETURN
                "BKSP" -> AudioManager.FX_KEYPRESS_DELETE
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
        )
    }

    // ---------- Буфер обмена ----------

    private fun showClip() {
        root?.findViewById<View>(R.id.kb_clip_panel)?.visibility = View.VISIBLE
        fillClip()
    }

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
                setTextColor(0xFF8AA7B8.toInt())
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        for (item in items) {
            list.addView(TextView(this).apply {
                text = item.text.replace('\n', ' ').take(160)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(TEXT)
                textSize = 14f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = keyBg(false)
                layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                setOnClickListener { commit(item.text); hideClip() }
            })
        }
    }

    private fun clipCopy() {
        val sel = currentInputConnection?.getSelectedText(0)?.toString()
        if (sel.isNullOrEmpty()) toast("Нет выделенного текста") else { clip.add(sel); fillClip() }
    }

    private fun clipSystem() {
        val s = clip.readSystemClipboard()
        if (s.isNullOrBlank()) toast("Системный буфер пуст") else { clip.add(s, false); fillClip() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
