package com.lookbuddy.app

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.lookbuddy.app.sdk.DiaryDeviceEvent
import com.lookbuddy.app.sdk.DiaryDeviceMedia
import com.lookbuddy.app.sdk.EufyDevice
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiaryMediaRepositoryTest {
    private class Source : DiaryMediaSource {
        override var connected = true
        var bounds: Pair<Long, Long>? = null
        var count = 1
        var encoded = ""
        var lastImage: List<String>? = null
        override suspend fun devices() = listOf(EufyDevice("sdk-sn", "parent", "Desk", "T8171", true, false))
        override suspend fun events(deviceSn: String, start: Long, end: Long): List<DiaryDeviceEvent> {
            bounds = start to end
            return List(count) { i -> DiaryDeviceEvent("sdk-event-$i", Instant.ofEpochMilli(start + i * 1000),
                listOf(DiaryDeviceMedia("thumbnail", "image", "thumbnail"), DiaryDeviceMedia("video", "video", "clip"))) }
        }
        override suspend fun image(deviceSn: String, eventId: String, capturedAt: Instant, key: String): String {
            lastImage = listOf(deviceSn, eventId, capturedAt.toString(), key)
            return encoded
        }
        override suspend fun player(deviceSn: String, eventId: String, capturedAt: Instant): EventMediaPlayer = error("No hardware in tests")
    }

    private fun client(server: MockWebServer, scope: CoroutineScope): ProductClient =
        client(server.url("/").toString().trimEnd('/'), scope)

    private fun client(origin: String, scope: CoroutineScope): ProductClient {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lookbuddy.product.connection", Context.MODE_PRIVATE).edit().clear()
            .putString("endpoint", origin).commit()
        return ProductClient(context, scope, object : ProductSessionVault {
            override fun read() = JSONObject().put("accessToken", "test-only").put("refreshToken", "test-only")
                .put("user", JSONObject().put("id", "media-owner").put("username", "media-owner").put("timezone", "America/New_York"))
            override fun save(value: JSONObject?) = Unit
        })
    }

    @Test fun mediaMetadataRoundTripsThroughActualBackend(): Unit = runBlocking {
        val origin = System.getenv("LOOKBUDDY_PRODUCT_INTEGRATION_BASE_URL")
        org.junit.Assume.assumeTrue("Isolated backend not configured", !origin.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(origin!!, scope)
        try {
            assertTrue(client.login("media-test-${java.util.UUID.randomUUID()}", "媒体联调"))
            val repository = DiaryMediaRepository(client, Source())
            val day = LocalDate.of(2020, 1, 2)
            repeat(2) { repository.sync(day, client.diaryIdentity) }
            suspend fun page() = JSONObject(client.request("/v1/diary-page/$day")).getJSONArray("entries")
            val records = page()
            assertEquals(1, records.length())
            val row = records.getJSONObject(0)
            assertEquals(2, row.getJSONArray("mediaItems").length())
            val mediaId = row.getJSONArray("mediaItems").getJSONObject(0).getString("id")
            repository.link(row.getString("id"), mediaId, false, client.diaryIdentity)
            repository.sync(day, client.diaryIdentity)
            assertEquals(1, page().getJSONObject(0).getJSONArray("mediaItems").length())
            repository.link(row.getString("id"), mediaId, true, client.diaryIdentity)
            assertEquals(2, page().getJSONObject(0).getJSONArray("mediaItems").length())
        } finally {
            if (client.user != null) client.request("/v1/account", "DELETE")
            scope.cancel()
        }
    }

    @Test fun syncMapsDeviceIdsChunksMetadataAndUsesCalendarDayAcrossDst(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("{\"id\":\"dev_backend\"}"))
            repeat(2) { server.enqueue(MockResponse().setBody("{\"events\":[]}")) }
            val source = Source().apply { count = 101 }
            DiaryMediaRepository(client, source).sync(LocalDate.of(2026, 3, 8), client.diaryIdentity)
            assertEquals(23 * 60 * 60 * 1000L, source.bounds!!.second - source.bounds!!.first)
            assertEquals(LocalDate.of(2026, 3, 8).atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli(), source.bounds!!.first)
            assertEquals("/v1/devices", server.takeRequest().path)
            val binding = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("sdk-sn", binding.getString("providerDeviceRef"))
            val batches = List(2) { JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("events") }
            assertEquals(listOf(100, 1), batches.map { it.length() })
            val event = batches[0].getJSONObject(0)
            assertEquals("dev_backend", event.getString("deviceId"))
            assertEquals("sdk-event-0", event.getString("providerEventId"))
            val image = event.getJSONArray("mediaItems").getJSONObject(0)
            assertEquals(setOf("providerMediaKey", "kind", "role"), image.keys().asSequence().toSet())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun existingBindingIsReusedAndRepeatedSyncKeepsProviderIdentity(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            val bound = JSONArray().put(JSONObject().put("id", "dev_existing").put("provider", "eufy").put("providerDeviceRef", "sdk-sn"))
            repeat(2) { server.enqueue(MockResponse().setBody(bound.toString())); server.enqueue(MockResponse().setBody("{}")) }
            val repository = DiaryMediaRepository(client, Source())
            val day = LocalDate.of(2026, 3, 7)
            repeat(2) { repository.sync(day, client.diaryIdentity) }
            val requests = List(4) { server.takeRequest() }
            assertEquals(listOf("GET", "POST", "GET", "POST"), requests.map { it.method })
            assertEquals(requests[1].body.readUtf8(), requests[3].body.readUtf8())
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun staleIdentityOrDisconnectedSdkCannotReadOrSynchronize(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            val source = Source()
            val repository = DiaryMediaRepository(client, source)
            try { repository.sync(LocalDate.of(2026, 3, 8), "old-account"); fail("Stale identity accepted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("账号")) }
            source.connected = false
            try { repository.sync(LocalDate.of(2026, 3, 8), client.diaryIdentity); fail("Disconnected SDK accepted") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("连接")) }
            assertEquals(0, server.requestCount)
            assertNull(source.bounds)
        } finally { scope.cancel(); server.shutdown() }
    }

    @Test fun imageUsesHistoricalReferenceAndBoundsDecodedSize(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = client(server, scope)
        try {
            // Actual compressed 3200x2 PNG, independent of Robolectric compression.
            val bytes = Base64.decode("iVBORw0KGgoAAAANSUhEUgAADIAAAAACCAYAAACaXEbfAAAAMElEQVR4nO3BMQEAAADCoPVPbQdvoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIDfAGQCAAHzeIVpAAAAAElFTkSuQmCC", Base64.DEFAULT)
            val source = Source().apply { encoded = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) }
            val media = JSONObject().put("providerDeviceRef", "sdk-sn").put("providerEventId", "historical-event")
                .put("capturedAt", "2026-03-08T07:00:00Z").put("providerMediaKey", "thumbnail")
            val result = DiaryMediaRepository(client, source).image(media, client.diaryIdentity)
            assertEquals(listOf("sdk-sn", "historical-event", "2026-03-08T07:00:00Z", "thumbnail"), source.lastImage)
            assertTrue(result.width <= 1600)
            assertEquals(0, server.requestCount)
            result.recycle()
        } finally { scope.cancel(); server.shutdown() }
    }
}
