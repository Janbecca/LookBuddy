package com.lookbuddy.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lookbuddy.app.data.*
import com.lookbuddy.app.sdk.DeviceEvent
import com.lookbuddy.app.sdk.EufyAccountConfig
import com.lookbuddy.app.sdk.EufyDevice
import com.lookbuddy.app.sdk.RealEufySdkGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import retrofit2.HttpException
import java.time.LocalDate
import java.time.ZoneId

data class ClientState(
    val baseUrl: String, val user: UserDto? = null, val preferences: PreferencesDto? = null,
    val reminders: List<ReminderDto> = emptyList(), val displayed: Set<String> = emptySet(),
    val namespace: String = "live", val interventions: String = "[]", val expressions: String = "[]",
    val timeline: String = "[]", val busy: Boolean = false, val connection: String = "等待连接",
    val error: String = "", val result: String = "", val diary: String = "",
    val eufyReady: Boolean = false, val eufyStatus: String = "App SDK 未连接",
    val eufyDevices: List<EufyDevice> = emptyList(), val eufyDeviceSn: String = "",
    val eufyEvents: List<DeviceEvent> = emptyList(), val eufyThumbnailBase64: String? = null,
)

private fun ClientState.resetBackend(baseUrl: String = this.baseUrl, status: String) =
    ClientState(
        baseUrl = baseUrl,
        connection = status,
        eufyReady = eufyReady,
        eufyStatus = eufyStatus,
        eufyDevices = eufyDevices,
        eufyDeviceSn = eufyDeviceSn,
        eufyEvents = eufyEvents,
        eufyThumbnailBase64 = eufyThumbnailBase64,
    )

