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

    /**
     * Рамка захвата (экранные координаты), которую пользователь только что обвёл мышью.
     * Служит «выделением»: «Копировать» берёт текст под рамкой, даже если приложение само
     * ничего не выделило. Сбрасывается любым кликом, началом скролла и командой grabClear().
     */
    @Volatile
    var grabRect: Rect? = null
        private set

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

    /**
     * Тап одним касанием. Раньше его мог молча оборвать другой жест (скролл, перетаскивание)
     * или касание пальца по экрану — клик «не срабатывал». Теперь:
     *  - если в этот момент летит свайп скролла, ждём его завершения;
     *  - если жест отменён или не принят системой, повторяем до 3 раз.
     */
    private fun tap(x: Float, y: Float, duration: Long, attempt: Int = 0) {
        if (sBusy && attempt < 4) {
            ui.postDelayed({ tap(x, y, duration, attempt + 1) }, 70)
            return
        }
        val p = Path().apply { moveTo(cx(x), cy(y)) }
        val ok = send(GestureDescription.StrokeDescription(p, 0, duration), object : GestureResultCallback() {
            override fun onCancelled(g: GestureDescription?) {
                if (attempt < 3) ui.postDelayed({ tap(x, y, duration, attempt + 1) }, 60)
            }
        })
        if (!ok && attempt < 3) ui.postDelayed({ tap(x, y, duration, attempt + 1) }, 60)
    }

    fun click(x: Float, y: Float) {
        grabRect = null
        tap(x, y, 60)
    }

    fun rightClick(x: Float, y: Float) {
        grabRect = null
        tap(x, y, 600)
    }

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
        grabFinish(x1, y1, x2, y2)
    }

    // ======================================================================
    //  Рамка захвата как выделение (используют тачпад и трекбол)
    // ======================================================================

    /**
     * Рамка завершена (кнопку мыши отпустили). Запоминаем её как «выделение» и, если приложение
     * само ничего не выделило, пробуем выделить текст под рамкой через доступность.
     * Возвращает false, если рамка слишком мала (это был просто клик/долгое нажатие).
     */
    fun grabFinish(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val rect = Rect(
            min(x1, x2).toInt(), min(y1, y2).toInt(),
            max(x1, x2).toInt(), max(y1, y2).toInt()
        )
        if (rect.width() < dpf(10f) && rect.height() < dpf(10f)) {
            grabRect = null
            return false
        }
        grabRect = rect
        ui.postDelayed({
            if (grabRect !== rect) return@postDelayed
            if (selNode() != null) return@postDelayed   // приложение уже выделило само — не трогаем
            val root = rootInActiveWindow ?: return@postDelayed
            findAndSelectInNode(root, rect, 0)
        }, 250)
        return true
    }

    fun grabClear() {
        grabRect = null
    }

    /**
     * Какую часть текста узла покрывает рамка (индексы символов).
     * Для однострочного узла считаем по горизонтали, для многострочного — по вертикали.
     */
    private fun sliceRange(b: Rect, rect: Rect, total: Int): Pair<Int, Int> {
        if (rect.contains(b.left, b.top, b.right, b.bottom)) return 0 to total
        val s: Float
        val e: Float
        if (b.height() <= dpf(48f)) {
            val w = b.width().coerceAtLeast(1).toFloat()
            s = ((rect.left - b.left) / w).coerceIn(0f, 1f)
            e = ((rect.right - b.left) / w).coerceIn(0f, 1f)
        } else {
            val h = b.height().coerceAtLeast(1).toFloat()
            s = ((rect.top - b.top) / h).coerceIn(0f, 1f)
            e = ((rect.bottom - b.top) / h).coerceIn(0f, 1f)
        }
        val a = (s * total).toInt().coerceIn(0, total)
        val c = (e * total).toInt().coerceIn(0, total)
        return min(a, c) to max(a, c)
    }

    private fun findAndSelectInNode(n: AccessibilityNodeInfo?, rect: Rect, depth: Int): Boolean {
        if (n == null || depth > 25) return false
        val b = Rect()
        n.getBoundsInScreen(b)
        if (!Rect.intersects(b, rect)) return false
        val text = n.text
        if (!text.isNullOrEmpty() && !n.isPassword && !n.isShowingHintText) {
            val (start, end) = sliceRange(b, rect, text.length)
            if (end > start) {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                }
                if (n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)) return true
            }
        }
        for (i in 0 until n.childCount) {
            if (findAndSelectInNode(n.getChild(i), rect, depth + 1)) return true
        }
        return false
    }

    private class TextPiece(val top: Int, val left: Int, val text: String)

    private fun collectText(n: AccessibilityNodeInfo?, rect: Rect, out: MutableList<TextPiece>, depth: Int) {
        if (n == null || depth > 30 || !n.isVisibleToUser) return
        val b = Rect()
        n.getBoundsInScreen(b)
        if (!Rect.intersects(b, rect)) return
        val text = n.text
        if (!text.isNullOrEmpty() && !n.isPassword && !n.isShowingHintText) {
            val full = text.toString()
            val (start, end) = sliceRange(b, rect, full.length)
            if (end > start) {
                val piece = full.substring(start, end).trim()
                if (piece.isNotEmpty()) out.add(TextPiece(b.top, b.left, piece))
            }
        }
        for (i in 0 until n.childCount) collectText(n.getChild(i), rect, out, depth + 1)
    }

    /** Весь текст, попавший под рамку (сверху вниз, слева направо). */
    private fun textUnderRect(rect: Rect): String {
        val root = rootInActiveWindow ?: return ""
        val pieces = ArrayList<TextPiece>()
        collectText(root, rect, pieces, 0)
        if (pieces.isEmpty()) return ""
        pieces.sortWith(compareBy<TextPiece>({ it.top }, { it.left }))
        val lineTol = dpf(8f)
        val sb = StringBuilder()
        var prev: TextPiece? = null
        for (p in pieces) {
            val pr = prev
            if (pr != null) {
                if (p.text == pr.text && p.top == pr.top) continue          // дубль (родитель и потомок)
                sb.append(if (kotlin.math.abs(p.top - pr.top) <= lineTol) " " else "\n")
            }
            sb.append(p.text)
            prev = p
        }
        return sb.toString()
    }

    private fun nodeIntersects(n: AccessibilityNodeInfo, rect: Rect): Boolean {
        val b = Rect()
        n.getBoundsInScreen(b)
        return Rect.intersects(b, rect)
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
    private var sFlight = 0f      // сколько пикселей «съел» свайп, который сейчас в полёте
    private var sFails = 0        // сколько свайпов подряд отменила система
    private val sWatchdog = Runnable {
        sBusy = false
        sFlight = 0f
        sPump()
    }

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    fun scrollBegin(x: Float, y: Float) {
        if (stroke != null) {
            // идёт перетаскивание (ЛКМ зажата): второй жест поверх него оборвёт оба
            sActive = false
            return
        }
        grabRect = null
        sActive = true
        sAx = cx(x)
        sAy = cy(y)
        sAcc = 0f
        sRate = 0f
        sFails = 0
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
        val dir = if (sAcc > 0f) 1 else -1

        // Система отменяет «чужие» жесты, пока пользователь сам водит пальцем по экрану (а он как раз
        // крутит колёсико пальцем) — свайпы отменялись один за другим и страница не ехала, а
        // накопленный путь терялся. После двух отмен подряд листаем напрямую через узел
        // доступности (прокручиваемый список/страница под курсором): этому касание не мешает.
        if (sFails >= 2) {
            val step = dpf(300f)
            if (scrollNode(dir)) {
                sAcc = if (kotlin.math.abs(sAcc) > step) sAcc - dir * step else 0f
                sBusy = true
                ui.postDelayed({ sBusy = false; sPump() }, 90)
            } else {
                // нечего прокручивать напрямую — сбрасываем счётчик, следующая порция попробует свайп снова
                sAcc = 0f
                sFails = 0
            }
            return
        }

        val slop = dpf(12f)
        val sh = screen().second.toFloat()
        val top = dpf(48f)
        val bottom = sh - dpf(72f)
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
        val consumed = dir * max(len - slop, 0f)
        sAcc -= consumed
        sFlight = consumed

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
            override fun onCompleted(g: GestureDescription?) {
                sFails = 0
                sFlight = 0f
                sDone()
            }

            override fun onCancelled(g: GestureDescription?) {
                // свайп не доехал — возвращаем путь в очередь, чтобы он не пропал
                sAcc += sFlight
                sFlight = 0f
                sFails++
                sBusy = false
                ui.removeCallbacks(sWatchdog)
                ui.postDelayed({ sPump() }, 30)
            }
        })
        if (!ok) {
            sAcc += sFlight
            sFlight = 0f
            sFails = 2          // жест не принят вообще — сразу на запасной путь
            sBusy = false
            ui.removeCallbacks(sWatchdog)
            ui.post { sPump() }
        }
    }

    private fun sDone() {
        sBusy = false
        ui.removeCallbacks(sWatchdog)
        sPump()
    }

    // ---------- Запасной скролл через дерево доступности ----------

    private fun scrollNode(dir: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (dir > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        // 1) самый вложенный прокручиваемый узел под курсором, затем его родители
        var cur: AccessibilityNodeInfo? = deepestScrollable(root, sAx.toInt(), sAy.toInt(), 0)
        var guard = 0
        while (cur != null && guard < 10) {
            if (cur.isScrollable && cur.performAction(action)) return true
            cur = cur.parent
            guard++
        }
        // 2) курсор не над списком — берём самую большую прокручиваемую область окна
        val big = largestScrollable(root, 0) ?: return false
        return big.performAction(action)
    }

    private fun deepestScrollable(n: AccessibilityNodeInfo?, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30) return null
        val b = Rect()
        n.getBoundsInScreen(b)
        if (!b.contains(x, y)) return null
        for (i in n.childCount - 1 downTo 0) {
            val r = deepestScrollable(n.getChild(i), x, y, depth + 1)
            if (r != null) return r
        }
        return if (n.isScrollable) n else null
    }

    private fun largestScrollable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1L
        if (n.isScrollable && n.isVisibleToUser) {
            val b = Rect()
            n.getBoundsInScreen(b)
            best = n
            bestArea = b.width().toLong() * b.height().toLong()
        }
        for (i in 0 until n.childCount) {
            val c = largestScrollable(n.getChild(i), depth + 1) ?: continue
            val b = Rect()
            c.getBoundsInScreen(b)
            val area = b.width().toLong() * b.height().toLong()
            if (area > bestArea) {
                best = c
                bestArea = area
            }
        }
        return best
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
        val r = grabRect
        // Реальное выделение в приложении берём, если оно относится к рамке (или рамки нет)
        if (n != null && (r == null || nodeIntersects(n, r))) {
            val (a, b) = selRange(n)
            val t = n.text.toString().substring(a, b)
            clipboard.add(t)
            toast("В буфер: ${t.length} симв.")
            return
        }
        // Рамка захвата работает как выделение: берём текст под ней
        if (r != null) {
            val t = textUnderRect(r)
            if (t.isNotBlank()) {
                clipboard.add(t)
                toast("В буфер: ${t.length} симв.")
                return
            }
        }
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
