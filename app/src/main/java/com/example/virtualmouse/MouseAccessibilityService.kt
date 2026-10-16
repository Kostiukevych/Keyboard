package com.example.virtualmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class MouseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MouseAccessibilityService? = null
    }

    private var stroke: GestureDescription.StrokeDescription? = null
    private var lx = 0f
    private var ly = 0f
    private var tx = 0f
    private var ty = 0f
    private var busy = false
    private var ending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun screen(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun cx(v: Float) = v.coerceIn(0f, (screen().first - 1).toFloat())
    private fun cy(v: Float) = v.coerceIn(0f, (screen().second - 1).toFloat())

    private fun send(s: GestureDescription.StrokeDescription, cb: GestureResultCallback? = null): Boolean =
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(s).build(), cb, null)
        } catch (e: Exception) {
            false
        }

    private fun tap(x: Float, y: Float, duration: Long) {
        val p = Path().apply { moveTo(cx(x), cy(y)) }
        send(GestureDescription.StrokeDescription(p, 0, duration))
    }

    fun click(x: Float, y: Float) = tap(x, y, 50)

    fun rightClick(x: Float, y: Float) = tap(x, y, 600)

    fun scroll(x: Float, y: Float, dir: Int, distPx: Float) {
        val half = distPx / 2f * if (dir >= 0) 1 else -1
        val sy = cy(y - half)
        val ey = cy(y + half)
        if (sy == ey) return
        val p = Path().apply {
            moveTo(cx(x), sy)
            lineTo(cx(x), ey)
        }
        send(GestureDescription.StrokeDescription(p, 0, 250))
    }

    fun dragStart(x: Float, y: Float) {
        lx = cx(x); ly = cy(y)
        tx = lx; ty = ly
        ending = false
        val s = GestureDescription.StrokeDescription(Path().apply { moveTo(lx, ly) }, 0, 1, true)
        stroke = s
        dispatchStroke(s)
    }

    fun dragMove(x: Float, y: Float) {
        if (stroke == null) return
        tx = cx(x); ty = cy(y)
        next()
    }

    fun dragEnd() {
        if (stroke == null) return
        ending = true
        next()
    }

    private fun dispatchStroke(s: GestureDescription.StrokeDescription) {
        busy = true
        val ok = send(s, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                busy = false
                next()
            }

            override fun onCancelled(g: GestureDescription?) {
                busy = false
                stroke = null
                ending = false
            }
        })
        if (!ok) {
            busy = false
            stroke = null
            ending = false
        }
    }

    private fun next() {
        val s = stroke ?: return
        if (busy) return
        if (!ending && tx == lx && ty == ly) return
        val p = Path().apply {
            moveTo(lx, ly)
            lineTo(tx, ty)
        }
        lx = tx; ly = ty
        val n = s.continueStroke(p, 0, if (ending) 1 else 16, !ending)
        stroke = if (ending) null else n
        ending = false
        dispatchStroke(n)
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun recents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }
}
