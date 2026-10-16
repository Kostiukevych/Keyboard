package com.example.virtualmouse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Оверлей-диалог настроек. Изменяет переданный объект settings на месте. */
@SuppressLint("SetTextI18n")
class SettingsWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val settings: CursorSettings,
    private val onChanged: () -> Unit,
    private val onSave: () -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private var root: View? = null
    private var attached = false
    private var binding = false

    private val palette = listOf(
        "#2e7bff", "#00f0ff", "#ff00aa", "#00ff88", "#ffee00", "#ff4400",
        "#aa00ff", "#ff66cc", "#66ff00", "#ffaa00", "#00ffcc", "#ffffff"
    ).map { Color.parseColor(it) }

    private val styleButtons = ArrayList<TextView>()
    private val palettes = ArrayList<Pair<LinearLayout, () -> Int>>()

    private fun screenSize(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    private fun build(): View {
        val themed = ContextThemeWrapper(ctx, R.style.Theme_VirtualMouse)
        val v = LayoutInflater.from(themed).inflate(R.layout.dialog_settings, null)
        binding = true

        // Скорость 0.8..4.0
        val sbSpeed = v.findViewById<SeekBar>(R.id.sb_speed)
        val tvSpeed = v.findViewById<TextView>(R.id.tv_speed)
        sbSpeed.progress = Math.round((settings.sensitivity - 0.8f) * 10f).coerceIn(0, 32)
        tvSpeed.text = ctx.getString(R.string.set_speed) + ": " + String.format("%.1f", settings.sensitivity)
        sbSpeed.setOnSeekBarChangeListener(seek { p ->
            settings.sensitivity = 0.8f + p / 10f
            tvSpeed.text = ctx.getString(R.string.set_speed) + ": " + String.format("%.1f", settings.sensitivity)
        })

        // Размер курсора 24..96
        val sbSize = v.findViewById<SeekBar>(R.id.sb_size)
        val tvSize = v.findViewById<TextView>(R.id.tv_size)
        sbSize.progress = (settings.cursorSizeDp - 24).coerceIn(0, 72)
        tvSize.text = ctx.getString(R.string.set_size) + ": " + settings.cursorSizeDp + " dp"
        sbSize.setOnSeekBarChangeListener(seek { p ->
            settings.cursorSizeDp = 24 + p
            tvSize.text = ctx.getString(R.string.set_size) + ": " + settings.cursorSizeDp + " dp"
        })

        // Стиль курсора
        styleButtons.clear()
        listOf(R.id.st_arrow, R.id.st_cross, R.id.st_dot, R.id.st_hand).forEachIndexed { i, id ->
            val b = v.findViewById<TextView>(id)
            styleButtons.add(b)
            b.setOnClickListener {
                settings.cursorStyle = i
                refreshStyle()
                onChanged()
            }
        }
        refreshStyle()

        // Палитры
        palettes.clear()
        addPalette(v.findViewById(R.id.pal_cursor), { settings.cursorColor }) { settings.cursorColor = it }
        addPalette(v.findViewById(R.id.pal_glow), { settings.glowColor }) { settings.glowColor = it }
        addPalette(v.findViewById(R.id.pal_pad), { settings.padColor }) { settings.padColor = it }
        addPalette(v.findViewById(R.id.pal_key), { settings.keyGlowColor }) { settings.keyGlowColor = it }

        // Размер тачпада
        val (sw, sh) = screenSize()
        val maxW = (sw / density).toInt().coerceAtLeast(220)
        val maxH = (sh / density).toInt().coerceAtLeast(160)
        val sbW = v.findViewById<SeekBar>(R.id.sb_pad_w)
        val tvW = v.findViewById<TextView>(R.id.tv_pad_w)
        sbW.max = maxW - 220
        sbW.progress = (settings.padWidthDp - 220).coerceIn(0, maxW - 220)
        tvW.text = ctx.getString(R.string.set_pad_w) + ": " + settings.padWidthDp + " dp"
        sbW.setOnSeekBarChangeListener(seek { p ->
            settings.padWidthDp = 220 + p
            tvW.text = ctx.getString(R.string.set_pad_w) + ": " + settings.padWidthDp + " dp"
        })
        val sbH = v.findViewById<SeekBar>(R.id.sb_pad_h)
        val tvH = v.findViewById<TextView>(R.id.tv_pad_h)
        sbH.max = maxH - 160
        sbH.progress = (settings.padHeightDp - 160).coerceIn(0, maxH - 160)
        tvH.text = ctx.getString(R.string.set_pad_h) + ": " + settings.padHeightDp + " dp"
        sbH.setOnSeekBarChangeListener(seek { p ->
            settings.padHeightDp = 160 + p
            tvH.text = ctx.getString(R.string.set_pad_h) + ": " + settings.padHeightDp + " dp"
        })

        // Звук
        val cb = v.findViewById<CheckBox>(R.id.cb_sound)
        cb.isChecked = settings.soundOn
        cb.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            settings.soundOn = checked
            onChanged()
        }

        v.findViewById<View>(R.id.btn_done).setOnClickListener {
            onSave()
            hide()
        }
        v.findViewById<View>(R.id.btn_close).setOnClickListener {
            rollback()
            hide()
        }
        binding = false
        return v
    }

    private fun seek(update: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (binding || !fromUser) return
            update(progress)
            onChanged()
        }

        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun refreshStyle() {
        styleButtons.forEachIndexed { i, b ->
            b.setTextColor(if (i == settings.cursorStyle) Color.WHITE else Color.parseColor("#00BFFF"))
            b.alpha = if (i == settings.cursorStyle) 1f else 0.6f
        }
    }

    private fun addPalette(container: LinearLayout, current: () -> Int, set: (Int) -> Unit) {
        container.removeAllViews()
        val perRow = 6
        val sizePx = (34 * density).toInt()
        val margin = (3 * density).toInt()
        palette.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { color ->
                val sw = View(ctx)
                sw.layoutParams = LinearLayout.LayoutParams(0, sizePx, 1f).apply {
                    setMargins(margin, margin, margin, margin)
                }
                sw.tag = color
                sw.setOnClickListener {
                    set(color)
                    refreshPalettes()
                    onChanged()
                }
                row.addView(sw)
            }
            container.addView(row)
        }
        palettes.add(Pair(container, current))
        refreshPalettes()
    }

    private fun refreshPalettes() {
        for ((container, current) in palettes) {
            val cur = current()
            for (r in 0 until container.childCount) {
                val row = container.getChildAt(r) as LinearLayout
                for (i in 0 until row.childCount) {
                    val sw = row.getChildAt(i)
                    val color = sw.tag as Int
                    sw.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8 * density
                        setColor(color)
                        if ((color or 0xFF000000.toInt()) == (cur or 0xFF000000.toInt())) {
                            setStroke((3 * density).toInt(), Color.WHITE)
                        } else {
                            setStroke((1 * density).toInt(), Color.parseColor("#55FFFFFF"))
                        }
                    }
                }
            }
        }
    }

    /** «Закрыть»: вернуть значения, сохранённые в PreferenceHelper. */
    private fun rollback() {
        val o = PreferenceHelper.load(ctx)
        settings.sensitivity = o.sensitivity
        settings.cursorSizeDp = o.cursorSizeDp
        settings.cursorColor = o.cursorColor
        settings.cursorStyle = o.cursorStyle
        settings.glowColor = o.glowColor
        settings.padWidthDp = o.padWidthDp
        settings.padHeightDp = o.padHeightDp
        settings.padX = o.padX
        settings.padY = o.padY
        settings.padLocked = o.padLocked
        settings.buttonX = o.buttonX
        settings.buttonY = o.buttonY
        settings.padColor = o.padColor
        settings.keyGlowColor = o.keyGlowColor
        settings.soundOn = o.soundOn
        settings.language = o.language
        onChanged()
    }

    fun show() {
        if (attached) return
        val v = build()
        val (sw, sh) = screenSize()
        val w = minOf((360 * density).toInt(), sw - (24 * density).toInt())
        val h = (sh * 0.8f).toInt()
        val lp = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.CENTER
        try {
            wm.addView(v, lp)
            root = v
            attached = true
        } catch (_: Exception) {
        }
    }

    fun hide() {
        if (!attached) return
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
        root = null
        attached = false
    }
}
