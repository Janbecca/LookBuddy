package com.lookbuddy.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

internal val LocalProduct = staticCompositionLocalOf<ProductClient?> { null }

internal class ProductHttpException(val status: Int) : IllegalStateException("请求失败（HTTP $status），请检查后端或登录状态")

/** Device-local, encrypted session. No passwords, SMS codes or request logs. */
internal interface ProductSessionVault {
    fun read(): JSONObject?
    fun save(value: JSONObject?)
}
internal class ProductVault(context: Context) : ProductSessionVault {
    private val prefs = context.getSharedPreferences("lookbuddy.product.session", Context.MODE_PRIVATE)
    private val alias = "lookbuddy.product.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): JSONObject? = runCatching {
        val raw = prefs.getString("session", null) ?: return null
        val bits = raw.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(bits[0], Base64.NO_WRAP)))
        JSONObject(String(cipher.doFinal(Base64.decode(bits[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }.getOrNull()
    override fun save(value: JSONObject?) {
        if (value == null) { check(prefs.edit().remove("session").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("session", encoded).commit())
    }
}

internal class ProductClient(private val context: Context, private val scope: CoroutineScope,
                             private val vault: ProductSessionVault = ProductVault(context)) {
    private val prefs = context.getSharedPreferences("lookbuddy.product.connection", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val marksMutex = Mutex()
    private val transport = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var generation = 0
    private var session = vault.read()?.takeIf { value ->
        value.optJSONObject("user")?.let { !it.isNull("username") && it.optString("username").isNotBlank() } == true
    }
    val installation = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installation", it).apply() }
    var endpoint by mutableStateOf(prefs.getString("endpoint", "http://10.0.2.2:8080")!!); private set
    var user by mutableStateOf(session?.optJSONObject("user")); private set
    val username: String get() = if (user?.isNull("username") == false) user?.optString("username").orEmpty() else ""
    var summary by mutableStateOf(JSONObject()); private set
    var current by mutableStateOf<JSONObject?>(null); private set
    var error by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var moods by mutableStateOf<Map<LocalDate, Mood>>(emptyMap()); private set
    var camera: CameraFeed by mutableStateOf<CameraFeed>(UnconnectedCameraFeed()); private set
    var sdkStatus by mutableStateOf("官方设备未连接"); private set
    var connection by mutableStateOf("等待连接"); private set
    fun reportOffline() { connection = "离线，保留最近数据" }
    // Legacy local-history import stays unavailable for the new account system.
    val hasLegacy = false
    fun importOffered() = true
    fun skipImport() = Unit
    fun importLegacy() = Unit
    val gateway by lazy { com.lookbuddy.app.sdk.RealEufySdkGateway(context) }
    val diaryIdentity: String get() = "$endpoint:${user?.optString("id")}:$generation"
    val diaryMedia by lazy { DiaryMediaRepository(this) }

    private fun clear() {
        generation++
        vault.save(null); session = null; user = null; summary = JSONObject(); current = null; moods = emptyMap()
        camera.stop(); camera = UnconnectedCameraFeed(); gateway.logout(); sdkStatus = "官方设备未连接"
    }
    fun connect(value: String) = action {
        val uri = URI(value.trim())
        require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/")) { "请输入完整后端 origin" }
        val local = uri.host == "localhost" || uri.host == "10.0.2.2" || uri.host == "127.0.0.1" || uri.host.startsWith("192.168.") || uri.host.startsWith("10.") || Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..+").matches(uri.host)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" && local)) { "远端后端必须使用 HTTPS" }
        val next = value.trim().trimEnd('/')
        mutex.withLock { if (next != endpoint) { clear(); endpoint = next; prefs.edit().putString("endpoint", next).apply() } }
    }

    private fun exchange(path: String, method: String, body: JSONObject?, bearer: String?): Pair<Int, String> {
        val builder = Request.Builder().url(endpoint + path)
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return transport.newCall(builder.build()).execute().use { it.code to it.body?.string().orEmpty() }
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, authenticated: Boolean = true,
                        expectedIdentity: String? = null): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/v1/") || path == "/buddy/greeting")
        mutex.withLock {
            check(expectedIdentity == null || expectedIdentity == diaryIdentity) { "账号或设备连接已切换，请重新打开日记" }
            var response = exchange(path, method, body, if (authenticated) session?.optString("accessToken") else null)
            if (response.first == 401 && authenticated && session != null) {
                val refreshed = exchange("/v1/auth/refresh", "POST", JSONObject().put("refreshToken", session!!.getString("refreshToken")), null)
                if (refreshed.first == 200) {
                    val next = JSONObject(refreshed.second); vault.save(next); session = next
                    response = exchange(path, method, body, next.getString("accessToken"))
                } else if (refreshed.first == 401) {
                    withContext(Dispatchers.Main) { clear() }
                }
            }
            if (response.first !in 200..299) throw ProductHttpException(response.first)
            check(expectedIdentity == null || expectedIdentity == diaryIdentity) { "账号或设备连接已切换，请重新打开日记" }
            response.second
        }
    }
    suspend fun login(phone: String, nickname: String, code: String = ""): Boolean {
        return try {
            require(phone.trim().isNotEmpty()) { "请输入各端一致的用户名" }
            generation++
            val result = JSONObject(request("/v1/auth/username", "POST", JSONObject().put("username", phone.trim()).put("password", code)
                .put("displayName", nickname.trim().ifEmpty { "体验用户" }), false))
            marksMutex.withLock {
                clear()
                vault.save(result); session = result; user = result.getJSONObject("user"); error = ""
            }
            sync(); true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "演示登录失败"; false }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    suspend fun sync() {
        if (session == null) return
        val epoch = generation
        val updatedUser = JSONObject(request("/v1/profile"))
        val planned = JSONObject(request("/v1/work-schedule"))
        val now = LocalDate.now(java.time.ZoneId.of(updatedUser.optString("timezone", "Asia/Shanghai")))
        val records = JSONArray(request("/v1/moods?start=${now.minusDays(6)}&end=$now"))
        val response = request("/v1/work/heartbeat", "POST", JSONObject().put("installationId", installation))
        val updatedSummary = JSONObject(request("/v1/home/summary"))
        if (epoch != generation) return
        user = updatedUser
        WorkScheduleStore(context).save(WorkSchedule(java.time.LocalTime.parse(planned.getString("start")), java.time.LocalTime.parse(planned.getString("end"))))
        moods = parseMoodRecords(records)
        current = if (response == "null") null else JSONObject(response)
        summary = updatedSummary
        connection = "后端在线"
    }
    fun startWork() = action { request("/v1/work/start", "POST", JSONObject().put("installationId", installation)); sync() }
    fun finishWork() = action { current?.let { request("/v1/sessions/${it.getString("id")}/finish", "POST", JSONObject()) }; sync() }
    suspend fun saveMood(day: LocalDate, mood: Mood): Boolean = try {
        val identity = diaryIdentity
        request("/v1/moods/$day", "PUT", JSONObject().put("mood", mood.name), expectedIdentity = identity)
        check(identity == diaryIdentity)
        moods = moods + (day to mood); true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { error = e.message ?: "保存失败"; false }
    suspend fun deleteMood(day: LocalDate): Boolean = try {
        val identity = diaryIdentity
        request("/v1/moods/$day", "DELETE", expectedIdentity = identity)
        check(identity == diaryIdentity)
        moods = moods - day; true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { error = e.message ?: "删除失败"; false }
    suspend fun saveName(value: String): Boolean = try {
        user = JSONObject(request("/v1/me", "PATCH", JSONObject().put("displayName", value.ifBlank { "体验用户" }))); true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { error = e.message ?: "保存失败"; false }
    fun saveSchedule(value: WorkSchedule) = action {
        request("/v1/work-schedule", "PATCH", JSONObject().put("start", value.start.toString()).put("end", value.end.toString())); sync()
    }
    fun logout() = action {
        session?.let { runCatching { request("/v1/auth/logout", "POST", JSONObject().put("refreshToken", it.getString("refreshToken"))) } }
        clear()
    }
    fun deleteAccount() = action {
        val owner = user?.optString("id")
        request("/v1/account", "DELETE")
        owner?.let { prefs.edit().remove("marks:$it").remove("import:$it").commit() }
        clear()
    }
    suspend fun mark(eventId: String = "mark_" + UUID.randomUUID().toString()): String = marksMutex.withLock {
        val entry = JSONObject().put("eventId", eventId).put("occurredAt", Instant.now().toString()).put("note", "主动标记了一个工位瞬间")
        val queue = prefs.getString("marks:${user!!.getString("id")}", "[]")!!.let(::JSONArray)
        queue.put(entry); check(prefs.edit().putString("marks:${user!!.getString("id")}", queue.toString()).commit()) { "本机保存失败，请重试" }
        try {
            flushMarksUnlocked()
            "标记已进入日记，本轮不录制视频。"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "标记已暂存本机，联网后补传；本轮不录制视频。" }
    }
    suspend fun flushMarks() = marksMutex.withLock { flushMarksUnlocked() }
    private suspend fun flushMarksUnlocked() {
        val owner = user?.optString("id") ?: return
        val key = "marks:${user?.optString("id") ?: return}"
        val queue = JSONArray(prefs.getString(key, "[]"))
        for (i in 0 until queue.length()) {
            check(user?.optString("id") == owner) { "账号已切换，停止旧账号补传" }
            request("/v1/marks", "POST", queue.getJSONObject(i))
            val remaining = JSONArray(); for (j in i+1 until queue.length()) remaining.put(queue.getJSONObject(j))
            prefs.edit().putString(key, remaining.toString()).commit()
        }
    }
    fun connectCamera(config: com.lookbuddy.app.sdk.EufyAccountConfig) = action {
        generation++
        gateway.logout()
        gateway.initialize(config)
        val devices = gateway.refreshDevices().filter { it.online }
        check(devices.isNotEmpty()) { "账号下没有在线设备" }
        camera.stop(); camera = EufyCameraFeed(devices.first().deviceSn)
        sdkStatus = "已选择 ${devices.first().displayName}；进入陪伴视角播放"
    }
    fun release() { camera.stop(); gateway.logout() }
}

@Composable
internal fun ProductHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ProductClient(context, scope) }
    val lifecycle = LocalLifecycleOwner.current
    var editServer by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(client.endpoint) }
    val zone = client.user?.optString("timezone", "Asia/Shanghai") ?: java.time.ZoneId.systemDefault().id
    val clock = remember(zone) { { java.time.LocalDateTime.now(java.time.ZoneId.of(zone)) } }
    var importPrompt by remember(client.user?.optString("id")) { mutableStateOf(client.user != null && client.hasLegacy && !client.importOffered()) }
    LaunchedEffect(client.user?.optString("id")) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try { client.sync(); client.flushMarks() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { client.reportOffline() }
                delay(30000)
            }
        }
    }
    DisposableEffect(client) { onDispose { client.release() } }
    CompositionLocalProvider(LocalProduct provides client, LocalAppNow provides clock) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { address = client.endpoint; editServer = true }, modifier = Modifier.statusBarsPadding()) {
                Text("演示用户名 · 未验证 · ${client.connection} · 后端设置", style = MaterialTheme.typography.labelSmall)
            }
            if (client.error.isNotBlank()) Text(client.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
            Box(Modifier.weight(1f)) { content() }
        }
    }
    if (editServer) AlertDialog(onDismissRequest = { editServer = false }, title = { Text("后端地址") },
        text = { OutlinedTextField(address, { address = it }, label = { Text("HTTPS origin；模拟器 10.0.2.2:8080") }) },
        confirmButton = { TextButton(onClick = { client.connect(address); editServer = false }) { Text("连接") } },
        dismissButton = { TextButton(onClick = { editServer = false }) { Text("取消") } })
    if (importPrompt) AlertDialog(onDismissRequest = { client.skipImport(); importPrompt = false }, title = { Text("迁入这台设备的旧记录？") },
        text = { Text("将原本机心情和工作时段导入当前演示账号；已有云端心情不会被覆盖。") },
        confirmButton = { TextButton(onClick = { client.importLegacy(); importPrompt = false }) { Text("导入当前账号") } },
        dismissButton = { TextButton(onClick = { client.skipImport(); importPrompt = false }) { Text("暂不导入") } })
}
