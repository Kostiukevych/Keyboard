package com.example.virtualmouse

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.os.Build
import android.util.DisplayMetrics
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class TouchpadWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val cursor: CursorOverlay,
    private val getSettings: () -> CursorSettings,
    private val save: () -> Unit,
    private val onSettings: () -> Unit,
    private val onKeyboard: () -> Unit,
    private val onHide: () -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private val lp = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var bar: ProgressBar? = null
    private var leftLabel: TextView? = null
    private var lockBtn: TextView? = null
    private var held = false
    private var charging = false
    private var chargeStart = 0L

    val isShown: Boolean get() = root != null

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
        } catch (e: Exception) {
        }
    }

    fun show() {
        if (root != null) return
        val v = LayoutInflater.from(ContextThemeWrapper(ctx, R.style.Theme_VirtualMouse))
            .inflate(R.layout.overlay_touchpad, null)
        fit()
        bind(v)
        try {
            wm.addView(v, lp)
            root = v
        } catch (e: Exception) {
            Toast.makeText(ctx, "Нет разрешения «Поверх других окон»", Toast.LENGTH_SHORT).show()
        }
    }

    fun hide() {
        stopCharge()
        if (held) release()
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        root = null
    }

    fun applySettings() {
        if (root == null) return
        fit()
        lockBtn?.text = if (getSettings().padLocked) "🔒" else "🔓"
        refresh()
    }

    fun destroy() {
        hide()
    }

    private fun engage() {
        val a = svc()
        if (a == null) {
            bar?.progress = 0
            return
        }
        a.dragStart(cursor.x, cursor.y)
        held = true
        leftLabel?.text = "ЛКМ ●"
        root?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun release() {
        MouseAccessibilityService.instance?.dragEnd()
        held = false
        leftLabel?.text = "ЛКМ"
        bar?.progress = 0
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = ((SystemClock.uptimeMillis() - chargeStart) / 10).toInt()
            if (p >= 100) {
                charging = false
                bar?.progress = 100
                engage()
            } else {
                bar?.progress = p
                handler.postDelayed(this, 40)
            }
        }
    }

    private fun stopCharge() {
        charging = false
        handler.removeCallbacks(tick)
        if (!held) bar?.progress = 0
    }

    private fun bind(v: View) {
        bar = v.findViewById(R.id.pad_charge)
        leftLabel = v.findViewById(R.id.pad_left_label)
        lockBtn = v.findViewById(R.id.pad_btn_lock)
        lockBtn?.text = if (getSettings().padLocked) "🔒" else "🔓"

        lockBtn?.setOnClickListener {
            val s = getSettings()
            s.padLocked = !s.padLocked
            lockBtn?.text = if (s.padLocked) "🔒" else "🔓"
            save()
        }
        v.findViewById<View>(R.id.pad_btn_kb).setOnClickListener { onKeyboard() }
        v.findViewById<View>(R.id.pad_btn_settings).setOnClickListener { onSettings() }
        v.findViewById<View>(R.id.pad_btn_close).setOnClickListener { onHide() }
        v.findViewById<View>(R.id.pad_btn_right).setOnClickListener {
            svc()?.rightClick(cursor.x, cursor.y)
        }

        bindTitle(v.findViewById(R.id.pad_title))
        bindResize(v.findViewById(R.id.pad_resize))
        bindLeft(v.findViewById(R.id.pad_btn_left))
        bindWheel(v.findViewById(R.id.pad_wheel))
        bindMove(v.findViewById(R.id.pad_move_area))
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
                    v.alpha = 0.6f
                    if (held) {
                        release()
                        ignoreUp = true
                    } else {
                        ignoreUp = false
                        charging = true
                        chargeStart = SystemClock.uptimeMillis()
                        handler.post(tick)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (!ignoreUp && charging) {
                        stopCharge()
                        svc()?.click(cursor.x, cursor.y)
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

    private fun bindWheel(w: View) {
        var last = 0f
        var acc = 0f
        w.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    last = e.rawY; acc = 0f
                    svc()
                }
                MotionEvent.ACTION_MOVE -> {
                    acc += e.rawY - last
                    last = e.rawY
                    val step = dp(18).toFloat()
                    while (abs(acc) >= step) {
                        val dir = if (acc > 0) -1 else 1
                        acc -= step * -dir
                        MouseAccessibilityService.instance
                            ?.scroll(cursor.x, cursor.y, dir, dp(120).toFloat())
                    }
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
                    lx = e.rawX; ly = e.rawY; sx = lx; sy = ly
                    t0 = e.eventTime; far = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val k = getSettings().sensitivity
                    cursor.moveBy((e.rawX - lx) * k, (e.rawY - ly) * k)
                    lx = e.rawX; ly = e.rawY
                    if (hypot(lx - sx, ly - sy) > dp(10)) far = true
                    if (held) MouseAccessibilityService.instance?.dragMove(cursor.x, cursor.y)
                }
                MotionEvent.ACTION_UP -> {
                    if (!far && !held && e.eventTime - t0 < 250) svc()?.click(cursor.x, cursor.y)
                }
            }
            true
        }
    }
}
