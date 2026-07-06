package com.tobevpn.app.domain.model

/** How the text under the account avatar is shown. */
enum class ProfileNameDisplay {
    /** @username only. */
    USERNAME,

    /** Full name only. */
    NAME,

    /** Two lines: name on top, @username below. */
    BOTH,

    /** One line that cross-fades between the name and @username. */
    ANIMATED,
    ;

    companion object {
        val DEFAULT = ANIMATED

        fun fromName(value: String?): ProfileNameDisplay =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
