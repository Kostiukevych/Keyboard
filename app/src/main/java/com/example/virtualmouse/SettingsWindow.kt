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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Оверлей-диалог настроек в стиле HTML modal box:
 * Скорость, размер, стиль курсора, 3 палитры по 12 3D-пузырьков neonColors,
 * переключатель звука и кнопка Готово.
 */
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
    private val sounds = SoundManager.get(ctx) { settings.soundOn }

    private val neonColors = listOf(
        "#2E7BFF", "#00F0FF", "#FF00AA", "#00FF88", "#FFEE00", "#FF4400",
        "#AA00FF", "#FF66CC", "#66FF00", "#FFAA00", "#00FFCC", "#FFFFFF"
    ).map { Color.parseColor(it) }

    private val styleButtons = ArrayList<TextView>()
    private val palettes = ArrayList<Pair<LinearLayout, () -> Int>>()

    private fun dp(v: Int) = (v * density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    private fun build(): View {
        val themed = ContextThemeWrapper(ctx, R.style.Theme_VirtualMouse)
        val v = LayoutInflater.from(themed).inflate(R.layout.dialog_settings, null)
        binding = true

        // Скорость курсора 0.8 .. 4.0 (шаг 0.1)
        val sbSpeed = v.findViewById<SeekBar>(R.id.sb_speed)
        val tvSpeed = v.findViewById<TextView>(R.id.tv_speed)
        sbSpeed.max = 32
        sbSpeed.progress = Math.round((settings.sensitivity - 0.8f) * 10f).coerceIn(0, 32)
        tvSpeed.text = String.format("%.1f×", settings.sensitivity)
        sbSpeed.setOnSeekBarChangeListener(seek { p ->
            settings.sensitivity = 0.8f + p / 10f
            tvSpeed.text = String.format("%.1f×", settings.sensitivity)
        })

        // Размер курсора 32 .. 64 px
        val sbSize = v.findViewById<SeekBar>(R.id.sb_size)
        val tvSize = v.findViewById<TextView>(R.id.tv_size)
        sbSize.max = 32
        sbSize.progress = (settings.cursorSizeDp - 32).coerceIn(0, 32)
        tvSize.text = "${settings.cursorSizeDp} px"
        sbSize.setOnSeekBarChangeListener(seek { p ->
            settings.cursorSizeDp = 32 + p
            tvSize.text = "${settings.cursorSizeDp} px"
        })

        // Стиль курсора
        styleButtons.clear()
        listOf(R.id.st_arrow, R.id.st_cross, R.id.st_dot, R.id.st_hand).forEachIndexed { i, id ->
            val b = v.findViewById<TextView>(id)
            styleButtons.add(b)
            b.setOnClickListener {
                sounds.beep(340)
                settings.cursorStyle = i
                refreshStyle()
                onChanged()
            }
        }
        refreshStyle()

        // 3D-палитры Bubble (12 цветов в 2 ряда по 6)
        palettes.clear()
        addBubblePalette(v.findViewById(R.id.pal_cursor), { settings.cursorColor }) {
            settings.cursorColor = it
        }
        addBubblePalette(v.findViewById(R.id.pal_pad), { settings.padColor }) {
            settings.padColor = it
            // При изменении цвета корпуса подтягиваем подсветку по умолчанию
            settings.keyGlowColor = it
            refreshPalettes()
        }
        addBubblePalette(v.findViewById(R.id.pal_key), { settings.keyGlowColor }) {
            settings.keyGlowColor = it
        }

        // Размеры тачпада
        val (sw, sh) = screenSize()
        val maxW = (sw / density).toInt().coerceAtLeast(220)
        val maxH = (sh / density).toInt().coerceAtLeast(160)
        val sbW = v.findViewById<SeekBar>(R.id.sb_pad_w)
        val tvW = v.findViewById<TextView>(R.id.tv_pad_w)
        sbW.max = maxW - 220
        sbW.progress = (settings.padWidthDp - 220).coerceIn(0, maxW - 220)
        tvW.text = "${settings.padWidthDp} dp"
        sbW.setOnSeekBarChangeListener(seek { p ->
            settings.padWidthDp = 220 + p
            tvW.text = "${settings.padWidthDp} dp"
        })

        val sbH = v.findViewById<SeekBar>(R.id.sb_pad_h)
        val tvH = v.findViewById<TextView>(R.id.tv_pad_h)
        sbH.max = maxH - 160
        sbH.progress = (settings.padHeightDp - 160).coerceIn(0, maxH - 160)
        tvH.text = "${settings.padHeightDp} dp"
        sbH.setOnSeekBarChangeListener(seek { p ->
            settings.padHeightDp = 160 + p
            tvH.text = "${settings.padHeightDp} dp"
        })

        // Звук нажатий
        val cbSound = v.findViewById<CheckBox>(R.id.cb_sound)
        cbSound.isChecked = settings.soundOn
        cbSound.setOnCheckedChangeListener { _, checked ->
            settings.soundOn = checked
            sounds.beep(if (checked) 380 else 180)
            onChanged()
        }

        // Кнопка Готово
        v.findViewById<View>(R.id.btn_done).setOnClickListener {
            sounds.beep(180)
            saveAndClose()
        }

        binding = false
        refreshPalettes()
        return v
    }

    private fun addBubblePalette(container: LinearLayout, getCur: () -> Int, setCur: (Int) -> Unit) {
        container.removeAllViews()
        palettes.add(Pair(container, getCur))

        // 2 ряда по 6 пузырьков
        neonColors.chunked(6).forEach { rowColors ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(3), 0, dp(3)) }
            }

            rowColors.forEach { c ->
                val bubble = FrameLayout(ctx).apply {
                    val size = dp(34)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins(dp(5), 0, dp(5), 0)
                    }
                    tag = c

                    // Внутренний шар с 3D-градиентом
                    background = createBubbleDrawable(c, c == getCur())

                    setOnClickListener {
                        sounds.beep(420)
                        setCur(c)
                        refreshPalettes()
                        onChanged()
                    }
                }
                row.addView(bubble)
            }
            container.addView(row)
        }
    }

    private fun createBubbleDrawable(color: Int, isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(
                ColorUtils.blendARGB(color, Color.WHITE, 0.4f),
                color,
                ColorUtils.blendARGB(color, Color.BLACK, 0.5f)
            )
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(24).toFloat()
            if (isSelected) {
                setStroke(dp(3), Color.WHITE)
            } else {
                setStroke(dp(1), ColorUtils.setAlphaComponent(Color.WHITE, 0x66))
            }
        }
    }

    private fun refreshPalettes() {
        palettes.forEach { (box, getCur) ->
            val cur = getCur()
            for (i in 0 until box.childCount) {
                val row = box.getChildAt(i) as? LinearLayout ?: continue
                for (j in 0 until row.childCount) {
                    val bubble = row.getChildAt(j) as? FrameLayout ?: continue
                    val c = bubble.tag as? Int ?: continue
                    val selected = c == cur
                    bubble.background = createBubbleDrawable(c, selected)
                    bubble.scaleX = if (selected) 1.15f else 1.0f
                    bubble.scaleY = if (selected) 1.15f else 1.0f
                }
            }
        }
    }

    private fun refreshStyle() {
        val s = settings.cursorStyle
        styleButtons.forEachIndexed { i, b ->
            val sel = i == s
            b.isSelected = sel
            b.setTextColor(if (sel) Color.WHITE else Color.parseColor("#7A8C9E"))
        }
    }

    private fun seek(onProg: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (!fromUser || binding) return
            onProg(p)
            onChanged()
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    fun show() {
        if (attached) return
        val v = build()
        root = v
        val (sw, sh) = screenSize()
        val lp = WindowManager.LayoutParams(
            (sw * 0.92f).toInt().coerceAtMost(dp(380)),
            (sh * 0.85f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            wm.addView(v, lp)
            attached = true
        } catch (_: Exception) {
        }
    }

    fun hide() {
        if (!attached) return
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        attached = false
    }

    private fun saveAndClose() {
        onSave()
        hide()
    }
}
