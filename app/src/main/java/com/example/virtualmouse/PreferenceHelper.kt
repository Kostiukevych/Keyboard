package com.example.virtualmouse

import android.content.Context

object PreferenceHelper {
    private const val PREFS = "virtual_mouse_prefs"
    private val D = CursorSettings()

    fun load(context: Context): CursorSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CursorSettings(
            sensitivity = p.getFloat("sensitivity", D.sensitivity),
            cursorSizeDp = p.getInt("cursorSizeDp", D.cursorSizeDp),
            cursorColor = p.getInt("cursorColor", D.cursorColor),
            cursorStyle = p.getInt("cursorStyle", D.cursorStyle),
            glowColor = p.getInt("glowColor", D.glowColor),
            padWidthDp = p.getInt("padWidthDp", D.padWidthDp),
            padHeightDp = p.getInt("padHeightDp", D.padHeightDp),
            padX = p.getInt("padX", D.padX),
            padY = p.getInt("padY", D.padY),
            padLocked = p.getBoolean("padLocked", D.padLocked),
            buttonX = p.getInt("buttonX", D.buttonX),
            buttonY = p.getInt("buttonY", D.buttonY),
            padColor = p.getInt("padColor", D.padColor),
            keyGlowColor = p.getInt("keyGlowColor", D.keyGlowColor),
            soundOn = p.getBoolean("soundOn", D.soundOn),
            language = p.getInt("language", D.language),
            padStyle = p.getInt("padStyle", D.padStyle),
            kbStyle = p.getInt("kbStyle", D.kbStyle),
            ballWidthDp = p.getInt("ballWidthDp", D.ballWidthDp),
            ballHeightDp = p.getInt("ballHeightDp", D.ballHeightDp),
            cursorShape = p.getString("cursorShape", D.cursorShape) ?: "",
            cursorFill = p.getString("cursorFill", D.cursorFill) ?: "fill",
            cursorGlow = p.getBoolean("cursorGlow", D.cursorGlow),
            tbState = p.getString("tbState", D.tbState) ?: ""
        )
    }

    fun save(context: Context, s: CursorSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putFloat("sensitivity", s.sensitivity)
            putInt("cursorSizeDp", s.cursorSizeDp)
            putInt("cursorColor", s.cursorColor)
            putInt("cursorStyle", s.cursorStyle)
            putInt("glowColor", s.glowColor)
            putInt("padWidthDp", s.padWidthDp)
            putInt("padHeightDp", s.padHeightDp)
            putInt("padX", s.padX)
            putInt("padY", s.padY)
            putBoolean("padLocked", s.padLocked)
            putInt("buttonX", s.buttonX)
            putInt("buttonY", s.buttonY)
            putInt("padColor", s.padColor)
            putInt("keyGlowColor", s.keyGlowColor)
            putBoolean("soundOn", s.soundOn)
            putInt("language", s.language)
            putInt("padStyle", s.padStyle)
            putInt("kbStyle", s.kbStyle)
            putInt("ballWidthDp", s.ballWidthDp)
            putInt("ballHeightDp", s.ballHeightDp)
            putString("cursorShape", s.cursorShape)
            putString("cursorFill", s.cursorFill)
            putBoolean("cursorGlow", s.cursorGlow)
            putString("tbState", s.tbState)
            apply()
        }
    }
}
