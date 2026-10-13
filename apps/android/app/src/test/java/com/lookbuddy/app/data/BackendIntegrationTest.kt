package com.lookbuddy.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackendIntegrationTest {
    @Test fun actualBackendRoundTripAndAllDemoScenarios() = runBlocking {
        val address = System.getenv("LOOKBUDDY_ANDROID_INTEGRATION_BASE_URL")
        assumeTrue("Optional isolated HTTP backend is not configured", !address.isNullOrBlank())
        val vault = MemoryVault(); val client = ApiClient(normalizeBaseUrl(address!!, true), vault); val repo = LookBuddyRepository(client, vault)
        try {
            repo.authenticate("android-${UUID.randomUUID()}@example.com", "temporary-test-password", "Android integration")
            assertEquals(vault.read()!!.tokens.user.id, client.api.me().id)
            val prefs = client.api.patchPreferences(buildJsonObject { put("sedentaryMinutes", 30); put("breakMinutes", 5); put("busy", true) })
            assertEquals(30, prefs.sedentaryMinutes); assertEquals(5, prefs.breakMinutes); assertTrue(prefs.busy)
            // Simulate an update from another client against the same account.
            val second = ApiClient(client.baseUrl, vault)
            try { second.api.patchPreferences(buildJsonObject { put("busy", false) }); assertFalse(client.api.preferences().busy) } finally { second.close() }
            val expected = mapOf("recovery" to "improved", "recurrence" to "recurred", "gap" to "unknown", "chair" to "unknown", "break" to "improved", "return" to "interrupted", "ignored" to "ignored", "snoozed" to "snoozed", "expression_relaxed" to "expression_relaxed", "expression_tense" to "expression_tense", "expression_unchanged" to "expression_unchanged", "expression_gap" to "unknown")
            for ((scenario, status) in expected) {
                val value = DemoReplay(client.api).run(scenario, false)
                assertEquals("Scenario $scenario", status, value["status"]!!.jsonPrimitive.content)
                assertEquals("demo", value["namespace"]!!.jsonPrimitive.content)
            }
            assertEquals(0, client.api.getJson("v1/learning/rewards?namespace=live").jsonArray.size)
            val opened = CountDownLatch(1); val pushed = CountDownLatch(2)
            repo.openStream({ pushed.countDown() }, { if (it.contains("已建立")) opened.countDown() })
            assertTrue("header-auth realtime stream", opened.await(5, TimeUnit.SECONDS))
            val session = client.api.postJson("v1/sessions", buildJsonObject { }).jsonObject["id"]!!.jsonPrimitive.content
            val snapshot = client.api.postJson("v1/state-snapshots", buildJsonObject {
                put("sessionId", session); put("capturedAt", Instant.now().toString()); put("presence", "online")
                put("badPostureStreakMs", 60000); put("lastDrinkAt", Instant.now().toString())
            }).jsonObject
            val id = snapshot["reminder"]!!.jsonObject["id"]!!.jsonPrimitive.content
            assertTrue("realtime event reached Android repository", pushed.await(5, TimeUnit.SECONDS))
            assertTrue(client.api.pendingReminders().any { it.id == id })
            client.api.displayed(id)
            client.api.respondReminder(id, ReminderResponseRequest("accepted"))
            val rewardCount = client.api.getJson("v1/learning/rewards?namespace=live").jsonArray.size
            client.api.respondReminder(id, ReminderResponseRequest("accepted"))
            assertEquals(rewardCount, client.api.getJson("v1/learning/rewards?namespace=live").jsonArray.size)
            try { client.api.respondReminder(id, ReminderResponseRequest("ignored")); fail("Conflicting response must fail") } catch (error: retrofit2.HttpException) { assertEquals(409, error.code()) }
            client.api.postJson("v1/sessions/$session/finish", buildJsonObject { })
            DemoReplay(client.api).run("recovery", true)
            assertTrue(client.api.getJson("v1/learning/rewards?namespace=demo").jsonArray.isNotEmpty())
            val diary = client.api.generateDiary(LocalDate.now().toString()); assertTrue(diary.content.isNotEmpty())
            client.api.postJson("v1/debug/learning/reset", buildJsonObject { })
            assertEquals(0, client.api.getJson("v1/learning/rewards?namespace=demo").jsonArray.size)
        } finally {
            if (vault.read() != null) repo.deleteAccount()
            repo.close()
        }
    }
}
