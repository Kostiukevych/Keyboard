package com.example.virtualmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.math.hypot
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
        val dist = hypot(tx - lx, ty - ly)
        val p = Path().apply {
            moveTo(lx, ly)
            lineTo(tx, ty)
        }
        lx = tx; ly = ty
        val dur = if (ending) {
            if (dist > 5f) (dist / 1.5f).toLong().coerceIn(60L, 200L) else 30L
        } else {
            (dist / 2.0f).toLong().coerceIn(24L, 120L)
        }
        val n = s.continueStroke(p, 0, dur, !ending)
        stroke = if (ending) null else n
        ending = false
        dispatchStroke(n)
    }

    // ======================================================================
    //  Выделение рамкой (зажатая ПКМ + движение курсора)
    // ======================================================================

    fun boxSelectStart(x: Float, y: Float) {
        // Долгое нажатие (500 мс) в начальной точке выделяет первое слово и показывает ручки выделения
        dragStart(x, y, 500)
    }

    fun boxSelectMove(x: Float, y: Float) {
        dragMove(x, y)
    }

    fun boxSelectEnd(x1: Float, y1: Float, x2: Float, y2: Float) {
        dragMove(x2, y2)
        dragEnd()

        val left = minOf(x1, x2).toInt()
        val top = minOf(y1, y2).toInt()
        val right = maxOf(x1, x2).toInt()
        val bottom = maxOf(y1, y2).toInt()
        val rect = Rect(left, top, right, bottom)

        // Проверяем дерево доступности для выделения в полях ввода и текстовых блоках
        ui.postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                findAndSelectInNode(root, rect, 0)
            }
        }, 150)
    }

    private fun findAndSelectInNode(n: AccessibilityNodeInfo?, rect: Rect, depth: Int): Boolean {
        if (n == null || depth > 25) return false
        val b = Rect()
        n.getBoundsInScreen(b)
        if (Rect.intersects(b, rect)) {
            val text = n.text
            if (!text.isNullOrEmpty()) {
                val total = text.length
                val sRatio = ((rect.top - b.top).toFloat() / b.height().coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                val eRatio = ((rect.bottom - b.top).toFloat() / b.height().coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                val sIdx = (sRatio * total).toInt().coerceIn(0, total)
                val eIdx = (eRatio * total).toInt().coerceIn(0, total)
                val start = min(sIdx, eIdx)
                val end = max(sIdx, eIdx).coerceAtLeast(min(start + 1, total))
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                }
                if (n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)) {
                    return true
                }
            }
            for (i in 0 until n.childCount) {
                if (findAndSelectInNode(n.getChild(i), rect, depth + 1)) return true
            }
        }
        return false
    }

    // ======================================================================
    //  Скролл колёсиком: серия коротких свайпов от позиции курсора.
    // ======================================================================

    private var sActive = false
    private var sBusy = false
    private var sAx = 0f
    private var sAy = 0f
    private var sAcc = 0f
    private var sRate = 0f
    private var sLastT = 0L
    private val sWatchdog = Runnable {
        sBusy = false
        sPump()
    }

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    fun scrollBegin(x: Float, y: Float) {
        sActive = true
        sAx = cx(x)
        sAy = cy(y)
        sAcc = 0f
        sRate = 0f
        sLastT = SystemClock.uptimeMillis()
    }

    fun scrollBy(dy: Float, gain: Float = 2.2f) {
        if (!sActive) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - sLastT).coerceIn(1L, 120L)
        sLastT = now
        val d = dy * gain
        sRate = sRate * 0.6f + (kotlin.math.abs(d) / dt) * 0.4f
        sAcc += d
        sPump()
    }

    fun scrollEnd() {
        sActive = false
    }

    private fun sPump() {
        if (sBusy) return
        val minStep = dpf(36f)
        if (kotlin.math.abs(sAcc) < minStep) return

        val slop = dpf(12f)
        val sh = screen().second.toFloat()
        val top = dpf(48f)
        val bottom = sh - dpf(72f)
        val dir = if (sAcc > 0f) 1 else -1
        val want = min(kotlin.math.abs(sAcc), dpf(420f))

        var len = want + slop
        var startY = sAy
        val room = if (dir > 0) sAy - top else bottom - sAy
        if (len > room) {
            if (room >= minStep + slop) {
                len = room
            } else {
                len = min(len, bottom - top)
                startY = if (dir > 0) min(bottom, top + len) else max(top, bottom - len)
            }
        }
        val endY = startY - dir * len
        sAcc -= dir * max(len - slop, 0f)

        val v = sRate.coerceIn(0.45f, 3.0f)
        val dur = (len / v).toLong().coerceIn(70L, 320L)

        val p = Path().apply {
            moveTo(sAx, startY)
            lineTo(sAx, endY)
        }
        sBusy = true
        ui.removeCallbacks(sWatchdog)
        ui.postDelayed(sWatchdog, dur + 800)
        val ok = send(GestureDescription.StrokeDescription(p, 0, dur), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = sDone()
            override fun onCancelled(g: GestureDescription?) = sDone()
        })
        if (!ok) {
            sBusy = false
            sAcc = 0f
            ui.removeCallbacks(sWatchdog)
        }
    }

    private fun sDone() {
        sBusy = false
        ui.removeCallbacks(sWatchdog)
        sPump()
    }

    // ======================================================================
    //  Обновление страницы:
    //  а) поиск в дереве доступности кнопки/пункта «Обновить» (или в меню «⋮»)
    //  б) Ctrl+R / F5
    //  в) жест pull-to-refresh
    // ======================================================================

    fun refreshPage() {
        val reloadKeywords = listOf("обновить", "перезагрузить", "reload", "refresh", "оновити")

        // а) Найти в дереве доступности активного окна кнопку или пункт «Обновить»
        val root = rootInActiveWindow
        if (findAndClickReload(root, reloadKeywords)) {
            toast("Обновление...")
            return
        }

        // Если не найдено сразу, пробуем открыть меню Chrome «⋮» и нажать «Обновить»
        val moreKeywords = listOf("другие параметры", "ещё", "more options", "more", "більше опцій", "меню", "menu")
        val menuOpened = findAndClickMenu(root, moreKeywords)
        if (menuOpened) {
            ui.postDelayed({
                val newRoot = rootInActiveWindow
                if (findAndClickReload(newRoot, reloadKeywords)) {
                    toast("Обновление...")
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    ui.postDelayed({
                        stepBAndC()
                    }, 120)
                }
            }, 250)
            return
        }

        stepBAndC()
    }

    private fun stepBAndC() {
        // б) отправить комбинацию Ctrl+R (и затем F5) через ввод клавиш
        if (tryKeyRefresh()) {
            toast("Обновление...")
            return
        }

        // в) жест «потянуть вниз» (pull-to-refresh)
        pullToRefresh()
    }

    private fun findAndClickReload(root: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (root == null) return false
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            windows.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) {
        }
        roots.add(root)

        // 1. Поиск по известным ID кнопок перезагрузки в Chrome
        val idList = listOf(
            "com.android.chrome:id/refresh_button",
            "com.android.chrome:id/reload_button",
            "org.chromium.chrome:id/refresh_button",
            "com.chrome.canary:id/refresh_button",
            "com.chrome.beta:id/refresh_button"
        )
        for (r in roots) {
            for (id in idList) {
                val nodes = try { r.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                nodes?.forEach { node ->
                    if (clickNodeOrParent(node)) return true
                }
            }
        }

        // 2. Поиск по тексту и contentDescription
        for (r in roots) {
            for (kw in keywords) {
                val nodes = try { r.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
                nodes?.forEach { node ->
                    val desc = (node.contentDescription ?: node.text)?.toString() ?: ""
                    if (desc.contains(kw, ignoreCase = true)) {
                        if (clickNodeOrParent(node)) return true
                    }
                }
            }
        }
        return false
    }

    private fun findAndClickMenu(root: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (root == null) return false
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            windows.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) {
        }
        roots.add(root)

        val idList = listOf(
            "com.android.chrome:id/menu_button",
            "com.android.chrome:id/more_button",
            "org.chromium.chrome:id/menu_button"
        )
        for (r in roots) {
            for (id in idList) {
                val nodes = try { r.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                nodes?.forEach { node ->
                    if (clickNodeOrParent(node)) return true
                }
            }
            for (kw in keywords) {
                val nodes = try { r.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
                nodes?.forEach { node ->
                    val desc = (node.contentDescription ?: node.text)?.toString() ?: ""
                    if (desc.contains(kw, ignoreCase = true)) {
                        if (clickNodeOrParent(node)) return true
                    }
                }
            }
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun tryKeyRefresh(): Boolean {
        val ime = VirtualMouseIME.instance
        if (ime != null && ime.currentInputConnection != null) {
            ime.ctrlKey(KeyEvent.KEYCODE_R, false)
            ui.postDelayed({
                ime.ctrlKey(KeyEvent.KEYCODE_F5, false)
            }, 100)
            return true
        }
        return false
    }

    private fun pullToRefresh() {
        toast("Обновление...")
        val (sw, sh) = screen()
        val cx = sw / 2f
        val startY = dpf(90f).coerceIn(40f, sh * 0.25f)
        val endY = startY + dpf(380f).coerceAtMost(sh * 0.65f)

        // 1. Прокрутка вверх (свайп сверху вниз в средней части)
        val pScroll = Path().apply {
            moveTo(cx, dpf(180f))
            lineTo(cx, dpf(420f))
        }
        send(GestureDescription.StrokeDescription(pScroll, 0, 100), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                ui.postDelayed({
                    // 2. Длинный свайп вниз от верхней трети экрана с плавной скоростью
                    val pPull = Path().apply {
                        moveTo(cx, startY)
                        lineTo(cx, endY)
                    }
                    send(GestureDescription.StrokeDescription(pPull, 0, 650))
                }, 120)
            }
            override fun onCancelled(g: GestureDescription?) {
                val pPull = Path().apply {
                    moveTo(cx, startY)
                    lineTo(cx, endY)
                }
                send(GestureDescription.StrokeDescription(pPull, 0, 650))
            }
        })
    }

    // ======================================================================
    //  Работа с текстом: выделенное, копирование, вставка
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
                    if (!t.contains(l, ignoreCase = true)) return@forEach
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

    fun copySelection() {
        val n = selNode()
        if (n != null) {
            val (a, b) = selRange(n)
            val t = n.text.toString().substring(a, b)
            clipboard.add(t)
            toast("В буфер: ${t.length} симв.")
            return
        }
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

    fun undo() = VirtualMouseIME.instance?.ctrlKey(KeyEvent.KEYCODE_Z, false)
        ?: toast("Включите клавиатуру Virtual Mouse")

    fun redo() = VirtualMouseIME.instance?.ctrlKey(KeyEvent.KEYCODE_Z, true)
        ?: toast("Включите клавиатуру Virtual Mouse")

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun recents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }
}
