package com.example.virtualmouse

/** Общий интерфейс окон управления курсором: тачпад и трекбол взаимозаменяемы. */
interface PadWindow {
    val isShown: Boolean
    fun show()
    fun hide()
    fun applySettings()
    fun destroy()
}
