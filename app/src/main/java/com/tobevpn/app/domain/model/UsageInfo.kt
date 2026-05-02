package com.tobevpn.app.domain.model

data class UsageInfo(
    val bytesUsed: Long = 0,
    val bytesLimit: Long = 0, // 0 = unlimited
    val timeUsedSeconds: Long = 0,
    val timeLimitSeconds: Long = 0, // 0 = unlimited
) {
    val isUnlimitedTraffic: Boolean get() = bytesLimit <= 0
    val isUnlimitedTime: Boolean get() = timeLimitSeconds <= 0
    val bytesRemaining: Long get() = if (isUnlimitedTraffic) Long.MAX_VALUE else (bytesLimit - bytesUsed).coerceAtLeast(0)
    val timeRemainingSeconds: Long get() = if (isUnlimitedTime) Long.MAX_VALUE else (timeLimitSeconds - timeUsedSeconds).coerceAtLeast(0)
    val isExhausted: Boolean get() = (!isUnlimitedTraffic && bytesRemaining <= 0) || (!isUnlimitedTime && timeRemainingSeconds <= 0)
    val trafficProgress: Float get() = if (isUnlimitedTraffic) 0f else (bytesUsed.toFloat() / bytesLimit).coerceIn(0f, 1f)
    val timeProgress: Float get() = if (isUnlimitedTime) 0f else (timeUsedSeconds.toFloat() / timeLimitSeconds).coerceIn(0f, 1f)
}
