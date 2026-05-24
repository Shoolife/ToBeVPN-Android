package com.tobevpn.app.presentation.components

private const val GLOBE_FLAG = "\uD83C\uDF10"
private const val REGIONAL_INDICATOR_START = 0x1F1E6
private const val REGIONAL_INDICATOR_END = 0x1F1FF
private const val FLAG_CODE_POINTS = 2

fun countryFlagForUi(countryCode: String, serverName: String): String {
    val displayCountryCode = serverCountryCodeForUi(countryCode, serverName)
    return countryFlagOrNull(displayCountryCode)
        ?: parseLeadingPrefix(serverName).flag
        ?: GLOBE_FLAG
}

fun serverCountryCodeForUi(countryCode: String, serverName: String): String {
    val normalizedCode = countryCode.trim().uppercase()
    if (countryFlagOrNull(normalizedCode) != null) return normalizedCode
    return countryCodeFromFlag(parseLeadingPrefix(serverName).flag).orEmpty()
}

@Suppress("UNUSED_PARAMETER")
fun serverDisplayName(name: String, countryCode: String): String {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) return name

    val prefix = parseLeadingPrefix(trimmedName)

    val cleanedName = trimDecorativeEdges(removeFlagEmojis(trimmedName.substring(prefix.endIndex)))
    return cleanedName.ifEmpty { trimmedName }
}

private fun countryFlagOrNull(countryCode: String): String? {
    if (countryCode.length != 2) return null

    val normalizedCode = countryCode.uppercase()
    val firstChar = normalizedCode[0]
    val secondChar = normalizedCode[1]
    if (!firstChar.isLetter() || !secondChar.isLetter()) return null

    val first = 0x1F1E6 - 'A'.code + firstChar.code
    val second = 0x1F1E6 - 'A'.code + secondChar.code
    return String(intArrayOf(first, second), 0, 2)
}

private data class PrefixInfo(
    val endIndex: Int,
    val flag: String?,
)

private fun parseLeadingPrefix(text: String): PrefixInfo {
    val trimmedText = text.trimStart()
    if (trimmedText.isEmpty()) return PrefixInfo(endIndex = 0, flag = null)

    var index = 0
    var flag: String? = null

    while (index < trimmedText.length) {
        val codePoint = trimmedText.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) break

        if (isRegionalIndicator(codePoint)) {
            val nextIndex = index + Character.charCount(codePoint)
            if (nextIndex < trimmedText.length) {
                val nextCodePoint = trimmedText.codePointAt(nextIndex)
                if (isRegionalIndicator(nextCodePoint)) {
                    if (flag == null) {
                        flag = String(intArrayOf(codePoint, nextCodePoint), 0, FLAG_CODE_POINTS)
                    }
                    index = nextIndex + Character.charCount(nextCodePoint)
                    continue
                }
            }
        }

        if (!Character.isWhitespace(codePoint) && !isDecorativeCodePoint(codePoint)) break
        index += Character.charCount(codePoint)
    }

    return PrefixInfo(endIndex = index, flag = flag)
}

private fun countryCodeFromFlag(flag: String?): String? {
    if (flag.isNullOrEmpty()) return null

    val first = flag.codePointAt(0)
    val secondIndex = Character.charCount(first)
    if (secondIndex >= flag.length) return null

    val second = flag.codePointAt(secondIndex)
    if (!isRegionalIndicator(first) || !isRegionalIndicator(second)) return null

    val firstChar = (first - REGIONAL_INDICATOR_START + 'A'.code).toChar()
    val secondChar = (second - REGIONAL_INDICATOR_START + 'A'.code).toChar()
    return "$firstChar$secondChar"
}

private fun removeFlagEmojis(text: String): String {
    val output = StringBuilder(text.length)
    var index = 0

    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val nextIndex = index + Character.charCount(codePoint)
        if (isRegionalIndicator(codePoint) && nextIndex < text.length) {
            val nextCodePoint = text.codePointAt(nextIndex)
            if (isRegionalIndicator(nextCodePoint)) {
                index = nextIndex + Character.charCount(nextCodePoint)
                continue
            }
        }

        output.appendCodePoint(codePoint)
        index = nextIndex
    }

    return output.toString()
}

private fun trimDecorativeEdges(text: String): String =
    text.trim()
        .trim { it == '-' || it == '|' || it == '/' || it == '\\' || it == ':' }
        .trim()

private fun isRegionalIndicator(codePoint: Int): Boolean =
    codePoint in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END

private fun isDecorativeCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.OTHER_SYMBOL.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
    Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(),
    Character.NON_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    Character.FORMAT.toInt() -> true
    else -> false
}
