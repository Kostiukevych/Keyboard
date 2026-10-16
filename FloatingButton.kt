package com.example.virtualmouse

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class FloatingButton(
    private val ctx: Context,
    private val wm: WindowManager,
    private val getSettings: () -> CursorSettings,
    private val save: () -> Unit,
    private val onTap: () -> Unit,
    private val onLongTap: () -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private val size = (48 * density).toInt()
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var anim: ValueAnimator? = null
    private val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

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

    private fun update() {
        val v = view ?: return
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
        }
    }

    fun show() {
        if (view != null) return
        val (sw, sh) = screen()
        val s = getSettings()
        lp.x = (if (s.buttonX < 0) sw - size else s.buttonX).coerceIn(0, sw - size)
        lp.y = (if (s.buttonY < 0) sh / 2 else s.buttonY).coerceIn(0, sh - size)
        val v = TextView(ctx).apply {
            text = "🖱"
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_float_button)
        }
        bind(v)
        try {
            wm.addView(v, lp)
            view = v
        } catch (e: Exception) {
        }
    }

    fun hide() {
        anim?.cancel()
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
    }

    fun destroy() {
        hide()
    }

    private fun bind(v: View) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        var longFired = false
        val longRun = Runnable {
            longFired = true
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongTap()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    anim?.cancel()
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                    moved = false; longFired = false
                    handler.postDelayed(longRun, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!moved && (abs(dx) > 8 * density || abs(dy) > 8 * density)) {
                        moved = true
                        handler.removeCallbacks(longRun)
                    }
                    if (moved) {
                        val (sw, sh) = screen()
                        lp.x = (ox + dx).toInt().coerceIn(0, sw - size)
                        lp.y = (oy + dy).toInt().coerceIn(0, sh - size)
                        update()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRun)
                    if (moved) snap() else if (!longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRun)
            }
            true
        }
    }

    private fun snap() {
        val (sw, _) = screen()
        val target = if (lp.x + size / 2 < sw / 2) 0 else sw - size
        val from = lp.x
        anim = ValueAnimator.ofInt(from, target).apply {
            duration = 180
            addUpdateListener {
                lp.x = it.animatedValue as Int
                update()
            }
            start()
        }
        val s = getSettings()
        s.buttonX = target
        s.buttonY = lp.y
        save()
    }
}
