package com.example.virtualmouse

import android.graphics.Color

/**
 * Общие настройки. Хранятся через PreferenceHelper, читаются и оверлеем, и клавиатурой (IME).
 * Цвета соответствуют палитре neonColors из HTML-дизайна.
 */
data class CursorSettings(
    var sensitivity: Float = 2.1f,                       // 0.8 .. 4.0 (HTML default 2.1)
    var cursorSizeDp: Int = 48,                          // 32 .. 64 (HTML default 48)
    var cursorColor: Int = Color.parseColor("#00F0FF"),  // Цвет курсора (--cc)
    var cursorStyle: Int = 0,                            // 0=arrow (SVG HTML style)
    var glowColor: Int = Color.parseColor("#2E7BFF"),    // Свечение курсора (--glow)
    var padWidthDp: Int = 320,                           // Ширина тачпада
    var padHeightDp: Int = 240,                          // Высота тачпада
    var padX: Int = -1,                                  // Левый верх окна тачпада
    var padY: Int = -1,
    var padLocked: Boolean = false,                      // Окно тачпада зафиксировано
    var buttonX: Int = -1,                               // Позиция плавающей кнопки
    var buttonY: Int = -1,
    var padColor: Int = Color.parseColor("#2E7BFF"),     // Цвет темы корпуса тачпада и клавиатуры (--acc)
    var keyGlowColor: Int = Color.parseColor("#2E7BFF"), // Подсветка букв и цифр (--glow)
    var soundOn: Boolean = true,                         // Звук нажатий
    var language: Int = 1                                // 0=EN, 1=RU, 2=UK
)
