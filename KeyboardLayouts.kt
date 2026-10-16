package com.example.virtualmouse

/** Данные слоёв клавиатуры (из KEYBOARD_REF.md). Язык: 0=EN, 1=RU, 2=UK. */
object KeyboardLayouts {
    const val L_LETTERS = "letters"
    const val L_SYMBOLS = "symbols"
    const val L_MORE = "more"
    const val L_EMOJI = "emoji"
    const val L_UNICODE = "unicode"

    /**
     * code пустой = вставка текста (text); иначе служебная клавиша:
     * ESC, F1..F12, BKSP, TAB, CAPS, ENTER, SHIFT, CTRL, ALT, WIN, SPACE, CLIP,
     * LEFT/RIGHT/UP/DOWN, HOME, END, PGUP, PGDN, DEL, L:<слой>.
     * latin = латинская клавиша на этом месте (для Ctrl-сочетаний и малой подписи).
     */
    data class Key(
        val label: String,
        val code: String = "",
        val weight: Float = 1f,
        val latin: String = "",
        val text: String = label
    )

    data class Row(val keys: List<Key>, val fRow: Boolean = false)

    private fun t(s: String) = Key(s)
    private fun c(code: String, label: String, w: Float = 1f) = Key(label, code, w)
    private fun chars(line: String) = line.split(" ").filter { it.isNotEmpty() }.map { t(it) }

    private const val RU3 = "й ц у к е н г ш щ з х ъ"
    private const val EN3 = "q w e r t y u i o p [ ]"
    private const val RU4 = "ф ы в а п р о л д ж э"
    private const val EN4 = "a s d f g h j k l ; '"
    private const val RU5 = "я ч с м и т ь б ю ."
    private const val EN5 = "z x c v b n m , . /"

    private fun uk(ch: String, lang: Int) = if (lang != 2) ch else when (ch) {
        "ъ" -> "ї"; "ы" -> "і"; "э" -> "є"; else -> ch
    }

    private fun letterRow(ru: String, en: String, lang: Int): List<Key> {
        val r = ru.split(" ")
        val e = en.split(" ")
        return r.indices.map { i -> Key(if (lang == 0) e[i] else uk(r[i], lang), latin = e[i]) }
    }

    private fun fRow() = Row(
        listOf(c("ESC", "Esc")) + (1..12).map { c("F$it", "F$it") } + c("CLIP", "\uD83D\uDCCB", 1.5f),
        true
    )

    private fun bottom(vararg k: Key) = Row(k.toList())

    private fun mainBottom(other: Key) = bottom(
        c("CTRL", "Ctrl", 1.4f), c("ALT", "Alt"), c("SPACE", "Space", 4f),
        c("L:emoji", "\uD83D\uDE0A"), c("L:unicode", "\u2318"),
        c("L:letters", "\u0410\u0411\u0412", 1.4f), other, c("ENTER", "Enter", 1.6f)
    )

    fun letters(lang: Int): List<Row> = listOf(
        fRow(),
        Row(
            listOf(Key(when (lang) { 0 -> "`"; 1 -> "\u0451"; else -> "\u0027" }, latin = "`")) +
                "1 2 3 4 5 6 7 8 9 0 - =".split(" ").map { Key(it, latin = it) } +
                c("BKSP", "\u232B", 1.5f)
        ),
        Row(listOf(c("TAB", "Tab", 2.5f)) + letterRow(RU3, EN3, lang)),
        Row(listOf(c("CAPS", "Caps", 1.75f)) + letterRow(RU4, EN4, lang) + c("ENTER", "Enter", 1.75f)),
        Row(listOf(c("SHIFT", "Shift", 2.25f)) + letterRow(RU5, EN5, lang) + c("SHIFT", "Shift", 2.25f)),
        bottom(
            c("CTRL", "Ctrl", 1.4f), c("WIN", "Win"), c("ALT", "Alt"), c("SPACE", "Space", 6.3f),
            c("L:unicode", "\u2318"), c("L:symbols", "?123", 1.4f), c("ALT", "Alt"), c("CTRL", "Ctrl", 1.4f)
        )
    )

    fun symbols(): List<Row> = listOf(
        fRow(),
        Row(chars("1 2 3 4 5 6 7 8 9 0 \u2116 %") + c("BKSP", "\u232B", 2.5f)),
        Row(chars("+ \u2212 = / \u00D7 ( ) [ ] { } & _")),
        Row(chars("\u20AC \$ \u20BD \u00A3 \u00A5 \u20B4 \u20B9 \u20A9 \u20BA \u20AA \u00A2 \u20BF")),
        Row(
            listOf(c("LEFT", "\u2190"), c("RIGHT", "\u2192"), c("UP", "\u2191"), c("DOWN", "\u2193")) +
                chars("! ? , . ; : ' \"")
        ),
        mainBottom(c("L:more", "#+=", 1.4f))
    )

