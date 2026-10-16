package com.example.virtualmouse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.LinkedList

/**
 * Буфер обмена с историей (в памяти процесса).
 * Лимит суммарного размера истории: 100 МБ.
 * В системный буфер кладутся только элементы до ~500 КБ,
 * иначе возможен TransactionTooLargeException.
 */
class AppClipboardManager(private val context: Context) {

    data class ClipItem(
        val text: String,
        val sizeBytes: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class AddResult { ADDED, EMPTY, TOO_LARGE }

    companion object {
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024   // 100 МБ
        const val SYSTEM_CLIP_LIMIT = 500L * 1024        // ~500 КБ
    }

    private val history = LinkedList<ClipItem>()
    private var currentTotalBytes = 0L

    private val systemClipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Добавляет текст в начало истории (дубликаты по всему списку удаляются).
     * @param copyToSystem также положить в системный буфер (если элемент не слишком большой)
     */
    fun add(text: String, copyToSystem: Boolean = true): AddResult {
        if (text.isBlank()) return AddResult.EMPTY

        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > MAX_TOTAL_BYTES) return AddResult.TOO_LARGE

        // Убираем дубликаты по всему списку
        val iterator = history.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.text == text) {
                currentTotalBytes -= item.sizeBytes
                iterator.remove()
            }
        }

        // Вытесняем старые записи, если превышаем лимит
        while (history.isNotEmpty() && currentTotalBytes + bytes > MAX_TOTAL_BYTES) {
            val removed = history.removeLast()
            currentTotalBytes -= removed.sizeBytes
        }

        history.addFirst(ClipItem(text, bytes))
        currentTotalBytes += bytes

        if (copyToSystem && bytes <= SYSTEM_CLIP_LIMIT) {
            try {
                systemClipboard.setPrimaryClip(ClipData.newPlainText("virtual_mouse", text))
            } catch (_: Exception) {
                // Не критично: элемент всё равно есть в истории
            }
        }
        return AddResult.ADDED
    }

    /** Читает текст из системного буфера. На Android 10+ фоновый доступ может быть запрещён. */
    fun readSystemClipboard(): String? {
        return try {
            val clip = systemClipboard.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0)?.coerceToText(context)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun getHistory(): List<ClipItem> = history.toList()

    fun clear() {
        history.clear()
        currentTotalBytes = 0
    }

    fun sizeBytes(): Long = currentTotalBytes
    fun sizeCount(): Int = history.size
}
