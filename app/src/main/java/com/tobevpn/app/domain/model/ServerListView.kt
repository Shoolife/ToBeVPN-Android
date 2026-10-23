package com.tobevpn.app.domain.model

/** Line under the server name. PROTOCOL_FULL is "VLESS · REALITY · XHTTP". */
enum class ServerSubtitle { COUNTRY, PROTOCOL, PROTOCOL_FULL, NONE }

/**
 * Which lines a server card shows. Admins pick it in Personalization ->
 * Server list view; everyone else always gets [USER]. Same model as the
 * desktop client (session/serverListView.ts).
 */
data class ServerListView(
    val subtitle: ServerSubtitle,
    /** Row with the server's address and port. */
    val endpoint: Boolean,
    /** Row with the protocol, its build and TCP/UDP. */
    val protocol: Boolean,
) {
    val hasDetailRows: Boolean get() = endpoint || protocol

    /** Stored as "COUNTRY,true,true"; anything unreadable gives the default. */
    fun encode(): String = "${subtitle.name},$endpoint,$protocol"

    companion object {
        val ADMIN_DEFAULT = ServerListPreset.DETAILED.view
        val USER = ServerListPreset.USER.view

        fun decode(raw: String?): ServerListView {
            val parts = raw?.split(',') ?: return ADMIN_DEFAULT
            if (parts.size != 3) return ADMIN_DEFAULT
            val subtitle = ServerSubtitle.entries.firstOrNull { it.name == parts[0] }
                ?: return ADMIN_DEFAULT
            val endpoint = parts[1].toBooleanStrictOrNull() ?: return ADMIN_DEFAULT
            val protocol = parts[2].toBooleanStrictOrNull() ?: return ADMIN_DEFAULT
            return ServerListView(subtitle, endpoint, protocol)
        }
    }
}

enum class ServerListPreset(val view: ServerListView) {
    /** Everything an admin may need to tell servers apart (the default). */
    DETAILED(ServerListView(ServerSubtitle.COUNTRY, endpoint = true, protocol = true)),
    /** Country and how the profile is built, without the address. */
    CONDENSED(ServerListView(ServerSubtitle.COUNTRY, endpoint = false, protocol = true)),
    /** The full protocol in the country's place, and the address. */
    TECHNICAL(ServerListView(ServerSubtitle.PROTOCOL_FULL, endpoint = true, protocol = false)),
    /** Country and address, without the protocol. */
    ADDRESS(ServerListView(ServerSubtitle.COUNTRY, endpoint = true, protocol = false)),
    /** Exactly what users see. */
    USER(ServerListView(ServerSubtitle.PROTOCOL, endpoint = false, protocol = false)),
    /** One line per server: the longest lists fit on screen. */
    COMPACT(ServerListView(ServerSubtitle.NONE, endpoint = false, protocol = false)),
    ;

    companion object {
        fun matching(view: ServerListView): ServerListPreset? = entries.firstOrNull { it.view == view }
    }
}
