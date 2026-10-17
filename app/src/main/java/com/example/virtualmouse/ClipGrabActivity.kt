package com.example.virtualmouse

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Невидимая активность на долю секунды: на Android 10+ читать системный буфер может только
 * приложение в фокусе. Забирает то, что система только что скопировала (веб-страницы и т.п.),
 * кладёт в буфер приложения и закрывается.
 */
class ClipGrabActivity : Activity() {

    companion object {
        fun start(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(ctx, ClipGrabActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (_: Exception) {
            }
        }
    }

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(this)?.toString() else null
            if (!t.isNullOrBlank()) AppClipboardManager.get(this).add(t)
        } catch (_: Exception) {
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
