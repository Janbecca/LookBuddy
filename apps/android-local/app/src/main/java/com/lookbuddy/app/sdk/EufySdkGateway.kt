@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.lookbuddy.app.sdk

import android.content.Context
import com.anker.esiotkit.ESIotKit
import com.anker.esiotkit.core.config.ESIotAppConfig
import com.anker.esiotkit.core.config.ESIotUserConfig
import com.anker.esiotkit.core.config.MegaAppDomain
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.event.model.EventCondition
import com.anker.esiotkit.manager.MegaEventManager
import com.anker.esiotkit.mega.category.Category
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Narrow boundary around the organizer SDK. Credentials stay in memory. */
interface EufySdkGateway {
    suspend fun initialize(config: EufyAccountConfig)
    suspend fun refreshDevices(): List<EufyDevice>
    suspend fun fetchRecentEvents(deviceSn: String): List<DeviceEvent>
    suspend fun loadThumbnailBase64(deviceSn: String, eventId: String): String?
    fun supportsPrivacyMode(deviceSn: String): Boolean
    suspend fun setPrivacyMode(deviceSn: String, enabled: Boolean)
    fun logout()
}

data class EufyAccountConfig(
    val appName: String,
    val clientId: String,
    val userId: String,
    val authorization: String,
    val appDomain: String,
    val deviceDomain: String,
    val presetKey: String,
) {
    fun requireValid() {
        require(appName.isNotBlank()) { "appName 不能为空" }
        require(clientId.isNotBlank()) { "clientId 不能为空" }
        require(userId.isNotBlank()) { "userId 不能为空" }
        require(authorization.isNotBlank()) { "authorization 不能为空" }
        require(appDomain.startsWith("https://")) { "appDomain 必须使用 HTTPS" }
        require(deviceDomain.startsWith("https://")) { "deviceDomain 必须使用 HTTPS" }
        require(presetKey.isNotBlank()) { "presetKey 不能为空" }
    }
}

data class EufyDevice(
    val deviceSn: String,
    val parentSn: String,
    val displayName: String,
    val model: String,
    val online: Boolean,
    val privacyModeSupported: Boolean,
)

data class DeviceEvent(
    val schema: String = "lookbuddy.event",
    val version: Int = 1,
    val eventId: String,
    val type: String = "device.security.event",
    val deviceId: String,
    val occurredAt: String,
    val source: DeviceEventSource,
    val payload: DeviceEventPayload,
    val evidence: DeviceEventEvidence = DeviceEventEvidence(),
    val privacy: DeviceEventPrivacy = DeviceEventPrivacy(),
)

data class DeviceEventSource(
    val kind: String = "device_event",
    val provider: String = "eufy",
    val sdk: String = "ESIotKit",
    val sdkVersion: String = "2.2.7",
    val rawType: String,
)

data class DeviceEventPayload(
    val providerEventId: String,
    val providerType: String,
    val hasThumbnail: Boolean,
)

data class DeviceEventEvidence(val method: String = "eufy_app_sdk")
data class DeviceEventPrivacy(
    val containsImage: Boolean = false,
    val retention: String = "event_only",
)

class RealEufySdkGateway(context: Context) : EufySdkGateway {
    private val appContext = context.applicationContext
    private val events = ConcurrentHashMap<String, MegaEvent>()
    @Volatile private var initialized = false

    override suspend fun initialize(config: EufyAccountConfig) = withContext(Dispatchers.IO) {
        config.requireValid()
        try {
            ESIotKit.start(ESIotAppConfig().apply {
                clientId = config.clientId
                appName = config.appName
                context = appContext
            })
            ESIotKit.setUserConfig(ESIotUserConfig().apply {
                presetKey = config.presetKey
                userId = config.userId
                appDomain = MegaAppDomain().apply {
                    setMegaDomain(config.appDomain)
                    setDomainBy(Category.Security, config.appDomain, config.presetKey)
                }
                deviceDomain = config.deviceDomain
                authorization = config.authorization
            })
            initialized = true
        } catch (cause: Exception) {
            runCatching { ESIotKit.logout() }
            throw cause
        }
    }

    override suspend fun refreshDevices(): List<EufyDevice> = withContext(Dispatchers.IO) {
        checkInitialized()
        withTimeout(SDK_TIMEOUT_MS) { MegaDeviceManager.refreshDeviceList().await() }
        MegaDeviceManager.deviceList.value.map { device ->
            EufyDevice(
                deviceSn = device.deviceSn,
                parentSn = device.parentSn,
                displayName = device.iotDevice.deviceName.takeUnless {
                    it.isNullOrBlank() || it == device.deviceSn
                } ?: device.deviceSn,
                model = device.devicePn,
                online = device.getProperty("device_is_online")?.get()?.asBoolean() ?: false,
                privacyModeSupported = device.hasProperty(PRIVACY_MODE),
            )
        }
    }

