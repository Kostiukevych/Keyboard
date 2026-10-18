package com.example.virtualmouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.util.SparseArray
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ======================================================================
//  Данные раскладок и скинов (1-в-1 как в keyboard_neon.html)
// ======================================================================

private class NK(
    val label: String,
    val code: String = "",
    val w: Float = 1f,
    val latin: String = "",
    val text: String? = null
)

private class NLayer(
    val rows: List<List<NK>>?,
    val emoji: Boolean = false,
    val sections: List<Pair<String, List<NK>>> = emptyList(),
    val bottom: List<NK> = emptyList()
)

private class NSkin(val name: String, val hue: Float, val focusY: Float, val dim: Float, val img: String?)

private object NeonData {
    val LANGS = arrayOf("EN", "РУС", "УКР")

    val SKINS = listOf(
        NSkin("Синие волны", 235f, 0.70f, 0.28f, "kb_theme_1.jpg"),
        NSkin("Нейроны", 265f, 0.45f, 0.20f, "kb_theme_2.jpg"),
        NSkin("Череп", 110f, 0.45f, 0.30f, "kb_theme_3.jpg"),
        NSkin("Циан соты", 185f, 0.50f, 0.25f, "kb_theme_4.jpg"),
        NSkin("Мрамор", 215f, 0.45f, 0.50f, "kb_theme_5.jpg"),
        NSkin("Серые соты", 195f, 0.40f, 0.50f, "kb_theme_6.jpg"),
        NSkin("Зелёные соты", 130f, 0.55f, 0.25f, "kb_theme_7.jpg"),
        NSkin("Пляж спереди", 185f, 0.50f, 0.40f, "kb_theme_8.jpg"),
        NSkin("Без скина", 235f, 0.50f, 0.30f, null)
    )

    private val EN3 = "q w e r t y u i o p [ ]".split(" ")
    private val RU3 = "й ц у к е н г ш щ з х ъ".split(" ")
    private val EN4 = "a s d f g h j k l ; '".split(" ")
    private val RU4 = "ф ы в а п р о л д ж э".split(" ")
    private val EN5 = "z x c v b n m , . /".split(" ")
    private val RU5 = "я ч с м и т ь б ю .".split(" ")
    private val UK = mapOf("ъ" to "ї", "ы" to "і", "э" to "є")

    private val SH_EN = mapOf(
        "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^", "7" to "&",
        "8" to "*", "9" to "(", "0" to ")", "-" to "_", "=" to "+", "[" to "{", "]" to "}", ";" to ":",
        "'" to "\"", "," to "<", "." to ">", "/" to "?"
    )
    private val SH_RU = mapOf(
        "1" to "!", "2" to "\"", "3" to "№", "4" to ";", "5" to "%", "6" to ":", "7" to "?", "8" to "*",
        "9" to "(", "0" to ")", "-" to "_", "=" to "+", "." to ","
    )

    private val EMOJI = "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 🎃 😺 💋 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💯 💢 💥 💫 💦 💨 💬 💭 💤 👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 👃 🧠 🦷 🦴 👀 👁️ 👅 👄 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🥙 🧆 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🎣 🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 ✈️ 🛫 🛬 🛩️ 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🕹️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 💡 🔦 🕯️ 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️".split(" ").filter { it.isNotEmpty() }

    private val BIDI = listOf(
        "RLO" to "\u202E", "LRO" to "\u202D", "RLM" to "\u200F", "LRM" to "\u200E",
        "RLE" to "\u202B", "LRE" to "\u202A", "PDF" to "\u202C", "FSI" to "\u2068",
        "PDI" to "\u2069", "RLI" to "\u2067", "LRI" to "\u2066", "ALM" to "\u061C"
    )

    /** Регистр/Shift: как shifted() в HTML. */
    fun shifted(s: String, shift: Boolean, caps: Boolean, lang: Int): String {
        if (s.length != 1) return s
        if (s[0].isLetter()) return if (shift || caps) s.uppercase() else s
        return if (shift) ((if (lang == 0) SH_EN else SH_RU)[s] ?: s) else s
    }

    private fun c(code: String, label: String, w: Float = 1f) = NK(label, code, w)
    private fun ch(line: String) = line.split(" ").filter { it.isNotEmpty() }.map { NK(it) }
    private fun lr(ru: List<String>, en: List<String>, l: Int) = ru.mapIndexed { i, r ->
        NK(if (l == 0) en[i] else if (l == 2) (UK[r] ?: r) else r, "", 1f, en[i])
    }

    private fun f(): List<NK> =
        listOf(c("ESC", "Esc")) + (1..12).map { c("F$it", "F$it") } + c("CLIP", "📋", 1.5f)

    private fun mb(o: NK): List<NK> = listOf(
        c("CTRL", "Ctrl", 1.4f), c("ALT", "Alt"), c("SPACE", "Space", 4f),
        c("L:emoji", "😊"), c("L:unicode", "⌘"), c("L:letters", "АБВ", 1.4f), o, c("ENTER", "Enter", 1.6f)
    )

