package com.example.virtualmouse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

/** Формы курсора из HTML Orbit X (viewBox 32x32). x,y — «горячая точка». */
internal class CursorShape(val d: String, val x: Float, val y: Float, val line: Boolean, val miter: Boolean)

internal val CURSOR_SHAPES: Map<String, CursorShape> = mapOf(
    "arrow" to CursorShape("M3 2L3 23L8.5 18L12.5 27L16 25.5L12 16.8L19.5 16.8Z", 3f, 2f, false, false),
    "sharp" to CursorShape("M10 7.7L23.4 20.8H15.7L10 26.1Z", 10f, 7.7f, false, false),
    "hand" to CursorShape("M11 1L15 1L15 11L19 11L19 13L23 13L23 15L27 15L27 25L25 25L25 31L13 31L13 29L9 25L5 21L5 19L3 19L3 17L7 17L9 19L9 21L11 21Z", 12f, 1f, false, true),
    "dot" to CursorShape("M9 16a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z", 16f, 16f, false, false),
    "ring" to CursorShape("M7 16a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z", 16f, 16f, true, false),
    "cross" to CursorShape("M16 3V12M16 20V29M3 16H12M20 16H29", 16f, 16f, true, false),
    "ibeam" to CursorShape("M11 4H21M16 4V28M11 28H21", 16f, 16f, true, false),
    "dia" to CursorShape("M16 4L28 16L16 28L4 16Z", 16f, 16f, false, false),
    "tri" to CursorShape("M16 4L28 27H4Z", 16f, 16f, false, false)
)

/**
 * Оверлей курсора мыши.
 * Поддерживает старые стили (стрелка/прицел/точка/рука) и 9 форм из трекбола Orbit X
 * (заливка / тёмный / контур, цвет, неоновое свечение).
 * Перемещение объединяется по кадрам (Choreographer): окно двигается не чаще одного раза за кадр,
 * поэтому курсор не «лагает» при частых событиях касания.
 * Также отображает неоновую рамку выделения при зажатой правой кнопке.
 */
class CursorOverlay(private val ctx: Context, private val wm: WindowManager) {

    var x: Float = 0f
    var y: Float = 0f

    private var sw = 0
    private var sh = 0
    private var shown = false
    private var settings = CursorSettings()
    private var view: CursorView? = null
    private var hotX = 0
    private var hotY = 0
    private val density = ctx.resources.displayMetrics.density

    private var framePending = false
    private val frameCb = Choreographer.FrameCallback {
        framePending = false
        pushLayout()
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // Оверлей рамки выделения
    private var selectionView: SelectionBoxView? = null
    private var selectionShown = false
    private val selectionLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
        var total = 1

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(total, total)

        override fun onDraw(c: Canvas) {
            icon?.let { c.drawBitmap(it, 0f, 0f, null) }
        }
    }

    /**
     * Рамка захвата как у физической мыши: полупрозрачная заливка, неоновая обводка,
     * белый пунктир и угловые маркеры. held = кнопку отпустили, рамка осталась как «выделение».
     */
    private inner class SelectionBoxView(c: Context) : View(c) {
        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        var sx = 0f
        var sy = 0f
        var ex = 0f
        var ey = 0f
        var active = false
        var held = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val cornerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            if (!active) return
            val l = minOf(sx, ex)
            val t = minOf(sy, ey)
            val r = maxOf(sx, ex)
            val b = maxOf(sy, ey)
            if (r - l < 1f && b - t < 1f) return

            val color = settings.padColor
            // После отпускания кнопки рамка чуть ярче заливается: видно, что захват зафиксирован
            fillPaint.color = ColorUtils.setAlphaComponent(color, if (held) 70 else 45)
            strokePaint.color = color
            strokePaint.strokeWidth = 2f * density

            rect.set(l, t, r, b)
            val radius = 3f * density

            c.drawRoundRect(rect, radius, radius, fillPaint)

            glowPaint.color = ColorUtils.setAlphaComponent(color, 90)
            glowPaint.strokeWidth = 4f * density
            glowPaint.maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(rect, radius, radius, glowPaint)

            c.drawRoundRect(rect, radius, radius, strokePaint)

            // Белый пунктир поверх обводки — классический «маркер» выделения
            dashPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 220)
            dashPaint.strokeWidth = 1.2f * density
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
            c.drawRoundRect(rect, radius, radius, dashPaint)

