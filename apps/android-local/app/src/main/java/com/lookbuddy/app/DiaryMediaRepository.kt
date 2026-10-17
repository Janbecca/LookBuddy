package com.lookbuddy.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.lookbuddy.app.sdk.DiaryDeviceEvent
import com.lookbuddy.app.sdk.EufyDevice
import com.anker.esiotkit.media.event_stream.EventMediaPlayer

internal interface DiaryMediaSource {
    val connected: Boolean
    suspend fun devices(): List<EufyDevice>
    suspend fun events(deviceSn: String, start: Long, end: Long): List<DiaryDeviceEvent>
    suspend fun image(deviceSn: String, eventId: String, capturedAt: Instant, key: String): String
    suspend fun player(deviceSn: String, eventId: String, capturedAt: Instant): EventMediaPlayer
}

private class EufyDiaryMediaSource(private val client: ProductClient) : DiaryMediaSource {
    override val connected get() = client.gateway.diaryConnected
    override suspend fun devices() = client.gateway.refreshDevices()
    override suspend fun events(deviceSn: String, start: Long, end: Long) = client.gateway.fetchDiaryEvents(deviceSn, start, end)
    override suspend fun image(deviceSn: String, eventId: String, capturedAt: Instant, key: String) =
        client.gateway.loadDiaryImage(deviceSn, eventId, capturedAt, key)
    override suspend fun player(deviceSn: String, eventId: String, capturedAt: Instant) =
        client.gateway.createDiaryPlayer(deviceSn, eventId, capturedAt)
}

/** No persistent media cache. Every access is scoped to the current app and SDK account. */
internal class DiaryMediaRepository(private val client: ProductClient, private val source: DiaryMediaSource = EufyDiaryMediaSource(client)) {
    private val syncMutex = Mutex()

    suspend fun page(day: LocalDate, identity: String): JSONObject {
        val page = JSONObject(client.request("/v1/diary-page/$day", expectedIdentity = identity))
        val entries = page.optJSONArray("entries") ?: return page
        // New servers include exact instants; old servers expose HH:mm in account time.
        val zone = ZoneId.of(client.user?.optString("timezone", "Asia/Shanghai") ?: "Asia/Shanghai")
        val ordered = (0 until entries.length()).map { it to entries.getJSONObject(it) }
            .sortedWith(compareByDescending<Pair<Int, JSONObject>> { (_, row) ->
                runCatching { Instant.parse(row.optString("occurredAt")) }.getOrNull()
                    ?: runCatching { day.atTime(LocalTime.parse(row.optString("time"))).atZone(zone).toInstant() }.getOrDefault(Instant.MIN)
            }.thenByDescending { it.first })
        page.put("entries", JSONArray().apply { ordered.forEach { put(it.second) } })
        val legacy = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { !it.has("hasUploadedImage") && it.optString("id").isNotBlank() }
        if (legacy.isNotEmpty()) {
            // Compatibility with the deployed API that stores images only in event payloads.
            try {
                val events = JSONArray(client.request("/v1/timeline?limit=500", expectedIdentity = identity))
                val byId = legacy.associateBy { it.getString("id") }
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val entry = byId[event.optString("eventId")] ?: continue
                    val image = event.optJSONObject("payload")?.optString("imageDataUrl").orEmpty()
                    if (image.startsWith("data:image/") && image.length <= 16 * 1024 * 1024) entry.put("imageDataUrl", image)
                }
                if (events.length() == 500) page.put("uploadedImageWarning", "当前后端仅支持读取最近500条事件的图片；较早图片需更新后端后查看。")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { page.put("uploadedImageWarning", "文字记录已加载，已上传图片读取失败，请刷新重试。") }
        }
        return page
    }

    suspend fun uploadedImage(entry: JSONObject, identity: String): Bitmap = withContext(Dispatchers.IO) {
        checkIdentity(identity)
        val encoded = if (entry.optString("imageDataUrl").isNotBlank()) entry.getString("imageDataUrl")
            else JSONObject(client.request("/v1/diary-images/${entry.getString("id")}", expectedIdentity = identity)).getString("imageDataUrl")
        check(Regex("^data:image/(png|jpeg|webp);base64,").containsMatchIn(encoded)) { "暂不支持此图片格式" }
        val bitmap = decodeImage(encoded, 640)
        checkIdentity(identity)
        bitmap
    }

