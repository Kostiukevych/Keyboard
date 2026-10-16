package com.example.virtualmouse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

class CursorOverlay(private val ctx: Context, private val wm: WindowManager) {

    var x: Float = 0f
    var y: Float = 0f

    private var sw = 0
    private var sh = 0
    private var shown = false
    private var settings = CursorSettings()
    private var view: CursorView? = null
    private var pad = 0
    private var hotX = 0
    private var hotY = 0
    private val density = ctx.resources.displayMetrics.density

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            sw = m.widthPixels; sh = m.heightPixels
        }
        x = sw / 2f
        y = sh / 2f
        rebuild()
    }

    private inner class CursorView(c: Context) : View(c) {
        var icon: Bitmap? = null
        var glow: Bitmap? = null
        var total = 1
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(total, total)

        override fun onDraw(c: Canvas) {
            glow?.let {
                c.drawBitmap(it, 0f, 0f, glowPaint)
                c.drawBitmap(it, 0f, 0f, glowPaint)
            }
            icon?.let { c.drawBitmap(it, 0f, 0f, null) }
        }
    }

    private fun rebuild() {
        val s = settings
        val size = (s.cursorSizeDp * density).toInt().coerceAtLeast(8)
        pad = (size * 0.35f).toInt()
        val total = size + pad * 2
        val res = when (s.cursorStyle) {
            1 -> R.drawable.cursor_cross
            2 -> R.drawable.cursor_dot
            3 -> R.drawable.cursor_hand
            else -> R.drawable.cursor_arrow
        }
        val full = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(ctx, res)?.mutate()?.let {
            it.setBounds(pad, pad, pad + size, pad + size)
            it.setTint(s.cursorColor)
            it.draw(Canvas(full))
        }
        val blur = Paint().apply { maskFilter = BlurMaskFilter(pad * 0.6f, BlurMaskFilter.Blur.NORMAL) }
        val glow = full.extractAlpha(blur, null)
        if (s.cursorStyle == 0) {
            hotX = pad; hotY = pad
        } else {
            hotX = pad + size / 2; hotY = pad + size / 2
        }
        val v = view ?: CursorView(ctx).also { view = it }
        v.icon?.recycle()
        v.glow?.recycle()
        v.icon = full
        v.glow = glow
        v.total = total
        v.glowPaint.color = s.glowColor
        lp.width = total
        lp.height = total
        v.requestLayout()
        v.invalidate()
        update()
    }

    private fun update() {
        lp.x = (x - hotX).toInt()
        lp.y = (y - hotY).toInt()
        if (shown) {
            try {
                wm.updateViewLayout(view, lp)
            } catch (e: Exception) {
            }
        }
    }

    fun show() {
        if (shown) return
        try {
            update()
            wm.addView(view, lp)
            shown = true
        } catch (e: Exception) {
        }
    }

    fun hide() {
        if (!shown) return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
        }
        shown = false
    }

    fun moveBy(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, (sw - 1).toFloat())
        y = (y + dy).coerceIn(0f, (sh - 1).toFloat())
        update()
    }

    fun applySettings(s: CursorSettings) {
        settings = s
        rebuild()
    }

    fun setScreen(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        sw = w
        sh = h
        x = x.coerceIn(0f, (sw - 1).toFloat())
        y = y.coerceIn(0f, (sh - 1).toFloat())
        update()
    }

    fun bringToFront() {
        if (!shown) return
        try {
            wm.removeView(view)
            wm.addView(view, lp)
        } catch (e: Exception) {
            shown = false
        }
    }

    fun destroy() {
        hide()
        view?.icon?.recycle()
        view?.glow?.recycle()
        view = null
    }
}