            // Угловые маркеры: белый квадрат с цветной серединой
            val h = 3.5f * density
            val inset = 1.2f * density
            for (px in floatArrayOf(l, r)) {
                for (py in floatArrayOf(t, b)) {
                    cornerFill.color = Color.WHITE
                    c.drawRect(px - h, py - h, px + h, py + h, cornerFill)
                    cornerFill.color = color
                    c.drawRect(px - h + inset, py - h + inset, px + h - inset, py + h - inset, cornerFill)
                }
            }
        }
    }

    private fun strokePaint(color: Int, width: Float, miter: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = if (miter) Paint.Join.MITER else Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    /** Рисует форму из HTML (SVG-путь 32x32) в битмап с запасом под свечение. */
    private fun renderShape(shape: CursorShape, size: Int, s: CursorSettings): Bitmap {
        val pad = (10 * density).toInt()
        val tot = size + pad * 2
        val bmp = Bitmap.createBitmap(tot, tot, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val f = size / 32f

        val raw = PathParser.createPathFromPathData(shape.d)
        val path = Path()
        val m = Matrix()
        m.setScale(f, f)
        m.postTranslate(pad.toFloat(), pad.toFloat())
        raw.transform(m, path)

        val col = s.cursorColor
        val lineMode = shape.line || s.cursorFill == "line"

        // Неоновое свечение: два слоя размытия, как drop-shadow(0 0 3px) + drop-shadow(0 0 8px)
        if (s.cursorGlow) {
            for (r in floatArrayOf(8f, 3f)) {
                val g = strokePaint(col, (if (lineMode) 2.6f else 2f) * f, shape.miter).apply {
                    maskFilter = BlurMaskFilter(r * density, BlurMaskFilter.Blur.NORMAL)
                }
                if (!lineMode) canvas.drawPath(path, fillPaint(col).apply { maskFilter = g.maskFilter })
                canvas.drawPath(path, g)
            }
        }

        val outline = Color.parseColor("#06232B")
        if (lineMode) {
            canvas.drawPath(path, strokePaint(outline, 5.5f * f, shape.miter))
            canvas.drawPath(path, strokePaint(col, 2.6f * f, shape.miter))
        } else if (s.cursorFill == "dark") {
            canvas.drawPath(path, fillPaint(Color.parseColor("#111111")))
            canvas.drawPath(path, strokePaint(col, 2.2f * f, shape.miter))
        } else {
            canvas.drawPath(path, fillPaint(col))
            canvas.drawPath(path, strokePaint(outline, 1.6f * f, shape.miter))
        }

        hotX = (pad + shape.x * f).toInt()
        hotY = (pad + shape.y * f).toInt()
        return bmp
    }

    /** Старые стили курсора (векторные ресурсы). */
    private fun renderLegacy(size: Int, s: CursorSettings): Bitmap {
        val res = when (s.cursorStyle) {
            1 -> R.drawable.cursor_cross
            2 -> R.drawable.cursor_dot
            3 -> R.drawable.cursor_hand
            else -> R.drawable.cursor_arrow
        }
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(ctx, res)?.mutate()?.let {
            it.setBounds(0, 0, size, size)
            it.setTint(s.cursorColor)
            it.draw(Canvas(full))
        }
        if (s.cursorStyle == 0) {
            hotX = (size * 0.225f).toInt()
            hotY = (size * 0.194f).toInt()
        } else {
            hotX = size / 2
            hotY = size / 2
        }
        return full
    }

    private fun rebuild() {
        val s = settings
        val size = (s.cursorSizeDp * density).toInt().coerceAtLeast(8)
        val shape = if (s.cursorShape.isNotEmpty()) CURSOR_SHAPES[s.cursorShape] else null
        val bmp = if (shape != null) renderShape(shape, size, s) else renderLegacy(size, s)

        val v = view ?: CursorView(ctx).also { view = it }
        v.icon?.recycle()
        v.icon = bmp
        v.total = bmp.width
        v.pivotX = hotX.toFloat()
        v.pivotY = hotY.toFloat()
        lp.width = bmp.width
        lp.height = bmp.height
        v.requestLayout()
        v.invalidate()
        update()
        pushLayout()
    }

    /** Запоминает позицию и планирует единственное обновление окна на ближайший кадр. */
    private fun update() {
        lp.x = (x - hotX).toInt()
        lp.y = (y - hotY).toInt()
        if (shown && !framePending) {
            framePending = true
            Choreographer.getInstance().postFrameCallback(frameCb)
        }
    }

    private fun pushLayout() {
        if (!shown) return
        try {
            wm.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    fun setPressed(pressed: Boolean) {
        val v = view ?: return
        val targetScale = if (pressed) 0.88f else 1.0f
        v.animate().scaleX(targetScale).scaleY(targetScale).setDuration(80).start()
    }

    fun show() {
        if (shown) return
        try {
            lp.x = (x - hotX).toInt()
            lp.y = (y - hotY).toInt()
            wm.addView(view, lp)
            shown = true
        } catch (_: Exception) {
        }
    }

    fun hide() {
        if (!shown) return
        Choreographer.getInstance().removeFrameCallback(frameCb)
        framePending = false
        try {
            wm.removeView(view)
        } catch (_: Exception) {
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
        selectionView?.invalidate()
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
        } catch (_: Exception) {
            shown = false
        }
    }

    // ---------- Рамка выделения ----------

    fun startSelection(startX: Float, startY: Float) {
        val sv = selectionView ?: SelectionBoxView(ctx).also { selectionView = it }
        sv.sx = startX
        sv.sy = startY
        sv.ex = startX
        sv.ey = startY
        sv.active = true
        sv.held = false
        if (!selectionShown) {
            try {
                wm.addView(sv, selectionLp)
                selectionShown = true
            } catch (_: Exception) {
            }
        }
        sv.invalidate()
    }

    fun updateSelection(curX: Float, curY: Float) {
        val sv = selectionView ?: return
        sv.ex = curX
        sv.ey = curY
        sv.invalidate()
    }

    /** Рамка сейчас на экране (рисуется или удерживается после отпускания кнопки). */
    val isSelectionActive: Boolean get() = selectionShown && selectionView?.active == true

    /**
     * Кнопку мыши отпустили: рамка остаётся на экране как выделение, пока её не уберут
     * (endSelection) — например, новым кликом или действием из меню.
     */
    fun holdSelection() {
        val sv = selectionView ?: return
        if (!sv.active) return
        sv.held = true
        sv.invalidate()
    }

    /** Текущая рамка в экранных координатах (left <= right, top <= bottom) или null, если её нет. */
    fun selectionRect(): RectF? {
        val sv = selectionView ?: return null
        if (!sv.active) return null
        return RectF(minOf(sv.sx, sv.ex), minOf(sv.sy, sv.ey), maxOf(sv.sx, sv.ex), maxOf(sv.sy, sv.ey))
    }

    fun endSelection() {
        val sv = selectionView ?: return
        sv.active = false
        sv.held = false
        if (selectionShown) {
            try {
                wm.removeView(sv)
            } catch (_: Exception) {
            }
            selectionShown = false
        }
        sv.invalidate()
    }

    fun destroy() {
        endSelection()
        hide()
        view?.icon?.recycle()
        view = null
        selectionView = null
    }
}
