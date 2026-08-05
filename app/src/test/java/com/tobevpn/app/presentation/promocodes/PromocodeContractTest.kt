package com.tobevpn.app.presentation.promocodes

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.tobevpn.app.data.remote.dto.PromocodeActivateRequestDto
import com.tobevpn.app.data.remote.dto.PromocodeActivationResultDto
import com.tobevpn.app.data.remote.dto.PromocodeHistoryDto
import com.tobevpn.app.data.remote.dto.PromocodeHistoryItemDto
import com.tobevpn.app.data.repository.canonicalUuid4OrNull
import com.tobevpn.app.data.repository.shouldDiscardPendingPromocodeAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromocodeContractTest {

    private val gson = Gson()

    @Test
    fun serializesActivationRequestWithRequiredUuid() {
        val requestId = "b3bf44fc-cb1d-4cf5-8c7f-23c0edc4e70e"
        val json = JsonParser.parseString(
            gson.toJson(
                PromocodeActivateRequestDto(
                    code = "SAVE10",
                    requestId = requestId,
                ),
            ),
        ).asJsonObject

        assertEquals("SAVE10", json["code"].asString)
        assertEquals(requestId, json["request_id"].asString)
        assertTrue(canonicalUuid4OrNull(requestId) != null)
    }

    @Test
    fun parsesActivationRewardAndPublicPlanSnapshot() {
        val discount = gson.fromJson(
            """
            {
              "request_id": "b3bf44fc-cb1d-4cf5-8c7f-23c0edc4e70e",
              "code": "SAVE10",
              "reward_type": "PERSONAL_DISCOUNT",
              "reward": 10,
              "plan_snapshot": null
            }
            """.trimIndent(),
            PromocodeActivationResultDto::class.java,
        )
        val subscription = gson.fromJson(
            """
            {
              "code": "GIFT",
              "reward_type": "SUBSCRIPTION",
              "reward": null,
              "plan_snapshot": {"name": "Comfort", "duration": 30}
            }
            """.trimIndent(),
            PromocodeActivationResultDto::class.java,
        )

        assertEquals(
            "b3bf44fc-cb1d-4cf5-8c7f-23c0edc4e70e",
            discount.requestId,
        )
        assertEquals("PERSONAL_DISCOUNT", discount.rewardType)
        assertEquals(10, discount.reward)
        assertEquals("Comfort", subscription.planSnapshot?.name)
        assertEquals(30, subscription.planSnapshot?.duration)
    }

    @Test
    fun parsesPromocodeHistoryContract() {
        val history = gson.fromJson(
            """
            {
              "telegram_id": 123456789,
              "total": 1,
              "limit": 20,
              "offset": 0,
              "promocodes": [
                {
                  "activation_id": 7,
                  "promocode_id": 3,
                  "code": "SAVE10",
                  "reward_type": "PURCHASE_DISCOUNT",
                  "reward": 10,
                  "plan_snapshot": null,
                  "activated_at": "2026-08-04T10:30:00+00:00"
                }
              ]
            }
            """.trimIndent(),
            PromocodeHistoryDto::class.java,
        )

        assertEquals(123456789L, history.telegramId)
        assertEquals(1, history.total)
        assertEquals(7L, history.promocodes.orEmpty().single().activationId)
        assertEquals("PURCHASE_DISCOUNT", history.promocodes.orEmpty().single().rewardType)
    }

    @Test
    fun parsesStructuredFastApiError() {
        val error = parsePromocodeErrorBody(
            """{"detail":{"code":"PROMOCODE_EXPIRED","message":"Promocode has expired"}}""",
        )

        assertEquals("PROMOCODE_EXPIRED", error?.code)
        assertEquals("Promocode has expired", error?.message)
    }

    @Test
    fun mapsCurrentServerPromocodeErrors() {
        assertEquals(
            PromocodeActivationError.NOT_AVAILABLE,
            mapPromocodeActivationHttpError(
                httpStatus = 400,
                serverCode = "PROMOCODE_INVALID",
                serverMessage = "Promocode is invalid, unavailable, or already activated",
            ),
        )
        assertEquals(
            PromocodeActivationError.UNKNOWN,
            mapPromocodeActivationHttpError(
                httpStatus = 409,
                serverCode = "PROMOCODE_REQUEST_ID_CONFLICT",
                serverMessage = "request_id was already used",
            ),
        )
    }

    @Test
    fun keepsIdempotencyKeyForAmbiguousOutcomesOnly() {
        assertFalse(shouldDiscardPendingPromocodeAttempt(null))
        assertFalse(shouldDiscardPendingPromocodeAttempt(408))
        assertFalse(shouldDiscardPendingPromocodeAttempt(500))
        assertTrue(shouldDiscardPendingPromocodeAttempt(400))
        assertTrue(shouldDiscardPendingPromocodeAttempt(409))
        assertTrue(shouldDiscardPendingPromocodeAttempt(422))
    }

    @Test
    fun acceptsOnlyCanonicalVersionFourRequestIds() {
        val lower = "b3bf44fc-cb1d-4cf5-8c7f-23c0edc4e70e"
        assertEquals(lower, canonicalUuid4OrNull(lower.uppercase()))
        assertNull(canonicalUuid4OrNull("not-a-uuid"))
        assertNull(canonicalUuid4OrNull("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        assertNull(canonicalUuid4OrNull("1-1-1-1-1"))
    }

    @Test
    fun malformedErrorBodyIsIgnored() {
        assertEquals(null, parsePromocodeErrorBody("not-json"))
        assertEquals(null, parsePromocodeErrorBody("""{"detail":"Unauthorized"}"""))
    }

    @Test
    fun mergeHistoryPagesKeepsOrderAndDropsOverlap() {
        val first = PromocodeHistoryItemDto(
            activationId = 1,
            promocodeId = 10,
            code = "FIRST",
        )
        val second = PromocodeHistoryItemDto(
            activationId = 2,
            promocodeId = 20,
            code = "SECOND",
        )
        val current = PromocodeHistoryDto(
            telegramId = 42,
            total = 2,
            limit = 1,
            offset = 0,
            promocodes = listOf(first),
        )
        val next = PromocodeHistoryDto(
            telegramId = 42,
            total = 2,
            limit = 2,
            offset = 1,
            promocodes = listOf(first, second),
        )

        val merged = mergePromocodePages(current, next)

        assertEquals(listOf(first, second), merged.promocodes)
        assertEquals(2, merged.total)
        assertEquals(1, merged.offset)
    }
}