    override suspend fun fetchRecentEvents(deviceSn: String): List<DeviceEvent> {
        checkInitialized()
        require(deviceSn.isNotBlank()) { "deviceSn 不能为空" }
        val device = MegaDeviceManager.getDeviceBy(deviceSn)
            ?: error("SDK 设备列表中找不到该设备")
        val now = System.currentTimeMillis()
        val condition = EventCondition(
            _parentSnList = listOf(device.parentSn.ifBlank { deviceSn }),
            _startTime = now - EVENT_WINDOW_MS,
            _endTime = now,
        )
        val queried = withTimeout(SDK_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<List<MegaEvent>> { continuation ->
                    MegaEventManager.query(
                        condition,
                        refresh = true,
                        lastEventTime = 0,
                        onSuccess = { value -> if (continuation.isActive) continuation.resume(value) },
                        onFailure = { code, message ->
                            if (continuation.isActive) continuation.resumeWithException(
                                IllegalStateException("事件查询失败：$code $message")
                            )
                        },
                    )
                }
            }
        }.distinctBy { it.megaEventId }.sortedByDescending { it.startTimestamp }.take(MAX_EVENTS)
        return queried.map { event ->
            val providerType = event.thumbnail?.detectionType?.toString() ?: "unknown"
            val domainEventId = stableEventId(event.deviceSn, event.megaEventId)
            events[domainEventId] = event
            DeviceEvent(
                eventId = domainEventId,
                deviceId = event.deviceSn,
                occurredAt = Instant.ofEpochMilli(event.startTimestamp).toString(),
                source = DeviceEventSource(rawType = providerType),
                payload = DeviceEventPayload(
                    providerEventId = event.megaEventId,
                    providerType = providerType,
                    hasThumbnail = event.thumbnail?.picturePath?.isNotBlank() == true,
                ),
            )
        }
    }

    override suspend fun loadThumbnailBase64(deviceSn: String, eventId: String): String? {
        checkInitialized()
        val event = events[eventId] ?: return null
        require(event.deviceSn == deviceSn) { "事件与设备不匹配" }
        val candidates = listOfNotNull(event.thumbnail) + event.pictures
        for (picture in candidates.distinctBy { it.picturePath }) {
            if (picture.picturePath.isBlank() || picture.parentSn.isBlank()) continue
            val result = withTimeout(SDK_TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { continuation ->
                    MegaEventManager.getEventImageBase64(
                        picture.picturePath,
                        picture.parentSn,
                        onSuccess = { value -> if (continuation.isActive) continuation.resume(value) },
                        onFailure = { _, _ -> if (continuation.isActive) continuation.resume(null) },
                    )
                }
            }
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    override fun supportsPrivacyMode(deviceSn: String): Boolean =
        initialized && MegaDeviceManager.getDeviceBy(deviceSn)?.hasProperty(PRIVACY_MODE) == true

    override suspend fun setPrivacyMode(deviceSn: String, enabled: Boolean) {
        checkInitialized()
        val device = MegaDeviceManager.getDeviceBy(deviceSn)
            ?: error("SDK 设备列表中找不到该设备")
        check(device.getProperty("device_is_online")?.get()?.asBoolean() == true) {
            "设备离线，未发送控制命令"
        }
        val property = device.getProperty(PRIVACY_MODE)
            ?: error("该设备不支持隐私模式")
        check(property.writeEnable) { "该设备的隐私模式当前不可写" }
        val operation = property.set(ThingPayload.from(enabled))
        try {
            withTimeout(SDK_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    operation.onSuccess {
                        if (continuation.isActive) continuation.resume(Unit)
                    }.onFailure { code, _ ->
                        val recovery = when (code.code) {
                            -1002 -> "设备不支持该能力或物模型未就绪，请刷新设备后重试"
                            -1003 -> "设备响应超时，请检查在线状态后重试"
                            -1004 -> "隐私区已激活，当前操作被设备拦截"
                            else -> "设备拒绝了控制请求"
                        }
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("$recovery（${code.code}）")
                        )
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            error("设备 30 秒内未确认命令，请检查在线状态后重试")
        }
    }

    override fun logout() {
        events.clear()
        if (initialized) ESIotKit.logout()
        initialized = false
    }

    private fun checkInitialized() = check(initialized) { "请先初始化 eufy App SDK" }

    private fun stableEventId(deviceSn: String, providerEventId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$deviceSn:$providerEventId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "eufy_${digest.take(32)}"
    }

    private companion object {
        const val PRIVACY_MODE = "privacy_mode_enable"
        const val MAX_EVENTS = 20
        const val SDK_TIMEOUT_MS = 30_000L
        const val EVENT_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}

class MockEufySdkGateway : EufySdkGateway {
    override suspend fun initialize(config: EufyAccountConfig) { config.requireValid() }
    override suspend fun refreshDevices(): List<EufyDevice> = emptyList()
    override suspend fun fetchRecentEvents(deviceSn: String): List<DeviceEvent> = emptyList()
    override suspend fun loadThumbnailBase64(deviceSn: String, eventId: String): String? = null
    override fun supportsPrivacyMode(deviceSn: String): Boolean = false
    override suspend fun setPrivacyMode(deviceSn: String, enabled: Boolean) = Unit
    override fun logout() = Unit
}
