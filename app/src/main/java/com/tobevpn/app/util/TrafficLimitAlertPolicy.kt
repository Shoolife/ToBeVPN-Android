package com.tobevpn.app.util

import com.tobevpn.app.data.local.TrafficLimitAlertState
import com.tobevpn.app.domain.model.UsageInfo
import kotlin.math.max

internal enum class TrafficRemainingThreshold(
    val percent: Int,
    val mask: Long,
) {
    TWENTY(percent = 20, mask = 1L),
    TEN(percent = 10, mask = 1L shl 1),
    FIVE(percent = 5, mask = 1L shl 2),
}

internal data class TrafficLimitAlertEvaluation(
    val state: TrafficLimitAlertState,
    val thresholdToNotify: TrafficRemainingThreshold? = null,
)

/**
 * Selects at most one alert for an observation. If usage jumps over several
 * boundaries between server synchronisations, only the most urgent crossed
 * boundary is shown and all less urgent boundaries are marked as handled.
 */
internal fun evaluateTrafficLimitAlert(
    usage: UsageInfo,
    previous: TrafficLimitAlertState,
): TrafficLimitAlertEvaluation {
    if (usage.bytesLimit <= 0L) {
        return TrafficLimitAlertEvaluation(TrafficLimitAlertState())
    }

    val used = usage.bytesUsed.coerceIn(0L, usage.bytesLimit)
    val resetTolerance = max(ONE_GIB, usage.bytesLimit / 20L)
    val usageCycleRestarted = previous.limitBytes != usage.bytesLimit ||
        previous.lastUsedBytes - used >= resetTolerance
    val startingMask = if (usageCycleRestarted) 0L else previous.notifiedThresholdMask
    val remainingRatio = (usage.bytesLimit - used).toDouble() / usage.bytesLimit.toDouble()
    val reached = TrafficRemainingThreshold.entries.filter { threshold ->
        remainingRatio <= threshold.percent / 100.0
    }
    val reachedMask = reached.fold(0L) { mask, threshold -> mask or threshold.mask }
    val newlyReached = reached.filter { threshold -> startingMask and threshold.mask == 0L }
    val thresholdToNotify = newlyReached.minByOrNull(TrafficRemainingThreshold::percent)

    return TrafficLimitAlertEvaluation(
        state = TrafficLimitAlertState(
            limitBytes = usage.bytesLimit,
            lastUsedBytes = used,
            notifiedThresholdMask = startingMask or reachedMask,
        ),
        thresholdToNotify = thresholdToNotify,
    )
}

private const val ONE_GIB = 1024L * 1024L * 1024L
