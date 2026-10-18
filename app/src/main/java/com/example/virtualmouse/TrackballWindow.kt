package com.example.virtualmouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Во сколько раз страница едет быстрее пальца на колёсике трекбола. */
private const val BALL_WHEEL_GAIN = 2.2f

/** Базовая ширина окна трекбола при масштабе 100 % (dp) и соотношение сторон корпуса. */
private const val BALL_BASE_DP = 260
private const val BALL_ASPECT = 1.15f

/**
 * Трекбол Orbit X: прозрачное окно, в котором виден только сам трекбол (без рамки тачпада).
 *  - шар — движение курсора, ЛКМ (удержание 0.8 с = перетаскивание), ПКМ (дважды = меню правки, зажатие > 100мс + движение шара = выделение рамкой),
 *  - колёсико — скролл,
 *  - двойное нажатие на ЛКМ — меню: тачпад, настройки, замок, файлы, клавиатура, правка,
 *  - корпус трекбола можно таскать пальцем (если не закреплён замком).
 * Все настройки (курсоры, скин, поворот, чувствительность) — в HTML-панели trackball.html.
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
    private val onHide: () -> Unit,
    private val onSwitchToTouchpad: () -> Unit
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

    private var ctxMenu: View? = null

    private var held = false
    private var leftPending = false
    private var leftIgnore = false
    private val engageRun = Runnable {
        leftPending = false
        engage()
    }

    // Выделение рамкой (зажатая ПКМ > 100 мс + движение шара)
    private var rDown = false
    private var rDownTime = 0L
    private var rBoxSelecting = false
    private var rBoxStartX = 0f
    private var rBoxStartY = 0f

    private var rClickCount = 0
    private var rClickTimer: Runnable? = null
    private var tickAcc = 0f

    // Режим «панель»: окно временно увеличивается под меню/настройки
    private var panelOpen = false

    // Перетаскивание окна за корпус трекбола
    private var dragZone = false
    private var winDrag = false
    private var dsx = 0f
    private var dsy = 0f
    private var dox = 0
    private var doy = 0

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

    /** Обычный прямоугольник окна: x, y, ширина, высота. */
    private fun normalRect(): IntArray {
        val s = getSettings()
        val (sw, sh) = screen()
        val w = dp(s.ballWidthDp).coerceIn(min(dp(150), sw), sw)
        val h = dp(s.ballHeightDp).coerceIn(min(dp(170), sh), sh)
        val px = if (s.padX < 0) (sw - w) / 2 else s.padX
        val py = if (s.padY < 0) sh - h - dp(24) else s.padY
        return intArrayOf(px.coerceIn(0, sw - w), py.coerceIn(0, sh - h), w, h)
    }

    private fun fit() {
        val n = normalRect()
        if (!panelOpen) {
            lp.x = n[0]; lp.y = n[1]; lp.width = n[2]; lp.height = n[3]
            return
        }
        val (sw, sh) = screen()
        val w = min(sw, dp(360))
        val h = min((sh * 0.92f).toInt(), dp(620))
        lp.width = w
        lp.height = h
        lp.x = (n[0] + n[2] / 2 - w / 2).coerceIn(0, sw - w)
        lp.y = (n[1] + n[3] / 2 - h / 2).coerceIn(0, sh - h)
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
        panelOpen = false
        fit()
        bind(v)
        try {
            wm.addView(v, lp)
            root = v
        } catch (_: Exception) {
            Toast.makeText(ctx, "Нет разрешения «Поверх других окон»", Toast.LENGTH_SHORT).show()
        }
    }

    override fun hide() {
        handler.removeCallbacks(engageRun)
        leftPending = false
        if (held) release()
        if (rBoxSelecting) {
            cursor.endSelection()
            MouseAccessibilityService.instance?.dragEnd()
            rBoxSelecting = false
        }
        MouseAccessibilityService.instance?.scrollEnd()
        panelOpen = false
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
        refresh()
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
        @JavascriptInterface fun onRight(down: Int) { handler.post { if (down == 1) rightDown() else rightUp() } }
        @JavascriptInterface fun onRight() { handler.post { rightDown() } }
        @JavascriptInterface fun onWheelStart() { handler.post { wheelStart() } }
        @JavascriptInterface fun onWheel(dy: Double) { handler.post { wheelMove(dy.toFloat()) } }
        @JavascriptInterface fun onWheelEnd() { handler.post { wheelEnd() } }
        @JavascriptInterface fun dragZone(on: Int) { handler.post { dragZone = on == 1 } }
        @JavascriptInterface fun onPanel(open: Int) { handler.post { setPanel(open == 1) } }
        @JavascriptInterface fun onMenu(name: String) { handler.post { menuAction(name) } }

        /** Сохранённое состояние страницы (скин, поворот, курсор…) — JSON или пустая строка. */
        @JavascriptInterface fun getState(): String = getSettings().tbState

        /** Текущие значения, которыми управляет само приложение. */
        @JavascriptInterface fun getInfo(): String {
            val s = getSettings()
            val o = JSONObject()
            o.put("sens", s.sensitivity.toDouble())
            o.put("ws", s.ballWidthDp.toDouble() / BALL_BASE_DP)
            o.put("locked", s.padLocked)
            o.put("sel", rBoxSelecting)
            return o.toString()
        }

        @JavascriptInterface fun setState(json: String) { handler.post { applyState(json) } }
    }

    private fun move(dx: Float, dy: Float) {
        val k = getSettings().sensitivity
        cursor.moveBy(dx * k, dy * k)
        if (rDown) {
            val pressDuration = SystemClock.uptimeMillis() - rDownTime
            if (pressDuration >= 100) {
                if (!rBoxSelecting) {
                    rBoxSelecting = true
                    rClickTimer?.let { handler.removeCallbacks(it) }
                    rClickCount = 0
                    rBoxStartX = cursor.x
                    rBoxStartY = cursor.y
                    cursor.startSelection(rBoxStartX, rBoxStartY)
                    svc()?.boxSelectStart(rBoxStartX, rBoxStartY)
                    vibrate(25)
                    sounds.beep(320)
                } else {
                    cursor.updateSelection(cursor.x, cursor.y)
                    svc()?.boxSelectMove(cursor.x, cursor.y)
                }
            }
        } else if (held) {
            MouseAccessibilityService.instance?.dragMove(cursor.x, cursor.y)
        }
    }

    private fun ballDown() {
        closeContextMenu()
    }

    private fun ballUp() {
    }

    // ---------- Состояние HTML-панели ----------

    private fun applyState(json: String) {
        val s = getSettings()
        s.tbState = json
        try {
            val o = JSONObject(json)
            val cur = o.optJSONObject("cur")
            if (cur != null) {
                s.cursorShape = cur.optString("sh", "arrow")
                s.cursorFill = cur.optString("st", "fill")
                s.cursorGlow = cur.optInt("glow", 0) == 1
                s.cursorSizeDp = cur.optInt("sz", 40).coerceIn(12, 80)
                try {
                    s.cursorColor = Color.parseColor(cur.optString("col", "#35e0ff"))
                } catch (_: Exception) {
                }
            }
            if (o.has("sens")) s.sensitivity = o.optDouble("sens", 2.1).toFloat().coerceIn(0.3f, 8f)
            if (o.has("ws")) {
                val ws = o.optDouble("ws", 1.0).coerceIn(0.5, 1.6)
                s.ballWidthDp = (BALL_BASE_DP * ws).roundToInt()
                s.ballHeightDp = (s.ballWidthDp * BALL_ASPECT).roundToInt()
            }
        } catch (_: Exception) {
        }
        cursor.applySettings(s)
        fit()
        refresh()
        save()
    }

    private fun pushFlags() {
        val s = getSettings()
        web?.evaluateJavascript("window.setFlags&&setFlags(${s.padLocked},$rBoxSelecting)", null)
    }

    /** Окно временно увеличивается, пока открыто меню или настройки. */
    private fun setPanel(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        closeContextMenu()
        fit()
        refresh()
    }

    private fun menuAction(name: String) {
        sounds.beep(260)
        when (name) {
            "touchpad" -> {
                sounds.beep(420)
                onSwitchToTouchpad()
            }
            "lock" -> {
                val s = getSettings()
                s.padLocked = !s.padLocked
                save()
                pushFlags()
            }
            "files" -> FileManagerLauncher.open(ctx)
            "kb" -> onKeyboard()
            "appset" -> onSettings()
            "close" -> onHide()
            "refresh" -> svc()?.refreshPage()
            "undo" -> svc()?.undo()
            "redo" -> svc()?.redo()
            "cut" -> svc()?.cutSelection()
            "copy" -> svc()?.copySelection()
            "paste" -> svc()?.pasteFromBuffer()
            "delete" -> svc()?.deleteSelection()
            "all" -> svc()?.selectAllText()
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

    // ---------- ПКМ: зажатие > 100мс + движение = рамка, короткий тап = правый клик, дважды = меню ----------

    private fun rightDown() {
        closeContextMenu()
        rDown = true
        rDownTime = SystemClock.uptimeMillis()
        rBoxSelecting = false
        rBoxStartX = cursor.x
        rBoxStartY = cursor.y
    }

    private fun rightUp() {
        rDown = false
        if (rBoxSelecting) {
            rBoxSelecting = false
            rClickCount = 0
            rClickTimer?.let { handler.removeCallbacks(it) }
            cursor.endSelection()
            svc()?.boxSelectEnd(rBoxStartX, rBoxStartY, cursor.x, cursor.y)
            sounds.beep(180)
            openContextMenu()
        } else {
            val duration = SystemClock.uptimeMillis() - rDownTime
            if (held) {
                sounds.beep(160)
                openContextMenu()
            } else if (duration < 400) {
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
                    handler.postDelayed(runner, 280)
                }
            } else {
                rClickCount = 0
                sounds.beep(160)
                openContextMenu()
            }
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

    // ---------- Контекстное меню правки ----------

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
        act(R.id.ctx_refresh) { it.refreshPage() }
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
        ctxMenu = v.findViewById(R.id.pad_ctx_menu)
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
        w.settings.setSupportZoom(false)
        w.addJavascriptInterface(Bridge(), "Android")
        w.setOnLongClickListener { true }
        w.isLongClickable = false
        w.setOnTouchListener { _, e ->
            // Корпус трекбола (JS ставит dragZone) таскает всё окно; ничего не перехватываем у страницы
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragZone = false
                    winDrag = false
                    dsx = e.rawX; dsy = e.rawY; dox = lp.x; doy = lp.y
                }
                MotionEvent.ACTION_MOVE -> if (dragZone && !panelOpen && !getSettings().padLocked) {
                    if (!winDrag && hypot(e.rawX - dsx, e.rawY - dsy) > dp(6)) winDrag = true
                    if (winDrag) {
                        val (sw, sh) = screen()
                        lp.x = (dox + (e.rawX - dsx)).toInt().coerceIn(0, (sw - lp.width).coerceAtLeast(0))
                        lp.y = (doy + (e.rawY - dsy)).toInt().coerceIn(0, (sh - lp.height).coerceAtLeast(0))
                        refresh()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (winDrag) {
                        val s = getSettings()
                        s.padX = lp.x; s.padY = lp.y
                        save()
                    }
                    winDrag = false
                    dragZone = false
                }
            }
            false
        }
        w.loadUrl("file:///android_asset/trackball.html")
    }
}
