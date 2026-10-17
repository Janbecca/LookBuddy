package com.lookbuddy.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRepositoryTest {
    private val pending = """[{"id":"rem_one","category":"posture","message":"坐舒服一点","expiresAt":"2099-10-17T00:00:00Z"}]"""

    @Test fun claimBeforeDisplayAndRetryResponsesWithoutOptimisticSuccess() = runTest {
        val requests = mutableListOf<String>()
        var failResponse = true
        val repo = ReminderRepository({ path, _, body ->
            requests.add(path)
            when {
                path.endsWith("pending") -> pending
                path.endsWith("respond") -> {
                    assertEquals("android:test-device", body!!.getString("claimId"))
                    if (failResponse) throw ProductHttpException(503)
                    assertEquals("snoozed", body.getString("action"))
                    assertEquals(10, body.getInt("snoozeMinutes"))
                    "{}"
                }
                else -> "{}"
            }
        }, "android:test-device", 12_000)
        val event = repo.reminders.take(1).toList().single()
        assertEquals("rem_one", event.id)
        assertTrue(event.remote)
        assertTrue(requests[1].endsWith("rem_one/displayed"))
        try { repo.respond(event.id, HomeReminderResponse.Snoozed); fail("must fail") }
        catch (e: ProductHttpException) { assertEquals(503, e.status) }
        // A failed request leaves the card available for the same stable-ID retry.
        failResponse = false
        repo.respond(event.id, HomeReminderResponse.Snoozed)
        assertEquals(2, requests.count { it.endsWith("respond") })
    }

    @Test fun competingClaimNeverShowsAndPollingRecoversWithoutWebsocket() = runTest {
        var attempts = 0
        val repo = ReminderRepository({ path, _, _ ->
            if (path.endsWith("pending")) pending else {
                attempts++
                if (attempts == 1) throw ProductHttpException(409)
                "{}"
            }
        }, "android:test-device", 12_000)
        val states = repo.updates.take(3).toList()
        assertNull(states[0])
        assertNull(states[1])
        assertEquals("rem_one", states[2]!!.id)
        assertEquals(2, attempts)
    }

    @Test fun sevenMoodsAndUnknownRowsDoNotBreakSync() {
        val rows = JSONArray()
        Mood.entries.forEachIndexed { i, mood -> rows.put(JSONObject().put("date", "2026-10-${10 + i}").put("mood", mood.name)) }
        rows.put(JSONObject().put("date", "2026-10-17").put("mood", "FutureMood"))
        rows.put(JSONObject().put("date", "bad-date").put("mood", "Happy"))
        val result = parseMoodRecords(rows)
        assertEquals(Mood.entries.toSet(), result.values.toSet())
        assertEquals(7, result.size)
        assertNull(Mood.fromBackend("FutureMood"))
        assertEquals(reminderInstant("2026-10-17T01:02:03Z"), reminderInstant("2026-10-17T01:02:03"))
    }
}
