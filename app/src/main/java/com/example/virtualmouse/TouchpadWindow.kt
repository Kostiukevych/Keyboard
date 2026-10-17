package com.example.virtualmouse

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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Во сколько раз страница едет быстрее пальца на колёсике (больше — быстрее). */
private const val WHEEL_GAIN = 2.2f

class TouchpadWindow(
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
    private val lp = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var leftFill: View? = null
    private var leftHz: View? = null
    private var rightHz: View? = null
    private var lockBtn: TextView? = null
    private var wheelView: WheelView? = null
    private var ctxMenu: View? = null
    private var selBtn: TextView? = null

    // Режим выделения текста: касание-удержание + движение = выделение, как зажатая ЛКМ над текстом
    private var selectMode = false
    private var selDrag = false

    private var held = false
    private var charging = false
    private var chargeStart = 0L

    private var rClickCount = 0
    private var rClickTimer: Runnable? = null
    private var lastWheelTick = 0f

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
        val w = dp(s.padWidthDp).coerceIn(min(dp(220), sw), sw)
        val h = dp(s.padHeightDp).coerceIn(min(dp(160), sh), sh)
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
            .inflate(R.layout.overlay_touchpad, null)
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
        stopCharge()
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
    }

    override fun applySettings() {
        if (root == null) return
        fit()
        lockBtn?.text = if (getSettings().padLocked) "🔒" else "🔓"
        applyColors()
        refresh()
    }

    private fun applyColors() {
        val s = getSettings()
        wheelView?.accentColor = s.padColor
        leftHz?.setBackgroundColor(if (held) Color.WHITE else s.padColor)
        rightHz?.setBackgroundColor(s.padColor)
        root?.findViewById<TextView>(R.id.pad_title)?.setTextColor(s.padColor)
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

    private fun engage() {
        val a = svc()
        if (a == null) {
            leftFill?.layoutParams?.width = 0
            leftFill?.requestLayout()
            return
        }
        a.dragStart(cursor.x, cursor.y)
        held = true
        leftHz?.setBackgroundColor(Color.WHITE)
        sounds.beep(500)
        vibrate(40)
    }

    private fun release() {
        MouseAccessibilityService.instance?.dragEnd()
        held = false
        leftHz?.setBackgroundColor(getSettings().padColor)
        leftFill?.layoutParams?.width = 0
        leftFill?.requestLayout()
        sounds.beep(180)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!charging) return
            val btn = root?.findViewById<View>(R.id.pad_btn_left) ?: return
            val totalW = btn.width
            val elapsed = SystemClock.uptimeMillis() - chargeStart
            val p = (elapsed.toFloat() / 1000f).coerceIn(0f, 1f)
            leftFill?.layoutParams?.width = (totalW * p).toInt()
            leftFill?.requestLayout()

            if (p >= 1f) {
                charging = false
                engage()
            } else {
                handler.postDelayed(this, 16)
            }
        }
    }

    private fun stopCharge() {
        charging = false
        handler.removeCallbacks(tick)
        if (!held) {
            leftFill?.layoutParams?.width = 0
            leftFill?.requestLayout()
        }
    }

    private fun bind(v: View) {
        leftFill = v.findViewById(R.id.pad_left_fill)
        leftHz = v.findViewById(R.id.pad_left_hz)
        rightHz = v.findViewById(R.id.pad_right_hz)
        lockBtn = v.findViewById(R.id.pad_btn_lock)
        wheelView = v.findViewById(R.id.pad_wheel_view)
        ctxMenu = v.findViewById(R.id.pad_ctx_menu)
        selBtn = v.findViewById(R.id.pad_btn_sel)

        selBtn?.isSelected = selectMode
        selBtn?.setOnClickListener {
            selectMode = !selectMode
            selBtn?.isSelected = selectMode
            sounds.beep(if (selectMode) 420 else 240)
            if (selectMode) Toast.makeText(ctx, "Выделение: тяните по тачпаду (тап — слово)", Toast.LENGTH_SHORT).show()
        }
        v.findViewById<View>(R.id.pad_btn_files)?.setOnClickListener {
            sounds.beep(340)
            FileManagerLauncher.open(ctx)
        }

        lockBtn?.text = if (getSettings().padLocked) "🔒" else "🔓"

        lockBtn?.setOnClickListener {
            val s = getSettings()
            s.padLocked = !s.padLocked
            lockBtn?.text = if (s.padLocked) "🔒" else "🔓"
            sounds.beep(340)
            save()
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
        bindLeft(v.findViewById(R.id.pad_btn_left))
        bindWheel(v.findViewById(R.id.pad_wheel_box))
        bindRight(v.findViewById(R.id.pad_btn_right))
        bindMove(v.findViewById(R.id.pad_move_area))
        bindContextMenu(v)
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
                    lp.height = (h0 + (e.rawY - sy)).toInt().coerceIn(min(dp(160), sh), sh)
                    lp.x = lp.x.coerceIn(0, sw - lp.width)
                    lp.y = lp.y.coerceIn(0, sh - lp.height)
                    refresh()
                }
                MotionEvent.ACTION_UP -> {
                    val s = getSettings()
                    s.padWidthDp = (lp.width / density).roundToInt()
                    s.padHeightDp = (lp.height / density).roundToInt()
                    s.padX = lp.x; s.padY = lp.y
                    save()
                }
            }
            true
        }
    }

    private fun bindLeft(b: View) {
        var ignoreUp = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    closeContextMenu()
                    v.alpha = 0.8f
                    if (held) {
                        release()
                        ignoreUp = true
                    } else {
                        ignoreUp = false
                        charging = true
                        chargeStart = SystemClock.uptimeMillis()
                        sounds.beep(220)
                        handler.post(tick)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (!ignoreUp && charging) {
                        stopCharge()
                        svc()?.click(cursor.x, cursor.y)
                        sounds.beep(180)
                    }
                    ignoreUp = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    stopCharge()
                }
            }
            true
        }
    }

    private fun bindRight(b: View) {
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.8f
                    if (held) {
                        sounds.beep(160)
                        openContextMenu()
                    } else {
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
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                }
            }
            true
        }
    }

    private fun openContextMenu() {
        ctxMenu?.visibility = View.VISIBLE
        ctxMenu?.animate()?.alpha(1f)?.setDuration(120)?.start()
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

    private fun bindWheel(w: View) {
        var last = 0f
        var tickAcc = 0f
        w.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    closeContextMenu()
                    last = e.rawY
                    tickAcc = 0f
                    lastWheelTick = 0f
                    // Палец «ставится» на позицию курсора и дальше едет вместе с колёсиком
                    svc()?.scrollBegin(cursor.x, cursor.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - last
                    last = e.rawY
                    tickAcc += dy
                    wheelView?.setScrollOffset(dy)

                    if (abs(tickAcc - lastWheelTick) >= dp(8)) {
                        sounds.wheelTick()
                        lastWheelTick = tickAcc
                    }
                    MouseAccessibilityService.instance?.scrollBy(dy, WHEEL_GAIN)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    MouseAccessibilityService.instance?.scrollEnd()
                }
            }
            true
        }
    }

    private fun bindMove(m: View) {
        var lx = 0f
        var ly = 0f
        var sx = 0f
        var sy = 0f
        var t0 = 0L
        var far = false
        m.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    closeContextMenu()
                    lx = e.rawX; ly = e.rawY; sx = lx; sy = ly
                    t0 = e.eventTime; far = false
                    cursor.setPressed(true)
                    if (selectMode && !held) {
                        // Долгое нажатие на месте курсора начинает выделение, движение тянет его
                        val a = svc()
                        if (a != null) {
                            a.dragStart(cursor.x, cursor.y, 600)
                            selDrag = true
                            vibrate(25)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val k = getSettings().sensitivity
                    cursor.moveBy((e.rawX - lx) * k, (e.rawY - ly) * k)
                    lx = e.rawX; ly = e.rawY
                    if (hypot(lx - sx, ly - sy) > dp(10)) far = true
                    if (held || selDrag) MouseAccessibilityService.instance?.dragMove(cursor.x, cursor.y)
                }
                MotionEvent.ACTION_UP -> {
                    cursor.setPressed(false)
                    if (selDrag) {
                        MouseAccessibilityService.instance?.dragEnd()
                        selDrag = false
                        selectMode = false
                        selBtn?.isSelected = false
                        sounds.beep(180)
                    } else if (!far && !held && e.eventTime - t0 < 250) {
                        svc()?.click(cursor.x, cursor.y)
                        sounds.beep(180)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    cursor.setPressed(false)
                    if (selDrag) {
                        MouseAccessibilityService.instance?.dragEnd()
                        selDrag = false
                        selectMode = false
                        selBtn?.isSelected = false
                    }
                }
            }
            true
        }
    }
}
