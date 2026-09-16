package com.tobevpn.app.util

import com.tobevpn.app.data.local.TrafficLimitAlertState
import com.tobevpn.app.domain.model.UsageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficLimitAlertPolicyTest {
    @Test
    fun `notifies once at each remaining traffic threshold`() {
        var state = TrafficLimitAlertState()

        val above = evaluateTrafficLimitAlert(usage(usedPercent = 79), state)
        assertNull(above.thresholdToNotify)
        state = above.state

        val twenty = evaluateTrafficLimitAlert(usage(usedPercent = 80), state)
        assertEquals(TrafficRemainingThreshold.TWENTY, twenty.thresholdToNotify)
        state = twenty.state

        val duplicate = evaluateTrafficLimitAlert(usage(usedPercent = 85), state)
        assertNull(duplicate.thresholdToNotify)
        state = duplicate.state

        val ten = evaluateTrafficLimitAlert(usage(usedPercent = 90), state)
        assertEquals(TrafficRemainingThreshold.TEN, ten.thresholdToNotify)
        state = ten.state

        val five = evaluateTrafficLimitAlert(usage(usedPercent = 95), state)
        assertEquals(TrafficRemainingThreshold.FIVE, five.thresholdToNotify)
    }

    @Test
    fun `large usage jump emits only the most urgent crossed threshold`() {
        val evaluation = evaluateTrafficLimitAlert(
            usage(usedPercent = 96),
            TrafficLimitAlertState(),
        )

        assertEquals(TrafficRemainingThreshold.FIVE, evaluation.thresholdToNotify)
        assertEquals(7L, evaluation.state.notifiedThresholdMask)
    }

    @Test
    fun `renewed traffic allowance resets threshold history`() {
        val exhausted = evaluateTrafficLimitAlert(
            usage(usedPercent = 96),
            TrafficLimitAlertState(),
        ).state

        val renewed = evaluateTrafficLimitAlert(usage(usedPercent = 0), exhausted)
        assertNull(renewed.thresholdToNotify)
        assertEquals(0L, renewed.state.notifiedThresholdMask)

        val nextCycle = evaluateTrafficLimitAlert(usage(usedPercent = 80), renewed.state)
        assertEquals(TrafficRemainingThreshold.TWENTY, nextCycle.thresholdToNotify)
    }

    @Test
    fun `unlimited plan never emits an alert and clears old state`() {
        val evaluation = evaluateTrafficLimitAlert(
            UsageInfo(bytesUsed = LIMIT, bytesLimit = 0L),
            TrafficLimitAlertState(LIMIT, LIMIT, 7L),
        )

        assertNull(evaluation.thresholdToNotify)
        assertEquals(TrafficLimitAlertState(), evaluation.state)
    }

    private fun usage(usedPercent: Int): UsageInfo = UsageInfo(
        bytesUsed = LIMIT * usedPercent / 100,
        bytesLimit = LIMIT,
    )

    private companion object {
        const val LIMIT = 100L * 1024L * 1024L * 1024L
    }
}
