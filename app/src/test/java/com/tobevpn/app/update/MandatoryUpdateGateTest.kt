package com.tobevpn.app.update

import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class MandatoryUpdateGateTest {

    @Test
    fun `available immediate update starts Play flow`() {
        assertEquals(
            MandatoryPlayUpdateAction.START_IMMEDIATE_UPDATE,
            mandatoryPlayUpdateAction(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                immediateUpdateAllowed = true,
            ),
        )
    }

    @Test
    fun `interrupted immediate update resumes Play flow`() {
        assertEquals(
            MandatoryPlayUpdateAction.START_IMMEDIATE_UPDATE,
            mandatoryPlayUpdateAction(
                updateAvailability = UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                immediateUpdateAllowed = true,
            ),
        )
    }

    @Test
    fun `unavailable immediate update falls back to store`() {
        assertEquals(
            MandatoryPlayUpdateAction.SHOW_STORE_FALLBACK,
            mandatoryPlayUpdateAction(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                immediateUpdateAllowed = false,
            ),
        )
    }

    @Test
    fun `no Play update falls back to store`() {
        assertEquals(
            MandatoryPlayUpdateAction.SHOW_STORE_FALLBACK,
            mandatoryPlayUpdateAction(
                updateAvailability = UpdateAvailability.UPDATE_NOT_AVAILABLE,
                immediateUpdateAllowed = true,
            ),
        )
    }
}
