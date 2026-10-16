package com.lookbuddy.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI

/** Override only at the composition boundary, e.g. an isolated transport test. */
internal val LocalDesktopPetSyncRepository = staticCompositionLocalOf<DesktopPetSyncRepository?> { null }

internal fun desktopPetOriginAllowed(value: String, debug: Boolean = BuildConfig.DEBUG): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase() ?: return@runCatching false
    val local = host in setOf("localhost", "127.0.0.1", "10.0.2.2") ||
        Regex("10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+").matches(host)
    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/") &&
        (uri.scheme == "https" || (debug && uri.scheme == "http" && local))
}.getOrDefault(false)

internal data class DesktopPetSyncRequest(
    val scene: String,
    val working: Boolean,
    val mood: String = "calm",
    val message: String? = null,
    val eventId: String? = null,
    val defaultScene: String = "idle",
    val completed: Boolean = false
)

internal data class DesktopPetSnapshot(
    val connected: Boolean,
    val revision: Long,
    val mood: String
)

/** Permanent LAN bridge between the Android app and the desktop pet; AWS is app-only. */
internal class DesktopPetSyncRepository(
    private val baseUrl: String = BuildConfig.LOOKBUDDY_DESKTOP_URL,
    private val token: String = BuildConfig.LOOKBUDDY_DESKTOP_TOKEN
) {
    val configured: Boolean get() = desktopPetOriginAllowed(baseUrl)

    suspend fun sync(request: DesktopPetSyncRequest): DesktopPetSnapshot? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        runCatching {
            val connection = (URL("${baseUrl.trimEnd('/')}/v1/sync").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2_500
                readTimeout = 2_500
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotBlank()) setRequestProperty("X-LookBuddy-Token", token)
            }
            try {
                val body = JSONObject().apply {
                    put("client_id", "android")
                    put("scene", request.scene)
                    put("default_scene", request.defaultScene)
                    request.eventId?.let { put("event_id", it) }
                    put("working", request.working)
                    put("mood", request.mood)
                    put("message", request.message.orEmpty())
                    put("completed", request.completed)
                }.toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "desktop sync HTTP ${connection.responseCode}")
                    return@runCatching null
                }
                val text = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                val desktop = JSONObject(text).getJSONObject("desktop")
                DesktopPetSnapshot(
                    connected = desktop.optBoolean("connected", false),
                    revision = desktop.optLong("revision", 0L),
                    mood = desktop.optString("mood", "calm")
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            // Desktop pet is optional: keep the Android home usable when the PC is offline.
            Log.d(TAG, "desktop pet unavailable: ${it.message}")
            null
        }
    }

    private companion object {
        const val TAG = "DesktopPetSync"
    }
}

/** Sync only while Home is composed and the app lifecycle is at least STARTED. */
@Composable
internal fun DesktopPetSyncEffect(
    scene: String,
    working: Boolean,
    message: String?,
    eventId: String? = null,
    defaultScene: String = "idle",
    completed: Boolean = false
) {
    val inspection = LocalInspectionMode.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = LocalDesktopPetSyncRepository.current ?: remember { DesktopPetSyncRepository() }
    LaunchedEffect(repository, lifecycle, scene, working, message, eventId, defaultScene, completed, inspection) {
        if (inspection || !repository.configured) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                repository.sync(DesktopPetSyncRequest(scene, working, message = message, eventId = eventId, defaultScene = defaultScene, completed = completed))
                delay(2_000)
            }
        }
    }
}
