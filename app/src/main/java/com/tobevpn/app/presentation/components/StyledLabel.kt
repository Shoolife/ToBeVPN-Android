package com.tobevpn.app.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal fun highlightBetaWord(
    text: String,
    betaColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    val start = text.indexOf("beta", ignoreCase = true)
    if (start >= 0) {
        addStyle(
            style = SpanStyle(color = betaColor),
            start = start,
            end = start + "beta".length,
        )
    }
}
