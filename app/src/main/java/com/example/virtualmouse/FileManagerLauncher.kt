package com.example.virtualmouse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Быстрый запуск файлового менеджера, установленного на устройстве. */
object FileManagerLauncher {

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

    fun open(ctx: Context) {
        val pm = ctx.packageManager
        for (p in PACKAGES) {
            val i = pm.getLaunchIntentForPackage(p) ?: continue
            if (tryStart(ctx, i)) return
        }
        // Стандартная категория «Файлы» (Android 12+)
        if (tryStart(ctx, Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_FILES"))) return
        // Просмотр внутреннего хранилища через системный DocumentsUI
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://com.android.externalstorage.documents/root/primary"),
            "vnd.android.document/root"
        )
        if (tryStart(ctx, view)) return
        // Последний вариант: системный выбор файлов
        val pick = Intent.createChooser(
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),
            "Файлы"
        )
        if (!tryStart(ctx, pick)) {
            Toast.makeText(ctx, "Файловый менеджер не найден", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStart(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }
}
