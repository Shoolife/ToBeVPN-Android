package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnToggleSelectionPolicyTest {

    @Test
    fun `cold start keeps an existing automatic bypass profile without reselection`() {
        assertTrue(
            shouldKeepAutomaticSelectionOnRefresh(
                source = ServerSource.BASE_STATION_BYPASS,
                forceSelection = true,
                selectedStillAvailable = true,
            ),
        )
    }

    @Test
    fun `missing automatic bypass profile requires a new selection`() {
        assertFalse(
            shouldKeepAutomaticSelectionOnRefresh(
                source = ServerSource.BASE_STATION_BYPASS,
                forceSelection = true,
                selectedStillAvailable = false,
            ),
        )
    }

    @Test
    fun `forced standard refresh still performs standard reselection`() {
        assertFalse(
            shouldKeepAutomaticSelectionOnRefresh(
                source = ServerSource.STANDARD,
                forceSelection = true,
                selectedStillAvailable = true,
            ),
        )
    }
}
