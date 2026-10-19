package com.example.virtualmouse

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * Быстрый запуск файлового менеджера.
 *
 * Порядок выбора:
 *  1) менеджер, который пользователь явно выбрал в настройках (getSaved) — запускается он;
 *  2) по умолчанию («Авто») — системное окно «Открыть» (DocumentsUI): слева список Видео / Аудио /
 *     Документы / Скачанные / память устройства / карта памяти и приложения-источники
 *     («Мои файлы», «Файловый менеджер+», Галерея…) — пользователь сам выбирает, откуда открыть;
 *  3) запасные варианты системы.
 * Раньше «Авто» подбирало первый попавшийся пакет по названию и могло запустить WPS Office.
 *
 * ВАЖНО: чтобы Android 11+ вообще показал приложению чужие пакеты, в манифесте должны быть
 * разрешение QUERY_ALL_PACKAGES и блок <queries> с intent-фильтрами (это делает AndroidManifest.xml).
 * Без этого getLaunchIntentForPackage() возвращал null и открывался системный менеджер.
 */
object FileManagerLauncher {

    private const val PREFS = "virtual_mouse_filemanager"
    private const val KEY_PKG = "pkg"

    /** Известные менеджеры (порядок = приоритет среди системных). */
    val PACKAGES = listOf(
        "com.sec.android.app.myfiles",          // Samsung «Мои файлы»
        "com.google.android.apps.nbu.files",    // Files by Google
        "com.mi.android.globalFileexplorer",    // Xiaomi
        "com.miui.fileexplorer",
        "com.huawei.hidisk",
        "com.coloros.filemanager",              // OPPO / Realme
        "com.oneplus.filemanager",
        "com.asus.filemanager",
        "com.google.android.documentsui",
        "com.android.documentsui"
    )

    /** Популярные сторонние менеджеры — чтобы находить их даже без intent-фильтров. */
    private val POPULAR = listOf(
        "com.mixplorer", "com.mixplorer.silver",
        "pl.solidexplorer2", "com.estrongs.android.pop", "com.lonelycatgames.Xplore",
        "com.ghostsq.commander", "com.ghisler.android.TotalCommander",
        "nextapp.fx", "com.alphainventor.filemanager", "com.amaze.filemanager",
        "com.cxinventor.file.explorer", "com.speedsoftware.explorer",
        "com.rhmsoft.fm", "ru.zdevs.zarchiver", "com.metago.astro",
        "me.zhanghai.android.files"
    )

    private val NAME_HINTS = listOf(
        "file", "файл", "manager", "менеджер", "explorer", "проводник", "провідник",
        "commander", "командир", "x-plore", "mixplorer", "zarchiver", "solid", "amaze"
    )

    /** Приложение-кандидат для списка в настройках. */
    data class App(val pkg: String, val label: String, val system: Boolean)

    // ---------- Выбор пользователя ----------

    /** Сохранённый пакет или "" (= автоопределение). */
    fun getSaved(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PKG, "") ?: ""

    /** pkg = "" возвращает автоопределение. */
    fun save(ctx: Context, pkg: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PKG, pkg).apply()
    }

    /** Подпись для строки в настройках: имя выбранного приложения или «Авто». */
    fun savedLabel(ctx: Context): String {
        val p = getSaved(ctx)
        if (p.isEmpty()) return "Окно выбора файлов"
        return labelOf(ctx, p) ?: "Не найден: $p"
    }

    fun labelOf(ctx: Context, pkg: String): String? = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        null
    }

    // ---------- Списки приложений для выбора ----------

    /** Вероятные файловые менеджеры: пользовательские первыми, потом системные. */
    fun candidates(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val own = ctx.packageName
        val map = LinkedHashMap<String, App>()

        fun add(pkg: String) {
            if (pkg == own || map.containsKey(pkg)) return
            if (pm.getLaunchIntentForPackage(pkg) == null) return
            val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return }
            val label = pm.getApplicationLabel(info).toString()
            map[pkg] = App(pkg, label, (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }

        // а) приложения, которые умеют открывать папки
        val folderIntents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/"), "resource/folder"),
            Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                "vnd.android.document/root"
            ),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/"), "vnd.android.document/directory")
        )
        for (i in folderIntents) {
            try {
                pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
                    .forEach { add(it.activityInfo.packageName) }
            } catch (_: Exception) {
            }
        }

        // б) известные менеджеры, если установлены
        (POPULAR + PACKAGES).forEach { add(it) }

        // в) по названию в списке запускаемых приложений
        try {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(main, 0).forEach { ri ->
                val l = ri.loadLabel(pm).toString().lowercase()
                if (NAME_HINTS.any { l.contains(it) }) add(ri.activityInfo.packageName)
            }
        } catch (_: Exception) {
        }

        // пользовательские выше системных; внутри группы — порядок обнаружения
        return map.values.sortedBy { if (it.system) 1 else 0 }
    }

    /** Все запускаемые приложения (для ручного выбора, если менеджера нет в candidates). */
    fun allLaunchable(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val own = ctx.packageName
        val out = ArrayList<App>()
        try {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(main, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == own || out.any { it.pkg == pkg }) return@forEach
                val sys = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                out.add(App(pkg, ri.loadLabel(pm).toString(), sys))
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { it.label.lowercase() }
    }

    // ---------- Запуск ----------

    /** Системные пакеты окна «Открыть» (DocumentsUI), по порядку попыток. */
    private val DOCUMENTS_UI = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui"
    )

    fun open(ctx: Context) {
        val pm = ctx.packageManager

        // 1) явный выбор пользователя в настройках
        val saved = getSaved(ctx)
        if (saved.isNotEmpty()) {
            val i = pm.getLaunchIntentForPackage(saved)
            if (i != null && tryStart(ctx, i)) return
            // выбранный менеджер удалён — показываем окно выбора файлов
        }

        // 2) системное окно «Открыть» с боковой панелью источников
        if (openPicker(ctx)) return

        // 3) запасные варианты
        if (tryStart(ctx, Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_FILES"))) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://com.android.externalstorage.documents/root/primary"),
            "vnd.android.document/root"
        )
        if (tryStart(ctx, view)) return
        Toast.makeText(ctx, "Окно выбора файлов не найдено", Toast.LENGTH_SHORT).show()
    }

    /**
     * Окно «Открыть»: сначала напрямую в DocumentsUI (без списка «Открыть с помощью» и без WPS Office),
     * иначе — обычный ACTION_OPEN_DOCUMENT / ACTION_GET_CONTENT системы.
     */
    private fun openPicker(ctx: Context): Boolean {
        fun docIntent(action: String) = Intent(action)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)

        for (pkg in DOCUMENTS_UI) {
            if (tryStart(ctx, docIntent(Intent.ACTION_OPEN_DOCUMENT).setPackage(pkg))) return true
        }
        for (pkg in DOCUMENTS_UI) {
            if (tryStart(ctx, docIntent(Intent.ACTION_GET_CONTENT).setPackage(pkg))) return true
        }
        if (tryStart(ctx, docIntent(Intent.ACTION_OPEN_DOCUMENT))) return true
        return tryStart(ctx, Intent.createChooser(docIntent(Intent.ACTION_GET_CONTENT), "Файлы"))
    }

    private fun tryStart(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }
}
