package com.example.virtualmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

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
    private val ui = Handler(Looper.getMainLooper())

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
        } catch (_: Exception) {
            false
        }

    private fun tap(x: Float, y: Float, duration: Long) {
        val p = Path().apply { moveTo(cx(x), cy(y)) }
        send(GestureDescription.StrokeDescription(p, 0, duration))
    }

    fun click(x: Float, y: Float) = tap(x, y, 50)

    fun rightClick(x: Float, y: Float) = tap(x, y, 600)

    /**
     * Надежный жест скролла:
     * dir > 0: прокрутка страницы вниз (свайп снизу вверх)
     * dir < 0: прокрутка страницы вверх (свайп сверху вниз)
     */
    fun scroll(x: Float, y: Float, dir: Int, distPx: Float = 450f) {
        val (sw, sh) = screen()
        val len = maxOf(350f, distPx)
        val px = cx(x)
        val py = cy(y).coerceIn(len / 2f + 40f, maxOf(len / 2f + 40f, sh - len / 2f - 40f))

        val startY: Float
        val endY: Float
        if (dir > 0) {
            // Прокрутка вниз: палец свайпает вверх
            startY = py + len / 2f
            endY = py - len / 2f
        } else {
            // Прокрутка вверх: палец свайпает вниз
            startY = py - len / 2f
            endY = py + len / 2f
        }

        val p = Path().apply {
            moveTo(px, startY)
            lineTo(px, endY)
        }
        send(GestureDescription.StrokeDescription(p, 0, 80))
    }

    /**
     * Начало «зажатого» касания. holdMs > 1 — палец сначала неподвижно держится это время
     * (долгое нажатие): так в Android начинается выделение текста, дальше движение тянет выделение.
     */
    fun dragStart(x: Float, y: Float, holdMs: Long = 1) {
        lx = cx(x); ly = cy(y)
        tx = lx; ty = ly
        ending = false
        val s = GestureDescription.StrokeDescription(Path().apply { moveTo(lx, ly) }, 0, holdMs.coerceIn(1, 5000), true)
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


    // ======================================================================
    //  Плавный скролл колёсиком: палец непрерывно едет за движением колёсика
    // ======================================================================

    private var sStroke: GestureDescription.StrokeDescription? = null
    private var sActive = false
    private var sBusy = false
    private var sEnding = false
    private var sRecenter = false
    private var sAx = 0f   // якорь: X и Y курсора в момент касания колёсика
    private var sAy = 0f
    private var sFy = 0f   // Y последней отправленной точки пальца
    private var sTy = 0f   // целевая Y пальца
    private var sOff = 0f

    private fun sLo() = 32f * resources.displayMetrics.density
    private fun sHi() = screen().second - 72f * resources.displayMetrics.density

    fun scrollBegin(x: Float, y: Float) {
        sActive = true
        sEnding = false
        sRecenter = false
        sAx = cx(x); sAy = cy(y)
        sFy = sAy; sTy = sAy
        if (sStroke == null && !sBusy) sStartStroke()
    }

    /** dy > 0 — колёсико тянут вниз: страница едет вниз, палец движется вверх. */
    fun scrollBy(dy: Float, gain: Float = 2.2f) {
        if (!sActive || sEnding) return
        sTy -= dy * gain
        sNext()
    }

    fun scrollEnd() {
        if (!sActive) return
        sEnding = true
        sNext()
    }

    private fun sStartStroke() {
        sFy = sAy
        val s = GestureDescription.StrokeDescription(Path().apply { moveTo(sAx, sAy) }, 0, 1, true)
        sStroke = s
        sDispatch(s, false)
    }

    private fun sSend(s: GestureDescription.StrokeDescription, ty: Float) {
        val p = Path().apply { moveTo(sAx, sFy); lineTo(sAx, ty) }
        val n = s.continueStroke(p, 0, 16, true)
        sFy = ty
        sStroke = n
        sDispatch(n, false)
    }

    /** Поднимаем палец после короткой паузы на месте, чтобы не было инерции (fling). */
    private fun sLift(s: GestureDescription.StrokeDescription, recenter: Boolean) {
        val p = Path().apply { moveTo(sAx, sFy) }
        val n = s.continueStroke(p, 0, 60, false)
        sStroke = null
        sRecenter = recenter
        sDispatch(n, true)
    }

    private fun sDispatch(s: GestureDescription.StrokeDescription, lifting: Boolean) {
        sBusy = true
        val ok = send(s, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                sBusy = false
                if (lifting) {
                    if (sEnding) {
                        sActive = false
                        sEnding = false
                        sRecenter = false
                        return
                    }
                    if (sRecenter) {
                        sRecenter = false
                        sFy = sAy
                        sTy = sAy + sOff
                    }
                }
                sNext()
            }

            override fun onCancelled(g: GestureDescription?) {
                sBusy = false
                sStroke = null
                sRecenter = false
                if (sEnding) {
                    sActive = false
                    sEnding = false
                } else {
                    sTy = sAy + (sTy - sFy)
                    sFy = sAy
                }
            }
        })
        if (!ok) {
            sBusy = false
            sStroke = null
            sActive = false
            sEnding = false
        }
    }

    private fun sNext() {
        if (sBusy || !sActive) return
        val s = sStroke
        if (s == null) {
            if (!sEnding) sStartStroke() else sActive = false
            return
        }
        val ty = sTy.coerceIn(min(sLo(), sHi()), max(sLo(), sHi()))
        if (sEnding) {
            if (ty != sFy) sSend(s, ty) else sLift(s, false)
            return
        }
        if (sTy == sFy) return
        if (ty != sFy) {
            sSend(s, ty)
            return
        }
        // Дошли до края экрана: поднимаем палец и продолжаем от позиции курсора
        sOff = sTy - sFy
        sLift(s, true)
    }

    // ======================================================================
    //  Работа с текстом: выделенное, копирование, вставка (напрямую в буфер приложения)
    // ======================================================================

    private val clipboard get() = AppClipboardManager.get(this)

    private fun toast(msg: String) {
        ui.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun hasSel(n: AccessibilityNodeInfo): Boolean {
        if (n.isPassword) return false
        val a = n.textSelectionStart
        val b = n.textSelectionEnd
        return a >= 0 && b >= 0 && a != b && !n.text.isNullOrEmpty()
    }

    private fun findSel(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (hasSel(n)) return n
        for (i in 0 until n.childCount) {
            val r = findSel(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun inputNode(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun selNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && hasSel(f)) return f
        return findSel(root, 0)
    }

    private fun selRange(n: AccessibilityNodeInfo): Pair<Int, Int> {
        val a = n.textSelectionStart
        val b = n.textSelectionEnd
        val len = n.text?.length ?: 0
        return min(a, b).coerceIn(0, len) to max(a, b).coerceIn(0, len)
    }

    private fun setTextAndCaret(n: AccessibilityNodeInfo, text: String, caret: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    private fun currentText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else (n.text?.toString() ?: "")

    /** Ищет в открытых окнах (в т.ч. в плавающей панели выделения) кнопку по подписи и нажимает её. */
    private fun clickLabel(labels: List<String>): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            windows.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { roots.add(it) }
        for (r in roots) {
            for (l in labels) {
                val found = try { r.findAccessibilityNodeInfosByText(l) } catch (_: Exception) { null }
                found?.forEach { node ->
                    val t = (node.text ?: node.contentDescription)?.toString() ?: return@forEach
                    if (!t.equals(l, ignoreCase = true)) return@forEach
                    var cur: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (cur != null && depth < 4) {
                        if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        cur = cur.parent
                        depth++
                    }
                }
            }
        }
        return false
    }

    private val LBL_COPY = listOf("Копировать", "Копіювати", "Copy")
    private val LBL_CUT = listOf("Вырезать", "Вирізати", "Cut")
    private val LBL_ALL = listOf("Выделить все", "Выбрать все", "Виділити все", "Вибрати все", "Select all")

    /** Копирует выделенный текст прямо в буфер приложения. */
    fun copySelection() {
        val n = selNode()
        if (n != null) {
            val (a, b) = selRange(n)
            val t = n.text.toString().substring(a, b)
            clipboard.add(t)
            toast("В буфер: ${t.length} симв.")
            return
        }
        // Выделение на веб-странице/в нестандартном виджете: жмём «Копировать» в панели выделения
        if (clickLabel(LBL_COPY)) {
            ui.postDelayed({ ClipGrabActivity.start(this) }, 250)
        } else {
            toast("Нет выделенного текста")
        }
    }

    fun cutSelection() {
        val n = selNode()
        if (n != null && n.isEditable) {
            val (a, b) = selRange(n)
            val full = n.text.toString()
            clipboard.add(full.substring(a, b))
            setTextAndCaret(n, full.removeRange(a, b), a)
            toast("Вырезано в буфер")
            return
        }
        if (n != null) {
            copySelection()
            return
        }
        if (clickLabel(LBL_CUT)) {
            ui.postDelayed({ ClipGrabActivity.start(this) }, 250)
        } else {
            toast("Нет выделенного текста")
        }
    }

    fun deleteSelection() {
        val n = selNode()
        if (n != null && n.isEditable) {
            val (a, b) = selRange(n)
            setTextAndCaret(n, n.text.toString().removeRange(a, b), a)
        } else {
            toast("Нечего удалять")
        }
    }

    /** Вставляет последний элемент буфера приложения в поле с фокусом. */
    fun pasteFromBuffer() {
        val item = clipboard.latest()
        if (item == null) {
            toast("Буфер пуст")
            return
        }
        val n = inputNode()
        if (n == null || !n.isEditable) {
            toast("Нет активного поля ввода")
            return
        }
        val full = currentText(n)
        val a0 = n.textSelectionStart
        val b0 = n.textSelectionEnd
        val a = if (a0 < 0 || n.isShowingHintText) full.length else min(a0, b0).coerceIn(0, full.length)
        val b = if (b0 < 0 || n.isShowingHintText) full.length else max(a0, b0).coerceIn(0, full.length)
        val res = full.substring(0, a) + item.text + full.substring(b)
        if (!setTextAndCaret(n, res, a + item.text.length)) {
            // запасной путь: через системный буфер и штатное действие «вставить»
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("virtual_mouse", item.text))
                n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (_: Exception) {
            }
        }
    }

    fun selectAllText() {
        val n = inputNode()
        if (n != null && !n.text.isNullOrEmpty() && !n.isShowingHintText) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text.length)
            }
            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)) return
        }
        if (!clickLabel(LBL_ALL)) toast("Нечего выделять")
    }

    fun undo() = VirtualMouseIME.instance?.ctrlKey(android.view.KeyEvent.KEYCODE_Z, false)
        ?: toast("Включите клавиатуру Virtual Mouse")

    fun redo() = VirtualMouseIME.instance?.ctrlKey(android.view.KeyEvent.KEYCODE_Z, true)
        ?: toast("Включите клавиатуру Virtual Mouse")

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun recents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }
}
