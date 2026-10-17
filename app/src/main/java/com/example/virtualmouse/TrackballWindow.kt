package com.example.virtualmouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Во сколько раз страница едет быстрее пальца на колёсике трекбола. */
private const val BALL_WHEEL_GAIN = 2.2f

/**
 * Трекбол Orbit X (HTML-интерфейс в WebView) — те же функции, что и у тачпада:
 * движение курсора шаром, ЛКМ (удержание = перетаскивание), ПКМ (двойной = меню),
 * колёсико, режим выделения текста, файловый менеджер, буфер, клавиатура, настройки.
 */
@SuppressLint("SetJavaScriptEnabled")
class TrackballWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val cursor: CursorOverlay,
    private val getSettings: () -> CursorSettings,
    private val save: () -> Unit,
    private val onSettings: () -> Unit,
    private val onKeyboard: () -> Unit,
    private val onHide: () -> Unit
) : PadWindow {

    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val sounds = SoundManager.get(ctx) { getSettings().soundOn }

    private var root: View? = null
    private var web: WebView? = null
    private val lp = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var lockBtn: TextView? = null
    private var selBtn: TextView? = null
    private var ctxMenu: View? = null

    private var held = false
    private var leftPending = false
    private var leftIgnore = false
    private val engageRun = Runnable {
        leftPending = false
        engage()
    }

    private var selectMode = false
    private var selDrag = false

    private var rClickCount = 0
    private var rClickTimer: Runnable? = null
    private var tickAcc = 0f

    override val isShown: Boolean get() = root != null

    private fun dp(v: Int) = (v * density).toInt()

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun svc(): MouseAccessibilityService? =
        MouseAccessibilityService.instance ?: run {
            Toast.makeText(ctx, "Включите службу специальных возможностей", Toast.LENGTH_SHORT).show()
            null
        }

    private fun fit() {
        val s = getSettings()
        val (sw, sh) = screen()
        val w = dp(s.ballWidthDp).coerceIn(min(dp(220), sw), sw)
        val h = dp(s.ballHeightDp).coerceIn(min(dp(260), sh), sh)
        lp.width = w
        lp.height = h
        val px = if (s.padX < 0) (sw - w) / 2 else s.padX
        val py = if (s.padY < 0) sh - h - dp(24) else s.padY
        lp.x = px.coerceIn(0, sw - w)
        lp.y = py.coerceIn(0, sh - h)
    }

    private fun refresh() {
        val v = root ?: return
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    override fun show() {
        if (root != null) return
        val v = LayoutInflater.from(ContextThemeWrapper(ctx, R.style.Theme_VirtualMouse))
            .inflate(R.layout.overlay_trackball, null)
        fit()
        bind(v)
        try {
            wm.addView(v, lp)
            root = v
            applyColors()
        } catch (_: Exception) {
            Toast.makeText(ctx, "Нет разрешения «Поверх других окон»", Toast.LENGTH_SHORT).show()
        }
    }

    override fun hide() {
        handler.removeCallbacks(engageRun)
        leftPending = false
        if (held) release()
        if (selDrag) { MouseAccessibilityService.instance?.dragEnd(); selDrag = false }
        MouseAccessibilityService.instance?.scrollEnd()
        selectMode = false
        closeContextMenu()
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        web?.let {
            try {
                it.removeJavascriptInterface("Android")
                it.destroy()
            } catch (_: Exception) {
            }
        }
        web = null
    }

    override fun applySettings() {
        if (root == null) return
        fit()
        lockBtn?.text = if (getSettings().padLocked) "🔒" else "🔓"
        applyColors()
        refresh()
    }

    private fun applyColors() {
        root?.findViewById<TextView>(R.id.pad_title)?.setTextColor(getSettings().padColor)
    }

    override fun destroy() {
        hide()
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Мост JS -> Android ----------

    private inner class Bridge {
        @JavascriptInterface fun onMove(dx: Double, dy: Double) { handler.post { move(dx.toFloat(), dy.toFloat()) } }
        @JavascriptInterface fun onBallDown() { handler.post { ballDown() } }
        @JavascriptInterface fun onBallUp() { handler.post { ballUp() } }
        @JavascriptInterface fun onLeft(down: Int) { handler.post { if (down == 1) leftDown() else leftUp() } }
        @JavascriptInterface fun onRight() { handler.post { rightDown() } }
        @JavascriptInterface fun onWheelStart() { handler.post { wheelStart() } }
        @JavascriptInterface fun onWheel(dy: Double) { handler.post { wheelMove(dy.toFloat()) } }
        @JavascriptInterface fun onWheelEnd() { handler.post { wheelEnd() } }
    }

    private fun move(dx: Float, dy: Float) {
        val k = getSettings().sensitivity
        cursor.moveBy(dx * k, dy * k)
        if (held || selDrag) MouseAccessibilityService.instance?.dragMove(cursor.x, cursor.y)
    }

    private fun ballDown() {
        closeContextMenu()
        if (selectMode && !held) {
            val a = svc()
            if (a != null) {
                a.dragStart(cursor.x, cursor.y, 600)
                selDrag = true
                vibrate(25)
            }
        }
    }

    private fun ballUp() {
        if (selDrag) {
            MouseAccessibilityService.instance?.dragEnd()
            selDrag = false
            selectMode = false
            selBtn?.isSelected = false
            sounds.beep(180)
        }
    }

    // ---------- ЛКМ: тап = клик, удержание = перетаскивание до следующего нажатия ----------

    private fun engage() {
        val a = svc() ?: return
        a.dragStart(cursor.x, cursor.y)
        held = true
        sounds.beep(500)
        vibrate(40)
    }

    private fun release() {
        MouseAccessibilityService.instance?.dragEnd()
        held = false
        sounds.beep(180)
    }

    private fun leftDown() {
        closeContextMenu()
        if (held) {
            release()
            leftIgnore = true
        } else {
            leftIgnore = false
            leftPending = true
            sounds.beep(220)
            handler.postDelayed(engageRun, 800)
        }
    }

    private fun leftUp() {
        handler.removeCallbacks(engageRun)
        if (!leftIgnore && leftPending) {
            svc()?.click(cursor.x, cursor.y)
            sounds.beep(180)
        }
        leftPending = false
        leftIgnore = false
    }

    // ---------- ПКМ: один раз = правый клик, дважды (или при зажатой ЛКМ) = меню ----------

    private fun rightDown() {
        if (held) {
            sounds.beep(160)
            openContextMenu()
            return
        }
        rClickCount++
        rClickTimer?.let { handler.removeCallbacks(it) }
        if (rClickCount >= 2) {
            rClickCount = 0
            sounds.beep(160)
            openContextMenu()
        } else {
            sounds.beep(120)
            val runner = Runnable {
                rClickCount = 0
                svc()?.rightClick(cursor.x, cursor.y)
            }
            rClickTimer = runner
            handler.postDelayed(runner, 300)
        }
    }

    // ---------- Колёсико ----------

    private fun wheelStart() {
        closeContextMenu()
        tickAcc = 0f
        svc()?.scrollBegin(cursor.x, cursor.y)
    }

    private fun wheelMove(dyPx: Float) {
        tickAcc += abs(dyPx)
        if (tickAcc >= dp(8)) {
            sounds.wheelTick()
            tickAcc = 0f
        }
        MouseAccessibilityService.instance?.scrollBy(dyPx, BALL_WHEEL_GAIN)
    }

    private fun wheelEnd() {
        MouseAccessibilityService.instance?.scrollEnd()
    }

    // ---------- Контекстное меню ----------

    private fun openContextMenu() {
        ctxMenu?.visibility = View.VISIBLE
    }

    private fun closeContextMenu() {
        ctxMenu?.visibility = View.GONE
    }

    private fun bindContextMenu(v: View) {
        fun act(id: Int, run: (MouseAccessibilityService) -> Unit) {
            v.findViewById<View>(id)?.setOnClickListener {
                sounds.beep(180)
                closeContextMenu()
                svc()?.let(run)
            }
        }
        act(R.id.ctx_undo) { it.undo() }
        act(R.id.ctx_redo) { it.redo() }
        act(R.id.ctx_cut) { it.cutSelection() }
        act(R.id.ctx_copy) { it.copySelection() }
        act(R.id.ctx_paste) { it.pasteFromBuffer() }
        act(R.id.ctx_delete) { it.deleteSelection() }
        act(R.id.ctx_select_all) { it.selectAllText() }
    }

    // ---------- Привязка окна ----------

    private fun bind(v: View) {
        lockBtn = v.findViewById(R.id.pad_btn_lock)
        selBtn = v.findViewById(R.id.pad_btn_sel)
        ctxMenu = v.findViewById(R.id.pad_ctx_menu)

        lockBtn?.text = if (getSettings().padLocked) "🔒" else "🔓"
        lockBtn?.setOnClickListener {
            val s = getSettings()
            s.padLocked = !s.padLocked
            lockBtn?.text = if (s.padLocked) "🔒" else "🔓"
            sounds.beep(340)
            save()
        }
        selBtn?.isSelected = selectMode
        selBtn?.setOnClickListener {
            selectMode = !selectMode
            selBtn?.isSelected = selectMode
            sounds.beep(if (selectMode) 420 else 240)
            if (selectMode) Toast.makeText(ctx, "Выделение: крутите шар (удержание 0.6 с, затем движение)", Toast.LENGTH_SHORT).show()
        }
        v.findViewById<View>(R.id.pad_btn_files)?.setOnClickListener {
            sounds.beep(340)
            FileManagerLauncher.open(ctx)
        }
        v.findViewById<View>(R.id.pad_btn_kb).setOnClickListener {
            sounds.beep(340)
            onKeyboard()
        }
        v.findViewById<View>(R.id.pad_btn_settings).setOnClickListener {
            sounds.beep(340)
            onSettings()
        }
        v.findViewById<View>(R.id.pad_btn_close).setOnClickListener {
            sounds.beep(180)
            onHide()
        }
        bindTitle(v.findViewById(R.id.pad_title))
        bindResize(v.findViewById(R.id.pad_resize))
        bindContextMenu(v)
        bindWeb(v.findViewById(R.id.ball_web))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindWeb(w: WebView) {
        web = w
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.addJavascriptInterface(Bridge(), "Android")
        w.setOnLongClickListener { true }
        w.isLongClickable = false
        w.loadUrl("file:///android_asset/trackball.html")
    }

    private fun bindTitle(t: View) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        t.setOnTouchListener { _, e ->
            if (getSettings().padLocked) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                    closeContextMenu()
                }
                MotionEvent.ACTION_MOVE -> {
                    val (sw, sh) = screen()
                    lp.x = (ox + (e.rawX - sx)).toInt().coerceIn(0, (sw - lp.width).coerceAtLeast(0))
                    lp.y = (oy + (e.rawY - sy)).toInt().coerceIn(0, (sh - lp.height).coerceAtLeast(0))
                    refresh()
                }
                MotionEvent.ACTION_UP -> {
                    val s = getSettings()
                    s.padX = lp.x; s.padY = lp.y
                    save()
                }
            }
            true
        }
    }

    private fun bindResize(r: View) {
        var sx = 0f
        var sy = 0f
        var w0 = 0
        var h0 = 0
        r.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; w0 = lp.width; h0 = lp.height
                    closeContextMenu()
                }
                MotionEvent.ACTION_MOVE -> {
                    val (sw, sh) = screen()
                    lp.width = (w0 + (e.rawX - sx)).toInt().coerceIn(min(dp(220), sw), sw)
                    lp.height = (h0 + (e.rawY - sy)).toInt().coerceIn(min(dp(260), sh), sh)
                    lp.x = lp.x.coerceIn(0, sw - lp.width)
                    lp.y = lp.y.coerceIn(0, sh - lp.height)
                    refresh()
                }
                MotionEvent.ACTION_UP -> {
                    val s = getSettings()
                    s.ballWidthDp = (lp.width / density).roundToInt()
                    s.ballHeightDp = (lp.height / density).roundToInt()
                    s.padX = lp.x; s.padY = lp.y
                    save()
                }
            }
            true
        }
    }
}