    suspend fun sync(day: LocalDate, identity: String) = syncMutex.withLock {
        checkAccess(identity)
        val zone = ZoneId.of(client.user?.optString("timezone", "Asia/Shanghai") ?: "Asia/Shanghai")
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val devices = source.devices()
        val bound = JSONArray(client.request("/v1/devices", expectedIdentity = identity))
        val pending = mutableListOf<JSONObject>()
        for (device in devices) {
            check(identity == client.diaryIdentity) { "账号已切换，请重新打开日记" }
            val events = source.events(device.deviceSn, start, end)
            if (events.isEmpty()) continue
            var backendId: String? = null
            for (i in 0 until bound.length()) {
                val row = bound.getJSONObject(i)
                if (row.optString("provider") == "eufy" && row.optString("providerDeviceRef") == device.deviceSn) {
                    backendId = row.getString("id"); break
                }
            }
            if (backendId == null) {
                val row = JSONObject(client.request("/v1/devices", "POST", JSONObject()
                    .put("name", device.displayName).put("provider", "eufy")
                    .put("providerDeviceRef", device.deviceSn).put("platform", "android"), expectedIdentity = identity))
                bound.put(row); backendId = row.getString("id")
            }
            for (event in events) {
                val media = JSONArray()
                event.media.forEach { media.put(JSONObject().put("providerMediaKey", it.key).put("kind", it.kind).put("role", it.role)) }
                pending.add(JSONObject().put("deviceId", backendId).put("providerEventId", event.providerEventId)
                    .put("occurredAt", event.capturedAt.toString()).put("mediaItems", media))
            }
        }
        // Each chunk is atomic and idempotent. Retrying a partially completed day is safe.
        pending.chunked(100).forEach { rows ->
            val batch = JSONArray(); rows.forEach { batch.put(it) }
            client.request("/v1/diary-device-events/batch", "POST", JSONObject().put("events", batch), expectedIdentity = identity)
        }
    }

    private fun checkAccess(identity: String) {
        checkIdentity(identity)
        check(source.connected) { "请先在个人设置连接官方设备账号" }
    }

    private fun checkIdentity(identity: String) {
        check(identity == client.diaryIdentity && client.user != null) { "账号已切换，请重新打开日记" }
    }

    suspend fun image(media: JSONObject, identity: String): Bitmap = withContext(Dispatchers.IO) {
        checkAccess(identity)
        val encoded = source.image(media.getString("providerDeviceRef"), media.getString("providerEventId"),
            Instant.parse(media.getString("capturedAt")), media.getString("providerMediaKey"))
        checkAccess(identity)
        val bitmap = decodeImage(encoded, 1600)
        checkAccess(identity)
        bitmap
    }

    private fun decodeImage(encoded: String, maxEdge: Int): Bitmap {
        check(encoded.length <= 16 * 1024 * 1024) { "图片过大，无法预览" }
        val bytes = Base64.decode(encoded.substringAfter("base64,", encoded), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "设备图片无法解码" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("设备图片无法解码")
        return bitmap
    }

    suspend fun player(media: JSONObject, identity: String): com.anker.esiotkit.media.event_stream.EventMediaPlayer {
        checkAccess(identity)
        val player = source.player(media.getString("providerDeviceRef"), media.getString("providerEventId"),
            Instant.parse(media.getString("capturedAt")))
        if (identity != client.diaryIdentity) { player.release(); error("账号已切换，请重新打开日记") }
        return player
    }

    suspend fun link(eventId: String, mediaId: String, linked: Boolean, identity: String) {
        client.request("/v1/events/$eventId/media/$mediaId", if (linked) "PUT" else "DELETE",
            if (linked) JSONObject() else null, expectedIdentity = identity)
    }
}
