package com.tobevpn.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResumePolicyTest {

    @Test
    fun `same server resumes when validated network returns and request is unchanged`() {
        assertTrue(
            NetworkResumePolicy.shouldResume(
                expectedRequest = 7,
                currentRequest = 7,
                hasNetworkTimeoutError = true,
                sameServer = true,
                availability = UnderlyingNetworkAvailability.VALIDATED,
            ),
        )
    }

    @Test
    fun `manual user action invalidates pending resume`() {
        assertFalse(
            NetworkResumePolicy.shouldResume(
                expectedRequest = 7,
                currentRequest = 8,
                hasNetworkTimeoutError = true,
                sameServer = true,
                availability = UnderlyingNetworkAvailability.VALIDATED,
            ),
        )
    }

    @Test
    fun `different error or server cannot trigger hidden reconnect`() {
        assertFalse(
            NetworkResumePolicy.shouldResume(7, 7, false, true, UnderlyingNetworkAvailability.VALIDATED),
        )
        assertFalse(
            NetworkResumePolicy.shouldResume(7, 7, true, false, UnderlyingNetworkAvailability.VALIDATED),
        )
    }

    @Test
    fun `unvalidated carrier network can restart vpn for tunnel validation`() {
        assertTrue(
            NetworkResumePolicy.shouldResume(
                expectedRequest = 7,
                currentRequest = 7,
                hasNetworkTimeoutError = true,
                sameServer = true,
                availability = UnderlyingNetworkAvailability.UNVALIDATED,
            ),
        )
    }

    @Test
    fun `missing physical network cannot restart vpn`() {
        assertFalse(
            NetworkResumePolicy.shouldResume(
                expectedRequest = 7,
                currentRequest = 7,
                hasNetworkTimeoutError = true,
                sameServer = true,
                availability = UnderlyingNetworkAvailability.UNAVAILABLE,
            ),
        )
    }
}