    fun more(): List<Row> = listOf(
        Row(listOf(c("CLIP", "\uD83D\uDCCB", 1.5f), c("HOME", "Home"), c("END", "End"), c("PGUP", "PgUp"), c("PGDN", "PgDn"), c("DEL", "Del"))),
        Row(chars("~ ` ^ \u00B0 \u221A \u03C0 \u0394 \u2211 \u2260 \u2264 \u2265 \u221E") + c("BKSP", "\u232B", 1.5f)),
        Row(chars("\u2022 \u00B7 \u2026 \u2013 \u2014 \u00AB \u00BB \u201E \u201C \u201D \u2018 \u2019")),
        Row(chars("\u00B6 \u2660 \u2665 \u2666 \u2663 \u2605 \u2606 \u2713 \u2717 \u26A1 \u2764 \u263A \u263B")),
        Row(chars("\u00B0C \u00B0F \u2116 \u00B5 \u03A9 \u03BB \u03B1 \u03B2 \u03B3 \u2192 \u2194 \u21C4")),
        mainBottom(c("L:symbols", "?123", 1.4f))
    )

    fun rows(layer: String, lang: Int): List<Row> = when (layer) {
        L_SYMBOLS -> symbols()
        L_MORE -> more()
        else -> letters(lang)
    }

    /** Нижний ряд для сеточных слоёв (emoji, unicode). */
    fun bottomRow(layer: String): Row = if (layer == L_EMOJI) {
        bottom(c("L:letters", "\u0410\u0411\u0412", 1.4f), c("L:symbols", "?123", 1.4f), c("SPACE", "Space", 5f), c("BKSP", "\u232B", 1.4f))
    } else {
        bottom(
            c("CTRL", "Ctrl", 1.2f), c("ALT", "Alt"), c("SPACE", "Space", 4f),
            c("L:letters", "\u0410\u0411\u0412", 1.4f), c("L:symbols", "?123", 1.4f), c("BKSP", "\u232B", 1.4f)
        )
    }

    // ---- Shift ----
    private val SHIFT_EN = mapOf(
        "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "\$", "5" to "%", "6" to "^",
        "7" to "&", "8" to "*", "9" to "(", "0" to ")", "-" to "_", "=" to "+", "[" to "{",
        "]" to "}", ";" to ":", "'" to "\"", "," to "<", "." to ">", "/" to "?"
    )
    private val SHIFT_RU = mapOf(
        "1" to "!", "2" to "\"", "3" to "\u2116", "4" to ";", "5" to "%", "6" to ":",
        "7" to "?", "8" to "*", "9" to "(", "0" to ")", "-" to "_", "=" to "+", "." to ","
    )

    /** Буквы: Shift или Caps дают заглавную; остальные символы меняет только Shift. */
    fun shifted(s: String, lang: Int, shift: Boolean, caps: Boolean): String {
        if (s.length != 1) return s
        if (s[0].isLetter()) return if (shift || caps) s.uppercase() else s
        if (!shift) return s
        return (if (lang == 0) SHIFT_EN else SHIFT_RU)[s] ?: s
    }

    // ---- Сетки ----
    private val EMOJI = """😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 🎃 😺 💋 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💯 💢 💥 💫 💦 💨 💬 💭 💤 👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 👃 🧠 🦷 🦴 👀 👁️ 👅 👄 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🥙 🧆 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🎣 🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 ✈️ 🛫 🛬 🛩️ 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🕹️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 💡 🔦 🕯️ 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️""".trim().split(" ").filter { it.isNotEmpty() }

    fun emojiKeys(): List<Key> = EMOJI.map { t(it) }

    private val BIDI = listOf(
        "RLO" to "\u202E", "LRO" to "\u202D", "RLM" to "\u200F", "LRM" to "\u200E",
        "RLE" to "\u202B", "LRE" to "\u202A", "PDF" to "\u202C", "FSI" to "\u2068",
        "PDI" to "\u2069", "RLI" to "\u2067", "LRI" to "\u2066", "ALM" to "\u061C"
    )

    private const val ARROWS = """← → ↑ ↓ ↔ ↕ ⇐ ⇒ ⇑ ⇓ ⇔ ⇕ ↖ ↗ ↘ ↙ ↺ ↻ ⇄ ⇆ ⇋ ⇌ ⇧ ⇩ ⏎ ⏏ ⏩ ⏪ ⏫ ⏬ ▶ ◀ ▲ ▼ ➡ ⬅ ⬆ ⬇ ➤ ➔ ➜ ➞ ➟ ➠ ⟶ ⟵ ⟷ ⟹"""
    private const val MATH = """± × ÷ ≠ ≈ ≡ ≤ ≥ < > ∞ √ ∑ ∏ ∫ ∂ ∇ ∆ π µ Ω λ α β γ δ ε θ φ ψ ω % ‰ ‱ ° ′ ″ ∅ ∈ ∉ ⊂ ⊃ ∪ ∩ ∨ ∧ ¬ ⊕"""
    private const val SPECIAL = """§ ¶ © ® ™ † ‡ • · … — – « » „ “ ” ‘ ’ ′ ″ ‴ ␣ ⌘ ⌥ ⇧ ⌫ ⎋ ⏎ ☰ ☱ ☲ ☳ ☴ ♠ ♥ ♦ ♣ ★ ☆ ✓ ✗ ⚡ ❤ ☺ ☻ № ⏳"""

    const val BIDI_TITLE = "Направление текста (BiDi)"

    fun unicodeSections(): List<Pair<String, List<Key>>> = listOf(
        BIDI_TITLE to BIDI.map { (name, ch) -> Key(name, text = ch) },
        "Стрелки и указатели" to chars(ARROWS),
        "Математика" to chars(MATH),
        "Спецсимволы" to chars(SPECIAL)
    )
}
