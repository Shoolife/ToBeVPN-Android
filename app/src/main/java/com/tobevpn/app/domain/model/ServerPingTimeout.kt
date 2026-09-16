package com.tobevpn.app.domain.model

const val MIN_SERVER_PING_TIMEOUT_SECONDS = 5
const val DEFAULT_SERVER_PING_TIMEOUT_SECONDS = 7
const val MAX_SERVER_PING_TIMEOUT_SECONDS = 15

fun normalizeServerPingTimeoutSeconds(value: Int): Int =
    value.coerceIn(MIN_SERVER_PING_TIMEOUT_SECONDS, MAX_SERVER_PING_TIMEOUT_SECONDS)
