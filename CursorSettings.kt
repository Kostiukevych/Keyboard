package com.example.virtualmouse

import android.graphics.Color

/** Общие настройки. Хранятся через PreferenceHelper, читаются и оверлеем, и клавиатурой (IME). */
data class CursorSettings(
    var sensitivity: Float = 2.0f,                       // 0.8 .. 4.0
    var cursorSizeDp: Int = 42,                          // 24 .. 96
    var cursorColor: Int = Color.WHITE,
    var cursorStyle: Int = 0,                            // 0=arrow, 1=cross, 2=dot, 3=hand
    var glowColor: Int = Color.parseColor("#00BFFF"),    // свечение курсора
    var padWidthDp: Int = 320,                           // 220 .. ширина экрана
    var padHeightDp: Int = 240,                          // 160 .. высота экрана
    var padX: Int = -1,                                  // левый верх окна тачпада, px; -1 = авто (низ по центру)
    var padY: Int = -1,
    var padLocked: Boolean = false,                      // окно тачпада зафиксировано
    var buttonX: Int = -1,                               // позиция плавающей кнопки, px; -1 = авто (правый край)
    var buttonY: Int = -1,
    var padColor: Int = Color.parseColor("#2E7BFF"),     // цвет корпуса тачпада и клавиатуры
    var keyGlowColor: Int = Color.parseColor("#00F0FF"), // подсветка букв и цифр
    var soundOn: Boolean = true,                         // звук нажатий
    var language: Int = 1                                // 0=EN, 1=RU, 2=UK
)
