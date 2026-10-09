package com.tobevpn.app.presentation.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/** Emoji drawn at this share of the text size. */
private const val EMOJI_SCALE = 0.95f

/**
 * Emoji in tariff names ("Комфорт 👌", "Корпорат 🧦") render taller than the
 * letters around them; drawn at 95% they read as part of the same line.
 * Used wherever a tariff name is shown.
 */
fun withSmallerEmoji(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val length = Character.charCount(codePoint)
        val piece = text.substring(index, index + length)
        if (isEmojiCodePoint(codePoint)) {
            withStyle(SpanStyle(fontSize = EMOJI_SCALE.em)) { append(piece) }
        } else {
            append(piece)
        }
        index += length
    }
}

private fun isEmojiCodePoint(codePoint: Int): Boolean =
    codePoint in 0x1F000..0x1FAFF || // pictographs, emoticons, skin tones
        codePoint in 0x2600..0x27BF || // symbols and dingbats
        codePoint in 0x2B00..0x2BFF ||
        codePoint == 0xFE0F || // emoji presentation selector
        codePoint == 0x200D // zero-width joiner in combined emoji
