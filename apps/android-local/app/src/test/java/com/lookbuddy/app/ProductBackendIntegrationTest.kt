package com.lookbuddy.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.*
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
            assertTrue(client.login("13800000000", "产品联调"))
            assertTrue(client.user!!.getBoolean("demo"))
            vault.value!!.put("accessToken", "expired-test-token")
            assertTrue(JSONObject(client.request("/v1/profile")).getBoolean("demo"))
            assertNotEquals("expired-test-token", vault.value!!.getString("accessToken"))
            assertTrue(client.saveName("已同步昵称"))
            assertEquals("已同步昵称", JSONObject(client.request("/v1/profile")).getString("displayName"))
            client.request("/v1/work-schedule", "PATCH", JSONObject().put("start", "22:00").put("end", "06:00"))
            client.sync()
            assertEquals("22:00", WorkScheduleStore(context).read().start.toString())
            val today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            assertTrue(client.saveMood(today, Mood.Calm))
            client.sync(); assertEquals(Mood.Calm, client.moods[today])
            val session = JSONObject(client.request("/v1/work/start", "POST", JSONObject().put("installationId", client.installation)))
            assertEquals(session.getString("id"), JSONObject(client.request("/v1/work/start", "POST", JSONObject().put("installationId", client.installation))).getString("id"))
            assertTrue(client.mark().contains("不录制"))
            val diaryPage = JSONObject(client.request("/v1/diary-page/$today"))
            assertTrue(diaryPage.getJSONArray("entries").length() > 0)
            client.request("/v1/diaries/$today/generate", "POST", JSONObject())
            client.request("/v1/diaries/$today", "PATCH", JSONObject().put("content", "用户编辑版本"))
            assertEquals("用户编辑版本", JSONObject(client.request("/v1/diaries/$today/generate", "POST", JSONObject())).getString("content"))
            assertTrue(JSONObject(client.request("/buddy/greeting", "POST", JSONObject().put("scene", "morning_unknown"))).has("show"))
            client.request("/v1/sessions/${session.getString("id")}/finish", "POST", JSONObject())
            client.sync(); assertNull(client.current)
            client.request("/v1/account", "DELETE")
            Unit
        } finally { client.release(); scope.cancel() }
    }
}
