package com.example.virtualmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * 3D-колёсико мыши в стиле HTML:
 * Цилиндрический градиент, акцентная рамка и анимированные горизонтальные
 * насечки, прокручивающиеся при скролле.
 */
class WheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private var scrollOffset = 0f
    var accentColor = Color.parseColor("#2E7BFF")
        set(value) {
            field = value
            invalidate()
        }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    fun setScrollOffset(dy: Float) {
        scrollOffset = (scrollOffset + dy) % (12f * density)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        bounds.set(borderPaint.strokeWidth / 2, borderPaint.strokeWidth / 2,
            w - borderPaint.strokeWidth / 2, h - borderPaint.strokeWidth / 2)
        val cornerRadius = w / 2f

        // 1. Цилиндрический градиент корпуса (#010203 -> key_top -> #010203)
        bodyPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(Color.parseColor("#020406"), Color.parseColor("#24344E"), Color.parseColor("#020406")),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, bodyPaint)

        // 2. Отрисовка насечек (горизонтальных полосок с шагом 12dp)
        canvas.save()
        canvas.clipRect(bounds)

        val grooveStep = 10f * density
        val grooveHeight = 4f * density
        groovePaint.color = Color.argb(120, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        var y = (scrollOffset % grooveStep) - grooveStep
        while (y < h + grooveStep) {
            canvas.drawRect(2f * density, y, w - 2f * density, y + grooveHeight, groovePaint)
            y += grooveStep
        }

        // 3. Боковые тени для объема
        shadowPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(Color.argb(180, 0, 0, 0), Color.TRANSPARENT, Color.argb(180, 0, 0, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, shadowPaint)
        canvas.restore()

        // 4. Акцентная рамка
        borderPaint.color = accentColor
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
    }
}
