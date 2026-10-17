package com.lookbuddy.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductBackendIntegrationTest {
    @Test fun androidTransportMatchesActualDesktopServerProtocol(): Unit = runBlocking {
        val base = System.getenv("LOOKBUDDY_DESKTOP_INTEGRATION_BASE_URL")
        assumeTrue("Isolated desktop server not configured", !base.isNullOrBlank())
        val repository = DesktopPetSyncRepository(base!!, "test-only")
        suspend fun desktop(): JSONObject = withContext(Dispatchers.IO) {
            val connection = java.net.URL("$base/v1/state").openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("X-LookBuddy-Token", "test-only")
            try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("desktop") }
            finally { connection.disconnect() }
        }
        val first = DesktopPetSyncRequest("drink", true, message = "喝口水吧", eventId = "p5:drink")
        assertNotNull(repository.sync(first))
        val event = desktop().getString("scene_event_id")
        assertEquals("drink", desktop().getString("scene_action_id"))
        assertNotNull(repository.sync(first))
        assertEquals(event, desktop().getString("scene_event_id"))
        assertNotNull(repository.sync(first.copy(completed = true, message = "")))
        val completed = desktop()
        assertEquals(event, completed.getString("scene_event_id"))
        assertTrue(completed.getBoolean("scene_completed"))
        assertEquals("", completed.getString("scene_message"))
        assertNotNull(repository.sync(first.copy(eventId = "p5:next")))
        assertNotEquals(event, desktop().getString("scene_event_id"))
        assertNull(DesktopPetSyncRepository(base, "wrong-test-value").sync(first))
        assertNull(repository.sync(first.copy(message = "x".repeat(65 * 1024))))
        assertNotNull(repository.sync(first.copy(scene = "evening", eventId = "p5:night")))
        assertEquals("night", desktop().getString("scene_action_id"))
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertNull(DesktopPetSyncRepository("http://127.0.0.1:$port", "test-only").sync(first))
        assertNotNull(repository.sync(first))
    }

    private class MemoryVault : ProductSessionVault {
        var value: JSONObject? = null
        override fun read() = value
        override fun save(value: JSONObject?) { this.value = value }
    }
    @Test fun productPagesUseRealBackend(): Unit = runBlocking {
        val base = System.getenv("LOOKBUDDY_PRODUCT_INTEGRATION_BASE_URL")
        assumeTrue("Isolated backend not configured", !base.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lookbuddy.product.connection", Context.MODE_PRIVATE).edit().clear().putString("endpoint", base).commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vault = MemoryVault()
        val client = ProductClient(context, scope, vault)
        try {
            assertFalse("An empty username cannot identify an account", client.login("", "产品联调", ""))
            for ((phone, code) in listOf("abc" to "wrong", "随便填写！" to "任意密码")) {
                val loggedIn = client.login(phone, "产品联调", code)
                assertTrue("Demo input should be accepted: $phone (${client.error})", loggedIn)
                assertTrue(client.user!!.getBoolean("demo"))
                client.request("/v1/account", "DELETE")
                client.logout()
                // logout() launches an action; finish it before starting the next identity.
                withTimeout(5000) { while (client.busy) delay(10) }
                assertNull(client.user)
            }
            assertTrue(client.login("13800000000", "产品联调"))
            assertTrue(client.user!!.getBoolean("demo"))
            val other = JSONObject(client.request("/v1/auth/username", "POST", JSONObject()
                .put("username", "13800000000").put("password", "a different password"), false))
            assertEquals(client.user!!.getString("id"), other.getJSONObject("user").getString("id"))
            assertEquals("13800000000", client.username)
            vault.value!!.put("accessToken", "expired-test-token")
            assertTrue(JSONObject(client.request("/v1/profile")).getBoolean("demo"))
            assertNotEquals("expired-test-token", vault.value!!.getString("accessToken"))
            assertTrue(client.saveName("已同步昵称"))
            assertEquals("已同步昵称", JSONObject(client.request("/v1/profile")).getString("displayName"))
            client.request("/v1/work-schedule", "PATCH", JSONObject().put("start", "22:00").put("end", "06:00"))
            client.sync()
            assertEquals("22:00", WorkScheduleStore(context).read().start.toString())
            val today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            for (mood in Mood.entries) {
                assertTrue(client.saveMood(today, mood))
                client.sync(); assertEquals(mood, client.moods[today])
            }
            val session = JSONObject(client.request("/v1/work/start", "POST", JSONObject().put("installationId", client.installation)))
            val identity = client.diaryIdentity
            val now = java.time.Instant.now().toString()
            val snapshot = JSONObject(client.request("/v1/state-snapshots", "POST", JSONObject()
                .put("sessionId", session.getString("id")).put("capturedAt", now)
                .put("presence", "online").put("badPostureStreakMs", 60_000).put("lastDrinkAt", now)))
            val reminders = ReminderRepository(client, identity)
            val reminder = withTimeout(5000) { reminders.reminders.first() }
            assertEquals(snapshot.getJSONObject("reminder").getString("id"), reminder.id)
            assertEquals(HomeReminderKind.Posture, reminder.kind)
            reminders.respond(reminder.id, HomeReminderResponse.Accepted)
            assertEquals(0, org.json.JSONArray(client.request("/v1/reminders/pending")).length())
            assertEquals(session.getString("id"), JSONObject(client.request("/v1/work/start", "POST", JSONObject().put("installationId", client.installation))).getString("id"))
            assertTrue(client.mark().contains("不录制"))
            val diaryPage = JSONObject(client.request("/v1/diary-page/$today"))
            assertTrue(diaryPage.getJSONArray("entries").length() > 0)
            client.request("/v1/diaries/$today/generate", "POST", JSONObject())
            client.request("/v1/diaries/$today", "PATCH", JSONObject().put("content", "用户编辑版本"))
            assertEquals("用户编辑版本", JSONObject(client.request("/v1/diaries/$today/generate", "POST", JSONObject())).getString("content"))
            client.request("/v1/diaries/$today", "DELETE")
            val clearedDiary = JSONObject(client.request("/v1/diary-page/$today"))
            assertEquals("", clearedDiary.getString("content"))
            assertTrue("Deleting diary text retains the recorded timeline", clearedDiary.getJSONArray("entries").length() > 0)
            assertTrue(JSONObject(client.request("/buddy/greeting", "POST", JSONObject().put("scene", "morning_unknown"))).has("show"))
            client.request("/v1/sessions/${session.getString("id")}/finish", "POST", JSONObject())
            client.sync(); assertNull(client.current)
            client.request("/v1/account", "DELETE")
            Unit
        } finally { client.release(); scope.cancel() }
    }
}
