package com.lookbuddy.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadedDiaryAndChatTest {
    @get:Rule val compose = createComposeRule()
    private fun client(server: MockWebServer, scope: CoroutineScope): ProductClient {
        return client(server.url("/").toString().trimEnd('/'), scope)
    }

    private fun client(origin: String, scope: CoroutineScope): ProductClient {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lookbuddy.product.connection", Context.MODE_PRIVATE).edit()
            .putString("endpoint", origin).commit()
        return ProductClient(context, scope, object : ProductSessionVault {
            override fun read() = JSONObject().put("accessToken", "test-only").put("refreshToken", "test-only")
                .put("user", JSONObject().put("id", "uploaded-owner").put("username", "uploaded-owner"))
            override fun save(value: JSONObject?) = Unit
        })
    }

    private fun image(): String {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    @Test fun diarySortsNewestFirstForBothLegacyAndExactTimestamps(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            val repository = DiaryMediaRepository(client)
            fun row(id: String, time: String, instant: String? = null) = JSONObject().put("id", id)
                .put("time", time).put("hasUploadedImage", false).apply { instant?.let { put("occurredAt", it) } }
            server.enqueue(MockResponse().setBody(JSONObject().put("entries", JSONArray()
                .put(row("oldest", "00:00")).put(row("middle", "10:00")).put(row("newest", "18:05"))).toString()))
            var entries = repository.page(LocalDate.of(2026, 10, 17), client.diaryIdentity).getJSONArray("entries")
            assertEquals(listOf("newest", "middle", "oldest"), (0 until entries.length()).map { entries.getJSONObject(it).getString("id") })
            server.enqueue(MockResponse().setBody(JSONObject().put("entries", JSONArray()
                .put(row("middle", "10:10", "2026-10-17T02:10:01Z"))
                .put(row("newest", "10:10", "2026-10-17T02:10:59Z"))).toString()))
            entries = repository.page(LocalDate.of(2026, 10, 17), client.diaryIdentity).getJSONArray("entries")
            assertEquals("newest", entries.getJSONObject(0).getString("id"))
        } finally { client.release(); scope.cancel(); server.shutdown() }
    }

    @Test fun uploadedImageIsVisibleAndCloudRefreshDoesNotRequireOfficialAccount() {
        val server = MockWebServer()
        val encoded = image()
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return if (request.path!!.startsWith("/v1/diary-page/")) {
                    refreshes.incrementAndGet()
                    MockResponse().setBody("""{"content":"","entries":[{"id":"image-event","time":"10:00","title":"已上传图片记录","category":"记录","hasUploadedImage":true}]}""")
                } else if (request.path == "/v1/diary-images/image-event") MockResponse().setBody(JSONObject().put("imageDataUrl", encoded).toString())
                else MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalProduct provides client, LocalBuddyMotionEnabled provides false) { DiaryScreen(1f) }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("diary_uploaded_image_image-event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("diary_uploaded_image_image-event").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("刷新当天云端记录").performScrollTo().performClick()
            compose.waitUntil(5000) { refreshes.get() >= 2 }
            compose.waitUntil(5000) { compose.onAllNodesWithText("云端记录已刷新，已上传图片无需连接官方设备账号").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("请先在个人设置连接官方设备账号").assertDoesNotExist()
        } finally { client.release(); scope.cancel(); server.shutdown() }
    }

    @Test fun uploaded792KiBImageRoundTripsThroughActualBackendWithoutSdk(): Unit = runBlocking {
        val origin = System.getenv("LOOKBUDDY_PRODUCT_INTEGRATION_BASE_URL")
        org.junit.Assume.assumeTrue("Isolated backend not configured", !origin.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(origin!!, scope)
        try {
            assertTrue(client.login("image-upload-${java.util.UUID.randomUUID()}", "图片联调"))
            val bytes = Base64.decode(image().substringAfter("base64,"), Base64.DEFAULT).copyOf(810_615)
            val encoded = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            val now = java.time.Instant.now()
            val eventId = "image-upload-${java.util.UUID.randomUUID()}"
            val event = JSONObject().put("eventId", eventId).put("type", "memory.marked").put("occurredAt", now.toString())
                .put("source", JSONObject().put("kind", "device"))
                .put("payload", JSONObject().put("note", "图片联调").put("imageDataUrl", encoded))
            val accepted = JSONObject(client.request("/v1/events/batch", "POST", JSONObject().put("events", JSONArray().put(event))))
            assertEquals(1, accepted.getInt("accepted"))
            val repository = DiaryMediaRepository(client)
            val page = repository.page(now.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(), client.diaryIdentity)
            val entry = page.getJSONArray("entries").getJSONObject(0)
            assertTrue(entry.getBoolean("hasUploadedImage"))
            assertEquals(8, repository.uploadedImage(entry, client.diaryIdentity).width)
            val returned = JSONObject(client.request("/v1/diary-images/$eventId")).getString("imageDataUrl")
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            assertArrayEquals(hash.digest(bytes), hash.digest(Base64.decode(returned.substringAfter("base64,"), Base64.DEFAULT)))
        } finally {
            if (client.user != null) client.request("/v1/account", "DELETE")
            client.release(); scope.cancel()
        }
    }

    @Test fun legacyPayloadImageLoadsWithoutAnyOfficialSdkAccount(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            val encoded = image()
            server.enqueue(MockResponse().setBody("""{"entries":[{"id":"image-event"}]}"""))
            server.enqueue(MockResponse().setBody(JSONArray().put(JSONObject().put("eventId", "image-event")
                .put("payload", JSONObject().put("imageDataUrl", encoded))).toString()))
            val repository = DiaryMediaRepository(client)
            val page = repository.page(LocalDate.of(2026, 10, 17), client.diaryIdentity)
            val entry = page.getJSONArray("entries").getJSONObject(0)
            assertEquals(encoded, entry.getString("imageDataUrl"))
            assertEquals(8, repository.uploadedImage(entry, client.diaryIdentity).width)
            assertEquals("/v1/diary-page/2026-10-17", server.takeRequest().path)
            assertEquals("/v1/timeline?limit=500", server.takeRequest().path)
            assertEquals(2, server.requestCount)
            try { repository.uploadedImage(entry, "other-account"); fail("Cross-account access must fail") }
            catch (_: IllegalStateException) { }
        } finally { client.release(); scope.cancel(); server.shutdown() }
    }

    @Test fun updatedBackendLoadsImageOnDemandAndDoesNotDownloadTimeline(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            server.enqueue(MockResponse().setBody("""{"entries":[{"id":"image-event","hasUploadedImage":true}]}"""))
            server.enqueue(MockResponse().setBody(JSONObject().put("imageDataUrl", image()).toString()))
            val repository = DiaryMediaRepository(client)
            val page = repository.page(LocalDate.of(2026, 10, 17), client.diaryIdentity)
            assertEquals(1, server.requestCount)
            assertEquals(8, repository.uploadedImage(page.getJSONArray("entries").getJSONObject(0), client.diaryIdentity).height)
            server.takeRequest()
            assertEquals("/v1/diary-images/image-event", server.takeRequest().path)
        } finally { client.release(); scope.cancel(); server.shutdown() }
    }

    @Test fun chatFailureKeepsMessageAndRetryIncludesSuccessfulHistory(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            val state = BuddyChatState()
            state.draft = "有点累"; state.submit()
            server.enqueue(MockResponse().setResponseCode(502).setBody("{}"))
            state.sendPending(client)
            assertEquals(listOf("有点累"), state.unsent.toList())
            assertTrue(state.messages.isEmpty()); assertNotNull(state.error)
            server.enqueue(MockResponse().setBody("""{"message":"先休息一下吧","source":"llm"}"""))
            state.sendPending(client)
            assertTrue(state.unsent.isEmpty()); assertNull(state.error)
            assertEquals("先休息一下吧", state.messages.single().second)
            state.draft = "现在下午"; state.submit()
            server.enqueue(MockResponse().setBody("""{"message":"那就慢慢来","source":"llm"}"""))
            state.sendPending(client)
            repeat(2) { server.takeRequest() }
            val request = server.takeRequest()
            assertEquals("/v1/buddy/chat", request.path)
            val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
            assertEquals(3, messages.length())
            assertEquals("先休息一下吧", messages.getJSONObject(1).getString("content"))
        } finally { client.release(); scope.cancel(); server.shutdown() }
    }
}
