package com.tobevpn.app.presentation.referrals

import com.google.gson.Gson
import com.tobevpn.app.data.remote.dto.ReferralListItemDto
import com.tobevpn.app.data.remote.dto.ReferralsDto
import com.tobevpn.app.data.remote.dto.SetReferrerRequestDto
import com.tobevpn.app.data.remote.dto.SetReferrerResponseDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferralContractTest {

    private val gson = Gson()

    @Test
    fun parsesLegacyRemnashopReferalsSpelling() {
        val data = gson.fromJson(
            """
            {
              "referral_code": "ABC123",
              "referral_url": "https://t.me/example?start=ref_ABC123",
              "total": 1,
              "referals": [
                {
                  "telegram_id": 42,
                  "display_name": "Friend",
                  "level": 1,
                  "created_at": "2026-07-22T10:00:00Z"
                }
              ],
              "limit": 20,
              "offset": 0
            }
            """.trimIndent(),
            ReferralsDto::class.java,
        )

        assertEquals("ABC123", data.referralCode)
        assertEquals(1, data.referrals.orEmpty().size)
        assertEquals("Friend", data.referrals.orEmpty().single().displayName)
    }

    @Test
    fun parsesCorrectedReferralsSpelling() {
        val data = gson.fromJson(
            """
            {
              "referral_code": "ABC123",
              "referral_url": "https://t.me/example?start=ref_ABC123",
              "total": 1,
              "referrals": [
                {
                  "telegram_id": 84,
                  "display_name": "Another friend",
                  "level": 2
                }
              ],
              "limit": 20,
              "offset": 0
            }
            """.trimIndent(),
            ReferralsDto::class.java,
        )

        assertEquals(1, data.referrals.orEmpty().size)
        assertEquals(2, data.referrals.orEmpty().single().level)
    }

    @Test
    fun setReferrerContractUsesTelegramIdField() {
        val requestJson = gson.toJson(SetReferrerRequestDto(referrerId = 123456789L))
        val response = gson.fromJson(
            """{"referrer_id":123456789,"created":true}""",
            SetReferrerResponseDto::class.java,
        )

        assertEquals("""{"referrer_id":123456789}""", requestJson)
        assertEquals(123456789L, response.referrerId)
        assertEquals(true, response.created)
    }

    @Test
    fun mergeReferralPagesKeepsOrderAndDropsOverlap() {
        val firstItem = ReferralListItemDto(
            telegramId = 1,
            displayName = "First",
            createdAt = "2026-07-22T10:00:00Z",
        )
        val secondItem = ReferralListItemDto(
            telegramId = 2,
            displayName = "Second",
            createdAt = "2026-07-21T10:00:00Z",
        )
        val current = ReferralsDto(
            referralCode = "ABC123",
            referralUrl = "https://t.me/example?start=ref_ABC123",
            total = 2,
            referrals = listOf(firstItem),
            limit = 1,
            offset = 0,
        )
        val next = ReferralsDto(
            total = 2,
            referrals = listOf(firstItem, secondItem),
            limit = 2,
            offset = 1,
        )

        val merged = mergeReferralPages(current, next)

        assertEquals(listOf(firstItem, secondItem), merged.referrals)
        assertEquals("ABC123", merged.referralCode)
        assertEquals(2, merged.total)
    }
}
