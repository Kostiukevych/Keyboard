package com.example.virtualmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Колёсико точь-в-точь как в трекболе (trackball.html): металлическая капсула,
 * внутри тёмный паз с неоновой обводкой и вращающийся цилиндр из 20 серебристых рёбер,
 * которые сжимаются по косинусу и гаснут к краям.
 * Модель в «юнитах» SVG: капсула 28x78, паз 20x70, радиус цилиндра 35.
 */
class WheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val RIBS = 20
        const val WR = 35f
        const val TWO_PI = 6.2831855f
    }

    private var angle = 0f
    private var unit = 1f

    var accentColor = Color.parseColor("#2E7BFF")
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AAB5BF")
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outer = RectF()
    private val slot = RectF()
    private val rib = RectF()
    private val slotPath = Path()

    /** Прокрутка пальцем на dy пикселей: вращает цилиндр, как wheelMove() в трекболе. */
    fun setScrollOffset(dy: Float) {
        angle = (angle + dy / (max(unit, 0.0001f) * WR)) % TWO_PI
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        unit = min(w / 28f, h / 78f)
        val s = unit
        val cx = w / 2f
        val cy = h / 2f

        // 1. металлическая капсула
        outer.set(cx - 14f * s, cy - 39f * s, cx + 14f * s, cy + 39f * s)
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#AEB8C1")
        canvas.drawRoundRect(outer, 14f * s, 14f * s, fillPaint)
        strokePaint.strokeWidth = max(1f, s)
        strokePaint.color = Color.parseColor("#6C7782")
        canvas.drawRoundRect(outer, 14f * s, 14f * s, strokePaint)

        // 2. тёмный паз с неоновой обводкой
        slot.set(cx - 10f * s, cy - 35f * s, cx + 10f * s, cy + 35f * s)
        fillPaint.color = Color.parseColor("#07090C")
        canvas.drawRoundRect(slot, 10f * s, 10f * s, fillPaint)
        strokePaint.strokeWidth = max(1f, 1.5f * s)
        strokePaint.color = Color.argb(153, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawRoundRect(slot, 10f * s, 10f * s, strokePaint)

        // 3. рёбра вращающегося цилиндра внутри паза
        canvas.save()
        slotPath.rewind()
        slotPath.addRoundRect(slot, 10f * s, 10f * s, Path.Direction.CW)
        canvas.clipPath(slotPath)
        for (j in 0 until RIBS) {
            val th = angle + j * TWO_PI / RIBS
            val cs = cos(th)
            if (cs <= 0.04f) continue
            val rh = 5.6f * cs * s
            val y = cy + WR * sin(th) * s
            rib.set(slot.left, y - rh / 2f, slot.right, y + rh / 2f)
            ribPaint.alpha = ((0.35f + 0.65f * cs) * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(rib, ribPaint)
        }

        // 4. объёмная тень поверх рёбер (верх и низ темнее, середина блестит)
        shadePaint.shader = LinearGradient(
            0f, slot.top, 0f, slot.bottom,
            intArrayOf(
                Color.argb(217, 0, 0, 0), Color.argb(0, 0, 0, 0),
                Color.argb(41, 255, 255, 255), Color.argb(0, 0, 0, 0), Color.argb(217, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(slot, shadePaint)
        canvas.restore()
    }
}
