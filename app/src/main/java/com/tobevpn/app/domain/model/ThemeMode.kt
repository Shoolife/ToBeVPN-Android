package com.tobevpn.app.domain.model

/** App theme preference. */
enum class ThemeMode {
    /** Follow the system setting. */
    SYSTEM,
    DARK,
    LIGHT,
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun fromName(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