class LookBuddyViewModel(application: Application) : AndroidViewModel(application) {
    private val eufyGateway = RealEufySdkGateway(application)
    private val settings = application.getSharedPreferences("lookbuddy.connection.v1", 0)
    private val vault = SecureSessionStore(application)
    private var repository = LookBuddyRepository(ApiClient(normalizeBaseUrl(settings.getString("baseUrl", BuildConfig.API_BASE_URL)!!, BuildConfig.DEBUG), vault), vault)
    private val mutable = MutableStateFlow(ClientState(repository.transport.baseUrl, repository.session?.tokens?.user))
    val state = mutable.asStateFlow()
    private val syncMutex = Mutex()
    private var work: Job? = null
    private var polling: Job? = null
    private var foreground = false
    private var generation = 0
    fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        polling?.cancel(); polling = null
        if (!value) { repository.closeStream(); return }
        polling = viewModelScope.launch {
            while (isActive) { sync(); delay(5000) }
        }
    }
    fun connect(url: String) {
        if (state.value.busy) return
        val normalized = try { normalizeBaseUrl(url, BuildConfig.DEBUG) } catch (_: Exception) {
            mutable.update { it.copy(error = "后端地址无效；Debug HTTP 仅用于本机/局域网，远端使用 HTTPS") }; return
        }
        if (normalized == repository.transport.baseUrl) { refresh(); return }
        generation += 1
        work?.cancel(); repository.close(); vault.save(null)
        settings.edit().putString("baseUrl", normalized).apply()
        repository = LookBuddyRepository(ApiClient(normalized, vault), vault)
        mutable.value = state.value.resetBackend(normalized, "后端已切换，请重新登录")
    }
    fun initializeEufy(config: EufyAccountConfig) = sdkAction {
        mutable.update {
            it.copy(eufyStatus = "正在初始化官方 App SDK…", eufyThumbnailBase64 = null)
        }
        eufyGateway.initialize(config)
        val devices = eufyGateway.refreshDevices()
        mutable.update {
            it.copy(
                eufyReady = true,
                eufyStatus = "官方 App SDK 已连接 · " + devices.size + " 台设备",
                eufyDevices = devices,
                eufyDeviceSn = devices.firstOrNull()?.deviceSn.orEmpty(),
                eufyEvents = emptyList(),
                eufyThumbnailBase64 = null,
            )
        }
    }
    fun refreshEufyDevices() = sdkAction {
        val devices = eufyGateway.refreshDevices()
        mutable.update { old ->
            val selected = old.eufyDeviceSn.takeIf { sn -> devices.any { it.deviceSn == sn } }
                ?: devices.firstOrNull()?.deviceSn.orEmpty()
            old.copy(
                eufyStatus = "设备列表已刷新 · " + devices.size + " 台",
                eufyDevices = devices,
                eufyDeviceSn = selected,
            )
        }
    }
    fun selectEufyDevice(deviceSn: String) {
        if (state.value.eufyDevices.none { it.deviceSn == deviceSn }) return
        mutable.update {
            it.copy(eufyDeviceSn = deviceSn, eufyEvents = emptyList(), eufyThumbnailBase64 = null)
        }
    }
    fun fetchEufyEvents() = sdkAction {
        val deviceSn = state.value.eufyDeviceSn
        val events = eufyGateway.fetchRecentEvents(deviceSn)
        mutable.update {
            it.copy(
                eufyStatus = "过去 24 小时事件 · " + events.size + " 条",
                eufyEvents = events,
                eufyThumbnailBase64 = null,
            )
        }
    }
    fun fetchEufyThumbnail(eventId: String) = sdkAction {
        val value = eufyGateway.loadThumbnailBase64(state.value.eufyDeviceSn, eventId)
            ?: error("该事件没有可读取的缩略图")
        mutable.update { it.copy(eufyThumbnailBase64 = value, eufyStatus = "已读取真实事件缩略图") }
    }
    fun setEufyPrivacyMode(enabled: Boolean) = sdkAction {
        val deviceSn = state.value.eufyDeviceSn
        check(eufyGateway.supportsPrivacyMode(deviceSn)) { "该设备不支持隐私模式" }
        eufyGateway.setPrivacyMode(deviceSn, enabled)
        val devices = eufyGateway.refreshDevices()
        mutable.update {
            it.copy(
                eufyStatus = "设备已确认：隐私模式" + if (enabled) "开启" else "关闭",
                eufyDevices = devices,
            )
        }
    }
    fun disconnectEufy() {
        eufyGateway.logout()
        mutable.update {
            it.copy(
                eufyReady = false,
                eufyStatus = "App SDK 已断开，内存凭证已释放",
                eufyDevices = emptyList(),
                eufyDeviceSn = "",
                eufyEvents = emptyList(),
                eufyThumbnailBase64 = null,
            )
        }
    }
    fun authenticate(email: String, password: String, name: String?) = action {
        repository.authenticate(email, password, name)
        mutable.update { it.copy(user = repository.session?.tokens?.user, error = "") }
        sync()
    }
    fun logout() = action {
        generation += 1
        repository.logout()
        mutable.update { it.resetBackend(status = "已退出；离线时服务端刷新令牌撤销可能未送达") }
    }
    fun deleteAccount() = action {
        repository.deleteAccount(); generation += 1
        mutable.update { it.resetBackend(status = "账号和关联数据已删除") }
    }
    fun refresh() { viewModelScope.launch { sync() } }
    private suspend fun sync(): Unit = syncMutex.withLock {
        val target = repository
        val epoch = target.session?.epoch ?: return@withLock
        val at = generation
        try {
            target.api.me() // renew expired access token before WebSocket handshakes
            val prefs = target.api.preferences()
            val reminders = target.api.pendingReminders()
            val namespace = state.value.namespace
            val interventions = target.api.getJson("v1/interventions?namespace=$namespace&limit=20")
            val expressions = target.api.getJson("v1/expressions?namespace=$namespace&limit=5")
            val timeline = target.api.getJson("v1/timeline?limit=15")
            if (at != generation || target !== repository || target.session?.epoch != epoch || namespace != state.value.namespace) return@withLock
            mutable.update { it.copy(user = target.session?.tokens?.user, preferences = prefs, reminders = reminders,
                displayed = it.displayed.intersect(reminders.map { r -> r.id }.toSet()),
                interventions = interventions.toString(), expressions = expressions.toString(), timeline = timeline.toString(),
                connection = "在线 · 前台每 5 秒补查", error = "") }
            if (foreground) target.openStream({ viewModelScope.launch { if (target === repository && foreground) sync() } }, { status ->
                if (target === repository && foreground) mutable.update { it.copy(connection = status) }
            })
        } catch (cause: CancellationException) { throw cause }
        catch (cause: Exception) {
            if (at == generation && target === repository) mutable.update { it.copy(
                user = target.session?.tokens?.user, connection = "断开/请求失败；保留最近数据，前台自动重试", error = message(cause)) }
        }
    }
    fun preferences(patch: JsonObject) = action {
        val updated = repository.api.patchPreferences(patch)
        mutable.update { it.copy(preferences = updated) }; sync()
    }
    fun display(id: String) = action {
        val value = repository.api.displayed(id)
        val status = value["status"]?.jsonPrimitive?.content
        if (status in setOf("improved", "unknown", "recurred", "interrupted", "cancelled", "rescheduled", "expression_relaxed", "expression_tense", "expression_unchanged")) {
            sync(); error("此干预已结束，请刷新")
        }
        mutable.update { it.copy(displayed = it.displayed + id) }
    }
    fun respond(id: String, response: String) = action {
        repository.api.respondReminder(id, ReminderResponseRequest(response, if (response == "snoozed") 10 else null))
        sync()
    }
    fun namespace(value: String) { if (value !in setOf("live", "demo")) return; mutable.update { it.copy(namespace = value) }; refresh() }
    fun inspect(path: String) = execute(path, "GET", "{}")
    fun execute(path: String, method: String, body: String) = action {
        val result = repository.execute(path, method, body)
        mutable.update { it.copy(result = result.toString()) }; sync()
    }
    fun replay(scenario: String, learning: Boolean) = action {
        val result = DemoReplay(repository.api).run(scenario, learning)
        mutable.update { it.copy(result = "【demo 加速回放；不代表真人效果】\n$result", namespace = "demo") }; sync()
    }
    fun generateDiary() = action {
        val timezone = state.value.user?.timezone ?: "Asia/Shanghai"
        val date = LocalDate.now(ZoneId.of(timezone)).toString()
        mutable.update { it.copy(diary = repository.api.generateDiary(date).content) }
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        val at = generation
        mutable.update { it.copy(busy = true, error = "") }
        work = viewModelScope.launch {
            try { block() }
            catch (cause: CancellationException) { throw cause }
            catch (cause: Exception) { if (at == generation) { mutable.update { it.copy(error = message(cause)) }; sync() } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun sdkAction(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = "") }
        work = viewModelScope.launch {
            try { block() }
            catch (cause: CancellationException) { throw cause }
            catch (cause: Exception) {
                mutable.update { it.copy(error = message(cause), eufyStatus = "App SDK 操作失败") }
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun message(cause: Exception) = when (cause) {
        is HttpException -> "HTTP ${cause.code()}：请求未完成。409 表示已处理/预算限制；可刷新服务端状态。"
        is java.io.IOException -> "网络连接失败，请检查后端地址和网络。"
        else -> cause.message?.take(240) ?: "操作未完成，请检查输入或刷新；不会自动伪造成功。"
    }
    override fun onCleared() { repository.close(); eufyGateway.logout(); super.onCleared() }
}
