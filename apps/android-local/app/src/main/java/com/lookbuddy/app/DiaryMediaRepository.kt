package com.lookbuddy.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
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
        check(identity == client.diaryIdentity && client.user != null) { "账号已切换，请重新打开日记" }
        check(source.connected) { "请先在个人设置连接官方设备账号" }
    }

    suspend fun image(media: JSONObject, identity: String): Bitmap = withContext(Dispatchers.IO) {
        checkAccess(identity)
        val encoded = source.image(media.getString("providerDeviceRef"), media.getString("providerEventId"),
            Instant.parse(media.getString("capturedAt")), media.getString("providerMediaKey"))
        checkAccess(identity)
        check(encoded.length <= 16 * 1024 * 1024) { "图片过大，无法预览" }
        val bytes = Base64.decode(encoded.substringAfter("base64,", encoded), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "设备图片无法解码" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("设备图片无法解码")
        checkAccess(identity)
        bitmap
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