    fun layer(name: String, l: Int): NLayer = when (name) {
        "symbols" -> NLayer(
            listOf(
                f(),
                ch("1 2 3 4 5 6 7 8 9 0 № %") + c("BKSP", "⌫", 2.5f),
                ch("+ − = / × ( ) [ ] { } & _"),
                ch("€ $ ₽ £ ¥ ₴ ₹ ₩ ₺ ₪ ¢ ₿"),
                listOf(c("LEFT", "←"), c("RIGHT", "→"), c("UP", "↑"), c("DOWN", "↓")) + ch("! ? , . ; : ' \""),
                mb(c("L:more", "#+=", 1.4f))
            )
        )
        "more" -> NLayer(
            listOf(
                listOf(
                    c("CLIP", "📋", 1.5f), c("HOME", "Home"), c("END", "End"),
                    c("PGUP", "PgUp"), c("PGDN", "PgDn"), c("DEL", "Del")
                ),
                ch("~ ` ^ ° √ π Δ ∑ ≠ ≤ ≥ ∞") + c("BKSP", "⌫", 1.5f),
                ch("• · … – — « » „ “ ” ‘ ’"),
                ch("¶ ♠ ♥ ♦ ♣ ★ ☆ ✓ ✗ ⚡ ❤ ☺ ☻"),
                ch("°C °F № µ Ω λ α β γ → ↔ ⇄"),
                mb(c("L:symbols", "?123", 1.4f))
            )
        )
        "emoji" -> NLayer(
            null, true,
            listOf("" to EMOJI.map { NK(it) }),
            listOf(c("L:letters", "АБВ", 1.4f), c("L:symbols", "?123", 1.4f), c("SPACE", "Space", 5f), c("BKSP", "⌫", 1.4f))
        )
        "unicode" -> NLayer(
            null, false,
            listOf(
                "Направление текста (BiDi)" to BIDI.map { (n, x) -> NK(n, "", 1f, "", x) },
                "Стрелки и указатели" to ch("← → ↑ ↓ ↔ ↕ ⇐ ⇒ ⇑ ⇓ ⇔ ⇕ ↖ ↗ ↘ ↙ ↺ ↻ ⇄ ⇆ ⇋ ⇌ ⇧ ⇩ ⏎ ⏏ ⏩ ⏪ ⏫ ⏬ ▶ ◀ ▲ ▼ ➡ ⬅ ⬆ ⬇ ➤ ➔ ➜ ➞ ➟ ➠ ⟶ ⟵ ⟷ ⟹"),
                "Математика" to ch("± × ÷ ≠ ≈ ≡ ≤ ≥ < > ∞ √ ∑ ∏ ∫ ∂ ∇ ∆ π µ Ω λ α β γ δ ε θ φ ψ ω % ‰ ‱ ° ′ ″ ∅ ∈ ∉ ⊂ ⊃ ∪ ∩ ∨ ∧ ¬ ⊕"),
                "Спецсимволы" to ch("§ ¶ © ® ™ † ‡ • · … — – « » „ “ ” ‘ ’ ′ ″ ‴ ␣ ⌘ ⌥ ⇧ ⌫ ⎋ ⏎ ☰ ☱ ☲ ☳ ☴ ♠ ♥ ♦ ♣ ★ ☆ ✓ ✗ ⚡ ❤ ☺ ☻ № ⏳")
            ),
            listOf(
                c("CTRL", "Ctrl", 1.2f), c("ALT", "Alt"), c("SPACE", "Space", 4f),
                c("L:letters", "АБВ", 1.4f), c("L:symbols", "?123", 1.4f), c("BKSP", "⌫", 1.4f)
            )
        )
        else -> NLayer(
            listOf(
                f(),
                listOf(NK(if (l == 0) "`" else if (l == 1) "ё" else "'", "", 1f, "`")) +
                    "1 2 3 4 5 6 7 8 9 0 - =".split(" ").map { NK(it, "", 1f, it) } + c("BKSP", "⌫", 1.5f),
                listOf(c("TAB", "Tab", 2.5f)) + lr(RU3, EN3, l),
                listOf(c("CAPS", "Caps", 1.75f)) + lr(RU4, EN4, l) + c("ENTER", "Enter", 1.75f),
                listOf(c("SHIFT", "Shift", 2.25f)) + lr(RU5, EN5, l) + c("SHIFT", "Shift", 2.25f),
                listOf(
                    c("CTRL", "Ctrl", 1.4f), c("WIN", "Win"), c("ALT", "Alt"), c("SPACE", "Space", 6.3f),
                    c("L:unicode", "⌘"), c("L:symbols", "?123", 1.4f), c("ALT", "Alt"), c("CTRL", "Ctrl", 1.4f)
                )
            )
        )
    }
}

// ======================================================================
//  Нативная неоновая клавиатура: рисуется на Canvas, без WebView.
//  Нажатие обрабатывается прямо на ACTION_DOWN, тяжёлые тени клавиш
//  рисуются один раз в кэш-битмапы, при касании рисуется готовая картинка.
// ======================================================================

private class KeyBmp(val bmp: Bitmap, val pad: Int)

private class Ptr(var kind: Int, var idx: Int, val sx: Float, val sy: Float) {
    var lastY = sy
    var scrolling = false
}

class NeonKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Событие клавиши в том же формате JSON, что раньше слал HTML (code, label, ctrl, alt, shift, caps, win, latin, text). */
    var listener: ((JSONObject) -> Unit)? = null

    /** Звук нажатия (частота как в SoundManager.beep). */
    var beep: ((Int) -> Unit)? = null

    private val dens = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("neon_kb_native", Context.MODE_PRIVATE)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---------- состояние ----------
    private var lang = 1
    private var layerName = "letters"
    private var shift = false
    private var caps = false
    private var ctrl = false
    private var alt = false
    private var win = false
    private var skinIdx = prefs.getInt("skin", 0).coerceIn(0, NeonData.SKINS.size - 1)
    private var accent = 0
    private var accentLight = 0

    // ---------- геометрия ----------
    private var gap = 0f
    private var keyH = 0f
    private var gapV = 0f
    private var innerL = 0f
    private var innerT = 0f
    private var innerR = 0f
    private var innerB = 0f
    private val wellRect = RectF()
    private val langR = RectF()
    private val skinR = RectF()
    private val setR = RectF()
    private val hideR = RectF()
    private val statusR = RectF()

    private var cur: NLayer = NeonData.layer("letters", 1)
    private val flat = ArrayList<NK>()
    private val frects = ArrayList<RectF>()
    private var gridMode = false
    private val gkeys = ArrayList<NK>()
    private val grects = ArrayList<RectF>()
    private val gtitles = ArrayList<Pair<String, RectF>>()
    private var gridTop = 0f
    private var gridBottom = 0f
    private var contentH = 0f
    private var maxScroll = 0f
    private var gridScroll = 0f
    private var lastKeyH = -1f
    private var lastGap = -1f
    private var lastHue = -1f

    // ---------- касания ----------
    private val ptrs = SparseArray<Ptr>()
    private val pressedFlat = HashSet<Int>()
    private val pressedGrid = HashSet<Int>()
    private var pressedBar = -1
    private var vt: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var repeatKey: NK? = null
    private var repeatRun: Runnable? = null

    // ---------- скин / панель скинов ----------
    private var staticBmp: Bitmap? = null
    private var skinBmp: Bitmap? = null
    private var skinGen = 0
    private var panelOpen = false
    private val thumbs = arrayOfNulls<Bitmap>(NeonData.SKINS.size)
    private var thumbsLoading = false
    private val panelClose = RectF()
    private val panelCells = Array(NeonData.SKINS.size) { RectF() }
    private var panelThumb = 0f

    // ---------- кисти / кэш ----------
    private val pp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val shPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val clipP = Path()
    private val holeP = Path()
    private val tmpR = RectF()
    private val keyCache = object : LruCache<Long, KeyBmp>(14 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: KeyBmp): Int = value.bmp.byteCount
    }

    init {
        applyHue()
        isClickable = true
    }

    // ======================================================================
    //  Публичное API
    // ======================================================================

    fun setLang(n: Int) {
        lang = n.coerceIn(0, 2)
        layerName = "letters"
        shift = false; caps = false; ctrl = false; alt = false; win = false
        gridScroll = 0f
        relayout()
        invalidate()
    }

    // ======================================================================
    //  Цвета и скин
    // ======================================================================

    private fun applyHue() {
        val hue = NeonData.SKINS[skinIdx].hue
        accent = ColorUtils.HSLToColor(floatArrayOf(hue, 1f, 0.58f))
        accentLight = ColorUtils.HSLToColor(floatArrayOf(hue, 1f, 0.84f))
    }

    private fun al(color: Int, a: Float): Int = ColorUtils.setAlphaComponent(color, (a * 255f).roundToInt().coerceIn(0, 255))

    private fun blurR(cssPx: Float): Float = max(0.5f, cssPx * dens * 0.8f)

    private fun selectSkin(i: Int) {
        skinIdx = i.coerceIn(0, NeonData.SKINS.size - 1)
        prefs.edit().putInt("skin", skinIdx).apply()
        applyHue()
        keyCache.evictAll()
        lastHue = NeonData.SKINS[skinIdx].hue
        loadSkin()
        rebuildStatic()
        invalidate()
    }

    private fun loadSkin() {
        val gen = ++skinGen
        val sk = NeonData.SKINS[skinIdx]
        val w = width
        val h = height
        val img = sk.img
        if (img == null || w <= 0 || h <= 0) {
            skinBmp = null
            return
        }
        Thread {
            var out: Bitmap? = null
            try {
                val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(img).use { BitmapFactory.decodeStream(it, null, o1) }
                var ss = 1
                while (o1.outWidth / (ss * 2) >= w && o1.outHeight / (ss * 2) >= h) ss *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = ss }
                val src = context.assets.open(img).use { BitmapFactory.decodeStream(it, null, o2) }
                if (src != null) {
                    out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(out)
                    val s = max(w / src.width.toFloat(), h / src.height.toFloat())
                    val m = Matrix()
                    m.postScale(s, s)
                    m.postTranslate((w - src.width * s) / 2f, (h - src.height * s) * sk.focusY)
                    cv.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    src.recycle()
                }
            } catch (_: Throwable) {
            }
            post {
                if (gen == skinGen) {
                    skinBmp = out
                    rebuildStatic()
                    invalidate()
                }
            }
        }.start()
    }

    private fun loadThumbs() {
        if (thumbsLoading) return
        thumbsLoading = true
        Thread {
            for ((i, sk) in NeonData.SKINS.withIndex()) {
                val img = sk.img ?: continue
                var out: Bitmap? = null
                try {
                    val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.assets.open(img).use { BitmapFactory.decodeStream(it, null, o1) }
                    var ss = 1
                    while (o1.outWidth / (ss * 2) >= 200 && o1.outHeight / (ss * 2) >= 200) ss *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = ss }
                    val src = context.assets.open(img).use { BitmapFactory.decodeStream(it, null, o2) }
                    if (src != null) {
                        val side = min(src.width, src.height)
                        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                        out = Bitmap.createScaledBitmap(crop, 200, 200, true)
                        if (crop !== src) crop.recycle()
                        src.recycle()
                    }
                } catch (_: Throwable) {
                }
                post {
                    thumbs[i] = out
                    if (panelOpen) invalidate()
                }
            }
        }.start()
    }

    // ======================================================================
    //  Раскладка
    // ======================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keyCache.evictAll()
        relayout()
        loadSkin()
        rebuildStatic()
    }

    private fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dp = dens
        cur = NeonData.layer(layerName, lang)

        val barTop = 8 * dp
        val barH = 32 * dp
        val boardT = barTop + barH + 8 * dp
        val boardB = h - 14 * dp
        val boardL = 8 * dp
        val boardR = w - 8 * dp
        wellRect.set(boardL, boardT, boardR, boardB)
        innerL = boardL + 8 * dp
        innerR = boardR - 8 * dp
        innerT = boardT + 8 * dp
        innerB = boardB - 8 * dp
        val wc = innerR - innerL
        gap = (if (wc / dp < 520f) 4f else 7f) * dp
        val u = (wc - gap * 13f) / 14.5f
        val dw = (u * 1.45f).roundToInt().toFloat()
        val innerH = innerB - innerT
        val dh = floor((innerH - gap * 5f) / 6f)
        keyH = min(dw, dh).coerceIn(22 * dp, 56 * dp)
        if (keyH != lastKeyH || gap != lastGap || NeonData.SKINS[skinIdx].hue != lastHue) {
            keyCache.evictAll()
            lastKeyH = keyH
            lastGap = gap
            lastHue = NeonData.SKINS[skinIdx].hue
        }

        // верхняя панель
        val bTop = barTop + 1 * dp
        val bH = 30 * dp
        tp.textSize = 12 * dp
        val langW = max(44 * dp, tp.measureText("РУС") + 24 * dp)
        langR.set(8 * dp, bTop, 8 * dp + langW, bTop + bH)
        skinR.set(langR.right + 8 * dp, bTop, langR.right + 8 * dp + 30 * dp, bTop + 30 * dp)
        hideR.set(w - 8 * dp - 44 * dp, bTop, w - 8 * dp, bTop + bH)
        setR.set(hideR.left - 8 * dp - 44 * dp, bTop, hideR.left - 8 * dp, bTop + bH)
        statusR.set(skinR.right + 8 * dp, barTop + 3 * dp, setR.left - 8 * dp, barTop + barH - 3 * dp)

        flat.clear(); frects.clear(); gkeys.clear(); grects.clear(); gtitles.clear()
        val rows = cur.rows
        if (rows != null) {
            gridMode = false
            val n = rows.size
            gapV = if (n > 1) max(gap, (innerH - n * keyH) / (n - 1)) else gap
            for ((ri, row) in rows.withIndex()) layoutRow(row, innerT + ri * (keyH + gapV))
            maxScroll = 0f
            gridScroll = 0f
        } else {
            gridMode = true
            gapV = gap
            layoutRow(cur.bottom, innerB - keyH)
            gridTop = innerT
            gridBottom = innerB - keyH - gap
            val gx0 = innerL + 4 * dp
            val gw = wc - 8 * dp
            val cols = max(1, floor((gw + gap) / (keyH * 1.05f + gap)).toInt())
            val cw = (gw - gap * (cols - 1)) / cols
            var y = 4 * dp
            for ((title, keys) in cur.sections) {
                if (title.isNotEmpty()) {
                    gtitles.add(title to RectF(gx0, y, gx0 + gw, y + 16 * dp))
                    y += 16 * dp + gap
                }
                var col = 0
                for (k in keys) {
                    val x = gx0 + col * (cw + gap)
                    grects.add(RectF(x, y, x + cw, y + keyH))
                    gkeys.add(k)
                    col++
                    if (col == cols) {
                        col = 0
                        y += keyH + gap
                    }
                }
                if (col != 0) y += keyH + gap
            }
            contentH = y - gap + 4 * dp
            maxScroll = max(0f, contentH - (gridBottom - gridTop))
            gridScroll = gridScroll.coerceIn(0f, maxScroll)
        }
    }

    private fun layoutRow(keys: List<NK>, y: Float) {
        var sum = 0f
        for (k in keys) sum += k.w
        val usable = (innerR - innerL) - gap * (keys.size - 1)
        var x = innerL
        for (k in keys) {
            val kw = usable * k.w / sum
            frects.add(RectF(x, y, x + kw, y + keyH))
            flat.add(k)
            x += kw + gap
        }
    }

    // ======================================================================
    //  Рисование: статический слой (фон, скин, «ванна» под клавиши)
    // ======================================================================

    private fun insetShadow(cv: Canvas, r: RectF, rad: Float, dx: Float, dy: Float, blur: Float, color: Int) {
        cv.save()
        clipP.rewind()
        clipP.addRoundRect(r, rad, rad, Path.Direction.CW)
        cv.clipPath(clipP)
        val ext = blur * 3f + abs(dx) + abs(dy) + 4f
        holeP.rewind()
        holeP.fillType = Path.FillType.EVEN_ODD
        holeP.addRect(r.left - ext, r.top - ext, r.right + ext, r.bottom + ext, Path.Direction.CW)
        tmpR.set(r)
        tmpR.offset(dx, dy)
        holeP.addRoundRect(tmpR, rad, rad, Path.Direction.CW)
        shPaint.reset()
        shPaint.isAntiAlias = true
        shPaint.style = Paint.Style.FILL
        shPaint.color = color
        if (blur > 0f) shPaint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        cv.drawPath(holeP, shPaint)
        cv.restore()
    }

    private fun rebuildStatic() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        var bmp = staticBmp
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            staticBmp = bmp
        }
        bmp.eraseColor(0)
        val cv = Canvas(bmp)
        val dp = dens
        val rr = 22 * dp
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val shell = Path()
        shell.addRoundRect(full, floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f), Path.Direction.CW)

        cv.save()
        cv.clipPath(shell)
        cv.drawColor(0xFF02030A.toInt())
        skinBmp?.let { cv.drawBitmap(it, 0f, 0f, null) }
        pp.reset()
        pp.style = Paint.Style.FILL
        pp.color = Color_argb((NeonData.SKINS[skinIdx].dim * 255f).roundToInt(), 2, 3, 10)
        cv.drawRect(full, pp)
        insetShadow(cv, full, 0f, 0f, 0f, blurR(40f) / 2f, al(0xFF000000.toInt(), 0.55f))
        cv.restore()

        // верхняя неоновая граница и блик
        cv.save()
        cv.clipRect(0f, 0f, w.toFloat(), rr + 2 * dp)
        pp.reset()
        pp.isAntiAlias = true
        pp.style = Paint.Style.STROKE
        pp.strokeWidth = dp
        pp.color = al(accent, 0.6f)
        cv.drawPath(shell, pp)
        cv.restore()
        cv.save()
        cv.clipPath(shell)
        insetShadow(cv, full, rr, 0f, dp, 0f, al(0xFFFFFFFF.toInt(), 0.14f))
        cv.restore()

        // плашка «NEON KEYS»
        pp.reset()
        pp.isAntiAlias = true
        pp.style = Paint.Style.FILL
        pp.color = Color_argb(140, 2, 3, 10)
        cv.drawRoundRect(statusR, 10 * dp, 10 * dp, pp)
        tp.textSize = 11 * dp
        tp.letterSpacing = 0.25f
        tp.color = accentLight
        tp.setShadowLayer(4 * dp, 0f, 0f, accent)
        val fm = tp.fontMetrics
        cv.drawText("NEON KEYS", statusR.centerX(), statusR.centerY() - (fm.ascent + fm.descent) / 2f, tp)
        tp.clearShadowLayer()
        tp.letterSpacing = 0f

        // «ванна» под клавиши
        pp.reset()
        pp.isAntiAlias = true
        pp.style = Paint.Style.FILL
        pp.color = al(0xFF000000.toInt(), 0.3f)
        cv.drawRoundRect(wellRect, 18 * dp, 18 * dp, pp)
        insetShadow(cv, wellRect, 18 * dp, 0f, 6 * dp, blurR(16f), al(0xFF000000.toInt(), 0.8f))
        pp.reset()
        pp.isAntiAlias = true
        pp.style = Paint.Style.STROKE
        pp.strokeWidth = dp
        pp.color = al(0xFFFFFFFF.toInt(), 0.06f)
        tmpR.set(wellRect)
        tmpR.inset(dp / 2f, dp / 2f)
        cv.drawRoundRect(tmpR, 18 * dp, 18 * dp, pp)
    }

    @Suppress("FunctionName")
    private fun Color_argb(a: Int, r: Int, g: Int, b: Int): Int = android.graphics.Color.argb(a, r, g, b)

    // ======================================================================
    //  Рисование: клавиши (кэш-битмапы)
    // ======================================================================

    private fun keyBmp(w: Int, h: Int, pressed: Boolean): KeyBmp {
        val key = (w.toLong() shl 24) or (h.toLong() shl 1) or (if (pressed) 1L else 0L)
        var kb = keyCache.get(key)
        if (kb == null) {
            kb = renderKey(w, h, pressed)
            keyCache.put(key, kb)
        }
        return kb
    }

    private fun renderKey(w: Int, h: Int, pressed: Boolean): KeyBmp {
        val dp = dens
        val outset = gap * 0.45f
        val glowPad = if (pressed) 20 * dp else 13 * dp
        val pad = ceil(outset + glowPad).toInt()
        val bw = w + 2 * pad
        val bh = h + 2 * pad
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val rs = keyH * 0.36f
        val rb = keyH * 0.3f
        val sock = RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat())
        sock.inset(-outset, -outset)
        val btn = RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat())
        if (pressed) btn.offset(0f, 3 * dp)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()

        // 1. свечение гнезда снаружи
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.FILL
        if (pressed) {
            pp.color = al(accent, 0.6f)
            pp.maskFilter = BlurMaskFilter(blurR(20f), BlurMaskFilter.Blur.NORMAL)
            cv.drawRoundRect(sock, rs, rs, pp)
            pp.color = accent
            pp.maskFilter = BlurMaskFilter(blurR(14f), BlurMaskFilter.Blur.NORMAL)
            cv.drawRoundRect(sock, rs, rs, pp)
        } else {
            pp.color = al(accent, 0.3f)
            pp.maskFilter = BlurMaskFilter(blurR(6f), BlurMaskFilter.Blur.NORMAL)
            cv.drawRoundRect(sock, rs, rs, pp)
        }
        // 2. внутри гнезда стираем свечение и заливаем тёмным
        cv.drawRoundRect(sock, rs, rs, clearPaint)
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.FILL
        pp.color = al(black, 0.38f)
        cv.drawRoundRect(sock, rs, rs, pp)
        // 3. внутренние тени гнезда
        if (pressed) {
            insetShadow(cv, sock, rs, 0f, 3 * dp, blurR(6f), al(black, 0.9f))
            insetShadow(cv, sock, rs, 0f, 0f, blurR(16f), accent)
        } else {
            insetShadow(cv, sock, rs, 0f, 3 * dp, blurR(6f), al(black, 0.85f))
            insetShadow(cv, sock, rs, 0f, -3 * dp, blurR(8f), al(accent, 0.5f))
        }
        // 4. внешние тени кнопки (только в отпущенном виде), под самой кнопкой не рисуются
        if (!pressed) {
            val layer = cv.saveLayer(null, null)
            pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.FILL
            pp.color = al(black, 0.5f)
            pp.maskFilter = BlurMaskFilter(blurR(8f), BlurMaskFilter.Blur.NORMAL)
            tmpR.set(btn); tmpR.offset(0f, 6 * dp)
            cv.drawRoundRect(tmpR, rb, rb, pp)
            pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.FILL
            pp.color = al(black, 0.65f)
            tmpR.set(btn); tmpR.offset(0f, 3 * dp)
            cv.drawRoundRect(tmpR, rb, rb, pp)
            cv.drawRoundRect(btn, rb, rb, clearPaint)
            cv.restoreToCount(layer)
        }
        // 5. тело кнопки
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.FILL
        pp.shader = if (pressed) {
            LinearGradient(
                0f, btn.top, 0f, btn.bottom,
                intArrayOf(Color_argb(179, 10, 10, 14), Color_argb(158, 24, 24, 30)), null, Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, btn.top, 0f, btn.bottom,
                intArrayOf(Color_argb(158, 62, 62, 72), Color_argb(148, 30, 30, 38), Color_argb(163, 18, 18, 24)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        cv.drawRoundRect(btn, rb, rb, pp)
        // 6. внутренние тени кнопки
        if (pressed) {
            insetShadow(cv, btn, rb, 0f, 3 * dp, blurR(6f), al(black, 0.8f))
            insetShadow(cv, btn, rb, 0f, 0f, blurR(12f), al(accent, 0.4f))
        } else {
            insetShadow(cv, btn, rb, 0f, dp, 0f, al(white, 0.18f))
            insetShadow(cv, btn, rb, 0f, -3 * dp, blurR(5f), al(black, 0.4f))
        }
        // 7. рамка
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.STROKE; pp.strokeWidth = dp
        tmpR.set(btn); tmpR.inset(dp / 2f, dp / 2f)
        if (pressed) {
            pp.color = accent
            cv.drawRoundRect(tmpR, rb, rb, pp)
            tmpR.set(btn); tmpR.inset(-dp / 2f, -dp / 2f)
            cv.drawRoundRect(tmpR, rb + dp / 2f, rb + dp / 2f, pp)
        } else {
            pp.color = al(white, 0.1f)
            cv.drawRoundRect(tmpR, rb, rb, pp)
            cv.save()
            cv.clipRect(btn.left, btn.bottom - 3 * dp, btn.right, btn.bottom + dp)
            pp.color = al(accent, 0.75f)
            cv.drawRoundRect(tmpR, rb, rb, pp)
            cv.restore()
        }
        return KeyBmp(bmp, pad)
    }

    private fun isActive(k: NK): Boolean = when (k.code) {
        "SHIFT" -> shift
        "CAPS" -> caps
        "CTRL" -> ctrl
        "ALT" -> alt
        "WIN" -> win
        else -> k.code == "L:$layerName"
    }

    /** kind: 0 — буквенные ряды, 1 — emoji, 2 — unicode-сетка. */
    private fun drawKey(c: Canvas, k: NK, r: RectF, pressed: Boolean, kind: Int) {
        val dp = dens
        val w = r.width().roundToInt()
        val h = r.height().roundToInt()
        if (w <= 0 || h <= 0) return
        val kb = keyBmp(w, h, pressed)
        c.drawBitmap(kb.bmp, r.left - kb.pad, r.top - kb.pad, null)

        val label = when {
            k.code == "SPACE" -> NeonData.LANGS[lang]
            k.code.isNotEmpty() -> k.label
            k.text != null -> k.label
            else -> NeonData.shifted(k.label, shift, caps, lang)
        }
        val tx = kind != 1 && label.codePointCount(0, label.length) > 1
        var size = keyH * (if (tx) 0.30f else 0.44f)
        tp.textSize = size
        val maxW = r.width() - 6 * dp
        val tw = tp.measureText(label)
        if (tw > maxW && tw > 0f) {
            size = size * maxW / tw
            tp.textSize = size
        }
        if (kind == 1) {
            tp.color = 0xFFFFFFFF.toInt()
            tp.clearShadowLayer()
        } else if (pressed) {
            tp.color = 0xFFFFFFFF.toInt()
            tp.setShadowLayer(6 * dp, 0f, 0f, accent)
        } else {
            tp.color = accentLight
            tp.setShadowLayer(5 * dp, 0f, 0f, accent)
        }
        val dy = if (pressed) 3 * dp else 0f
        val fm = tp.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() + dy - (fm.ascent + fm.descent) / 2f, tp)
        tp.clearShadowLayer()

        if (kind == 0 && k.latin.isNotEmpty() && k.latin != label && (lang != 0 || ctrl)) {
            tp.textSize = keyH * 0.2f
            tp.color = al(accent, 0.9f)
            c.drawText(k.latin.uppercase(), r.centerX(), r.bottom + dy - 3 * dp, tp)
        }
    }

    private fun drawKeys(c: Canvas) {
        val dp = dens
        if (!gridMode) {
            for (i in flat.indices) {
                val k = flat[i]
                drawKey(c, k, frects[i], pressedFlat.contains(i) || isActive(k), 0)
            }
            return
        }
        val kind = if (cur.emoji) 1 else 2
        c.save()
        c.clipRect(innerL - gap, gridTop, innerR + gap, gridBottom)
        c.translate(0f, gridTop - gridScroll)
        val viewH = gridBottom - gridTop
        for ((title, r) in gtitles) {
            if (r.bottom - gridScroll < 0f || r.top - gridScroll > viewH) continue
            tp.textSize = 11 * dp
            tp.color = accentLight
            tp.textAlign = Paint.Align.LEFT
            tp.setShadowLayer(3 * dp, 0f, 0f, accent)
            val fm = tp.fontMetrics
            c.drawText(title, r.left + 2 * dp, r.centerY() - (fm.ascent + fm.descent) / 2f, tp)
            tp.clearShadowLayer()
            tp.textAlign = Paint.Align.CENTER
        }
        for (i in grects.indices) {
            val r = grects[i]
            if (r.bottom - gridScroll < -gap || r.top - gridScroll > viewH + gap) continue
            drawKey(c, gkeys[i], r, pressedGrid.contains(i), kind)
        }
        c.restore()
        for (i in flat.indices) {
            val k = flat[i]
            drawKey(c, k, frects[i], pressedFlat.contains(i) || isActive(k), 0)
        }
    }

    // ======================================================================
    //  Рисование: верхняя панель и панель скинов
    // ======================================================================

    private fun drawBtn(c: Canvas, rect: RectF, text: String, pressed: Boolean) {
        val dp = dens
        val r = RectF(rect)
        if (pressed) r.offset(0f, 2 * dp)
        val rad = 12 * dp
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.FILL
        pp.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            intArrayOf(Color_argb(179, 62, 62, 72), Color_argb(179, 22, 22, 27)), null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, rad, rad, pp)
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.STROKE; pp.strokeWidth = dp
        tmpR.set(r); tmpR.inset(dp / 2f, dp / 2f)
        pp.color = if (pressed) accent else al(0xFFFFFFFF.toInt(), 0.08f)
        c.drawRoundRect(tmpR, rad, rad, pp)
        if (!pressed) {
            c.save()
            c.clipRect(r.left, r.bottom - 4 * dp, r.right, r.bottom + dp)
            pp.color = accent
            c.drawRoundRect(tmpR, rad, rad, pp)
            c.restore()
        }
        tp.textSize = 12 * dp
        tp.color = accentLight
        tp.setShadowLayer(3 * dp, 0f, 0f, accent)
        val fm = tp.fontMetrics
        c.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, tp)
        tp.clearShadowLayer()
    }

    private fun drawSkinBtn(c: Canvas, pressed: Boolean) {
        val dp = dens
        val cx = skinR.centerX()
        val cy = skinR.centerY() + (if (pressed) 2 * dp else 0f)
        val rad = skinR.width() / 2f
        val dark = ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.6f)
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.FILL
        pp.shader = RadialGradient(
            cx - rad * 0.3f, cy - rad * 0.4f, rad * 1.7f,
            intArrayOf(0xFFFFFFFF.toInt(), accent, dark), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, rad, pp)
        pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.STROKE; pp.strokeWidth = dp
        pp.color = if (pressed) accent else al(0xFFFFFFFF.toInt(), 0.12f)
        c.drawCircle(cx, cy, rad - dp / 2f, pp)
    }

    private fun drawBar(c: Canvas) {
        drawBtn(c, langR, NeonData.LANGS[lang], pressedBar == 0)
        drawSkinBtn(c, pressedBar == 1)
        drawBtn(c, setR, "⚙", pressedBar == 2)
        drawBtn(c, hideR, "⌄", pressedBar == 3)
    }

    private fun layoutPanel() {
        val dp = dens
        val w = width.toFloat()
        val h = height.toFloat()
        val m = 10 * dp
        panelClose.set(w - m - 44 * dp, m, w - m, m + 30 * dp)
        val top = m + 36 * dp + 8 * dp
        val gapP = 8 * dp
        val cols = 4
        val rows = (NeonData.SKINS.size + cols - 1) / cols
        val areaW = w - 2 * m
        val cw = (areaW - gapP * (cols - 1)) / cols
        val labelH = 18 * dp
        val avail = (h - m - top - (rows - 1) * gapP) / rows - labelH
        panelThumb = max(24 * dp, min(cw, avail))
        for (i in NeonData.SKINS.indices) {
            val col = i % cols
            val row = i / cols
            val x = m + col * (cw + gapP) + (cw - panelThumb) / 2f
            val y = top + row * (panelThumb + labelH + gapP)
            panelCells[i].set(x, y, x + panelThumb, y + panelThumb)
        }
    }

    private fun drawPanel(c: Canvas) {
        val dp = dens
        layoutPanel()
        pp.reset(); pp.style = Paint.Style.FILL
        pp.color = Color_argb(240, 2, 3, 10)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pp)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 13 * dp
        tp.color = accentLight
        tp.setShadowLayer(3 * dp, 0f, 0f, accent)
        val fm = tp.fontMetrics
        c.drawText("Скины", 10 * dp, panelClose.centerY() - (fm.ascent + fm.descent) / 2f, tp)
        tp.clearShadowLayer()
        tp.textAlign = Paint.Align.CENTER
        drawBtn(c, panelClose, "✕", false)
        for ((i, sk) in NeonData.SKINS.withIndex()) {
            val r = panelCells[i]
            val acc = ColorUtils.HSLToColor(floatArrayOf(sk.hue, 1f, 0.58f))
            val sel = i == skinIdx
            val rad = 14 * dp
            c.save()
            clipP.rewind()
            clipP.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.clipPath(clipP)
            pp.reset(); pp.style = Paint.Style.FILL; pp.color = 0xFF0A0C14.toInt()
            c.drawRect(r, pp)
            val tb = thumbs[i]
            if (tb != null) c.drawBitmap(tb, null, r, null)
            c.restore()
            pp.reset(); pp.isAntiAlias = true; pp.style = Paint.Style.STROKE
            pp.strokeWidth = if (sel) 2 * dp else dp
            pp.color = if (sel) acc else al(0xFFFFFFFF.toInt(), 0.1f)
            tmpR.set(r); tmpR.inset(dp / 2f, dp / 2f)
            c.drawRoundRect(tmpR, rad, rad, pp)
            pp.strokeWidth = 2 * dp
            pp.color = al(acc, if (sel) 1f else 0.8f)
            c.save()
            c.clipRect(r.left, r.bottom - 3 * dp, r.right, r.bottom + dp)
            c.drawRoundRect(tmpR, rad, rad, pp)
            c.restore()
            tp.textSize = 10 * dp
            tp.color = if (sel) 0xFFFFFFFF.toInt() else 0xFF8AA0B8.toInt()
            c.drawText(sk.name, r.centerX(), r.bottom + 13 * dp, tp)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val sb = staticBmp
        if (sb != null) canvas.drawBitmap(sb, 0f, 0f, null) else canvas.drawColor(0xFF02030A.toInt())
        drawBar(canvas)
        drawKeys(canvas)
        if (panelOpen) drawPanel(canvas)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            gridScroll = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    // ======================================================================
    //  Нажатия клавиш
    // ======================================================================

    private fun emitRaw(code: String) {
        listener?.invoke(JSONObject().put("code", code))
    }

    private fun fire(k: NK) {
        val code = k.code
        when {
            code == "SHIFT" -> { shift = !shift; beep?.invoke(220); invalidate(); return }
            code == "CAPS" -> { caps = !caps; beep?.invoke(220); invalidate(); return }
            code == "CTRL" -> { ctrl = !ctrl; beep?.invoke(220); invalidate(); return }
            code == "ALT" -> { alt = !alt; beep?.invoke(220); invalidate(); return }
            code == "WIN" -> { win = !win; beep?.invoke(220); invalidate(); return }
            code.startsWith("L:") -> {
                layerName = code.substring(2)
                gridScroll = 0f
                scroller.forceFinished(true)
                beep?.invoke(if (layerName == "emoji") 420 else if (layerName == "unicode") 500 else 340)
                relayout()
                invalidate()
                return
            }
        }
        val o = JSONObject()
            .put("code", code)
            .put("label", k.label)
            .put("ctrl", ctrl)
            .put("alt", alt)
            .put("shift", shift)
            .put("caps", caps)
            .put("win", win)
            .put("latin", k.latin)
        if (code.isEmpty()) o.put("text", k.text ?: NeonData.shifted(k.label, shift, caps, lang))
        listener?.invoke(o)
        if (shift || ctrl || alt || win) {
            shift = false; ctrl = false; alt = false; win = false
            invalidate()
        }
    }

    private fun startRepeat(k: NK) {
        stopRepeat()
        repeatKey = k
        val r = object : Runnable {
            override fun run() {
                val kk = repeatKey ?: return
                fire(kk)
                ui.postDelayed(this, 50)
            }
        }
        repeatRun = r
        ui.postDelayed(r, 400)
    }

    private fun stopRepeat() {
        repeatRun?.let { ui.removeCallbacks(it) }
        repeatRun = null
        repeatKey = null
    }

    private fun barAction(i: Int) {
        when (i) {
            0 -> {
                lang = (lang + 1) % 3
                layerName = "letters"
                gridScroll = 0f
                relayout()
                invalidate()
                listener?.invoke(JSONObject().put("code", "LANG").put("lang", lang))
            }
            1 -> {
                panelOpen = true
                loadThumbs()
                invalidate()
            }
            2 -> emitRaw("SETTINGS")
            3 -> emitRaw("HIDE")
        }
    }

    private fun barHit(x: Float, y: Float): Int = when {
        langR.contains(x, y) -> 0
        skinR.contains(x, y) -> 1
        setR.contains(x, y) -> 2
        hideR.contains(x, y) -> 3
        else -> -1
    }

    private fun hitRows(x: Float, y: Float): Int {
        val gv = max(gap, gapV) / 2f
        val gh = gap / 2f
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in frects.indices) {
            val r = frects[i]
            if (x >= r.left - gh && x <= r.right + gh && y >= r.top - gv && y <= r.bottom + gv) {
                val dx = x - r.centerX()
                val dy = y - r.centerY()
                val dd = dx * dx + dy * dy
                if (dd < bestD) { bestD = dd; best = i }
            }
        }
        return best
    }

    private fun hitGrid(x: Float, cy: Float): Int {
        for (i in grects.indices) {
            if (grects[i].contains(x, cy)) return i
        }
        return -1
    }

    private fun pDown(id: Int, x: Float, y: Float) {
        val b = barHit(x, y)
        if (b >= 0) {
            ptrs.put(id, Ptr(3, b, x, y))
            pressedBar = b
            invalidate()
            return
        }
        if (gridMode && y >= gridTop && y <= gridBottom && x >= innerL - gap && x <= innerR + gap) {
            scroller.forceFinished(true)
            val idx = hitGrid(x, y - gridTop + gridScroll)
            ptrs.put(id, Ptr(2, idx, x, y))
            if (idx >= 0) pressedGrid.add(idx)
            invalidate()
            return
        }
        val idx = hitRows(x, y)
        if (idx < 0) {
            ptrs.put(id, Ptr(0, -1, x, y))
            return
        }
        ptrs.put(id, Ptr(1, idx, x, y))
        pressedFlat.add(idx)
        val k = flat[idx]
        fire(k)
        if (k.code in REPEAT_CODES) startRepeat(k)
        invalidate()
    }

    private fun pMove(id: Int, y: Float) {
        val p = ptrs.get(id) ?: return
        if (p.kind == 2) {
            if (!p.scrolling && abs(y - p.sy) > slop) {
                p.scrolling = true
                if (p.idx >= 0) pressedGrid.remove(p.idx)
            }
            if (p.scrolling) {
                gridScroll = (gridScroll - (y - p.lastY)).coerceIn(0f, maxScroll)
                invalidate()
            }
            p.lastY = y
        }
    }

    private fun pUp(id: Int, x: Float, y: Float, cancel: Boolean) {
        val p = ptrs.get(id) ?: return
        ptrs.remove(id)
        when (p.kind) {
            1 -> {
                pressedFlat.remove(p.idx)
                if (repeatKey === flat.getOrNull(p.idx)) stopRepeat()
            }
            2 -> {
                if (p.idx >= 0) pressedGrid.remove(p.idx)
                if (!cancel && !p.scrolling && p.idx >= 0 && p.idx < gkeys.size) {
                    fire(gkeys[p.idx])
                }
            }
            3 -> {
                pressedBar = -1
                if (!cancel && barHit(x, y) == p.idx) barAction(p.idx)
            }
        }
        invalidate()
    }

    private fun panelTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val x = e.x
            val y = e.y
            if (panelClose.contains(x, y)) {
                panelOpen = false
            } else {
                for (i in panelCells.indices) {
                    val r = panelCells[i]
                    if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom + 18 * dens) {
                        panelOpen = false
                        selectSkin(i)
                        break
                    }
                }
            }
            invalidate()
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (panelOpen) return panelTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                pDown(e.getPointerId(0), e.getX(0), e.getY(0))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                for (i in 0 until e.pointerCount) pMove(e.getPointerId(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                pUp(e.getPointerId(i), e.getX(i), e.getY(i), false)
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                val id = e.getPointerId(0)
                val p = ptrs.get(id)
                if (p != null && p.kind == 2 && p.scrolling) {
                    vt?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                    val vy = vt?.yVelocity ?: 0f
                    scroller.fling(0, gridScroll.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll.toInt())
                    postInvalidateOnAnimation()
                }
                pUp(id, e.getX(0), e.getY(0), false)
                vt?.recycle()
                vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until ptrs.size()) {
                    val id = ptrs.keyAt(i)
                    val p = ptrs.valueAt(i)
                    if (p.kind == 2 && p.idx >= 0) pressedGrid.remove(p.idx)
                }
                ptrs.clear()
                pressedFlat.clear()
                pressedGrid.clear()
                pressedBar = -1
                stopRepeat()
                vt?.recycle()
                vt = null
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        ++skinGen
        super.onDetachedFromWindow()
    }

    private companion object {
        val REPEAT_CODES = setOf("BKSP", "UP", "DOWN", "LEFT", "RIGHT")
    }
}
