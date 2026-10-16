package com.lookbuddy.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
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

internal data class DesktopPetSyncRequest(
    val scene: String,
    val working: Boolean,
    val mood: String = "calm",
    val message: String? = null,
    val eventId: String? = null,
    val defaultScene: String = "idle"
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
    suspend fun sync(request: DesktopPetSyncRequest): DesktopPetSnapshot? = withContext(Dispatchers.IO) {
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
                    request.message?.takeIf { it.isNotBlank() }?.let { put("message", it) }
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
    defaultScene: String = "idle"
) {
    val inspection = LocalInspectionMode.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember { DesktopPetSyncRepository() }
    LaunchedEffect(scene, working, message, eventId, defaultScene, inspection) {
        if (inspection || BuildConfig.LOOKBUDDY_DESKTOP_URL.isBlank()) return@LaunchedEffect
        while (currentCoroutineContext().isActive) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                repository.sync(DesktopPetSyncRequest(scene, working, message = message, eventId = eventId, defaultScene = defaultScene))
            }
            delay(15_000)
        }
    }
}
