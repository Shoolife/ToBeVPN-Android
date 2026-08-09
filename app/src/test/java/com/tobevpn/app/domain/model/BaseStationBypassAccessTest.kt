package com.tobevpn.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseStationBypassAccessTest {

    @Test
    fun `anonymous user must authenticate`() {
        val state = AuthState.Anonymous

        assertEquals(BaseStationBypassAccess.AUTH_REQUIRED, state.baseStationBypassAccess())
        assertFalse(state.canUseBaseStationBypass())
    }

    @Test
    fun `authenticated trial user is allowed`() {
        val state = authenticated(UserPlan.FREE_TRIAL)

        assertEquals(BaseStationBypassAccess.ALLOWED, state.baseStationBypassAccess())
        assertTrue(state.canUseBaseStationBypass())
    }

    @Test
    fun `paid and admin users are allowed`() {
        assertTrue(authenticated(UserPlan.PAID).canUseBaseStationBypass())
        assertTrue(authenticated(UserPlan.ADMIN).canUseBaseStationBypass())
    }

    @Test
    fun `expired authenticated user is rejected`() {
        val state = authenticated(UserPlan.EXPIRED)

        assertEquals(BaseStationBypassAccess.ACCESS_EXPIRED, state.baseStationBypassAccess())
        assertFalse(state.canUseBaseStationBypass())
    }

    private fun authenticated(plan: UserPlan) = AuthState.Authenticated(
        telegramId = 42L,
        plan = plan,
    )
}
