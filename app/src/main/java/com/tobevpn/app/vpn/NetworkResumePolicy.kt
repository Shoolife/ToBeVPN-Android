package com.tobevpn.app.vpn

/** Pure guard for the one-shot same-server resume after physical network loss. */
internal object NetworkResumePolicy {
    fun shouldResume(
        expectedRequest: Int,
        currentRequest: Int,
        hasNetworkTimeoutError: Boolean,
        sameServer: Boolean,
        availability: UnderlyingNetworkAvailability,
    ): Boolean =
        expectedRequest == currentRequest &&
            hasNetworkTimeoutError &&
            sameServer &&
            UnderlyingNetworkPolicy.canAttemptTunnelProbe(availability)
}
