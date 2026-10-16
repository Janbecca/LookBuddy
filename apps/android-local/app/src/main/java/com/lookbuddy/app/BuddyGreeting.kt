package com.lookbuddy.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * GLM 关心语气泡数据。
 *
 * @param show 是否展示气泡。
 * @param message 关心语文案；show=false 时为空。
 * @param source 来源："glm" / "fallback" / "error"。
 */
data class BuddyGreeting(
    val show: Boolean,
    val message: String,
    val source: String
) {
    companion object {
        val Idle = BuddyGreeting(show = false, message = "", source = "idle")
    }
}

/**
 * 关心语请求参数。
 *
 * @param scene 场景标识，后端决定模拟天气内容。
 * @param recentMessage 最近展示的关心语，用于去重；可为空。
 */
data class GreetingRequest(
    val scene: String,
    val recentMessage: String = ""
)

/**
 * 关心语仓库：调用本地后端，解析返回。
 *
 * 后端地址可通过 [baseUrl] 指定；默认为构建时常量 [DEFAULT_BASE_URL]。
 * 真机联调时在调用处传入电脑局域网 IP，见 [BuddyGreetingConfig]。
 */
class BuddyGreetingRepository(private val baseUrl: String = DEFAULT_BASE_URL) {

    /**
     * 请求关心语。网络或解析失败时返回 null。
     */
    suspend fun fetch(request: GreetingRequest): BuddyGreeting? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$baseUrl/buddy/greeting")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                put("scene", request.scene)
                put("recent_message", request.recentMessage)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "HTTP $code")
                return@runCatching null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val json = JSONObject(text)
            val show = json.optBoolean("show", false)
            val message = json.optString("message", "")
            val source = json.optString("source", "unknown")
            BuddyGreeting(show, message, source)
        }.getOrElse { e ->
            Log.w(TAG, "fetch failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "BuddyGreeting"
        private const val DEFAULT_BASE_URL = "http://10.0.2.2:5210"
    }
}

/**
 * 关心语配置：持有后端地址与默认场景。
 *
 * 真机联调时在 [MorningHomeContent] 外部传入电脑局域网 IP。
 * 模拟器默认使用 10.0.2.2 映射宿主机 localhost。
 */
object BuddyGreetingConfig {
    /** 模拟器联调地址：10.0.2.2 映射到开发机 localhost。 */
    const val EMULATOR_URL = "http://10.0.2.2:5210"
    /** 实机联调示例地址：替换为电脑局域网 IP。 */
    const val DEVICE_URL = "http://192.168.1.100:5210"
}

/**
 * 在 Composable 中记住关心语状态，并自动发起一次请求。
 *
 * - 仅在 [scene] 变化或首次进入时请求，避免重组重复请求。
 * - 请求在 [LaunchedEffect] 协程中执行，不阻塞首页渲染。
 * - 离开 Composition 时自动取消，不泄漏协程。
 */
@Composable
fun rememberBuddyGreeting(
    baseUrl: String = BuddyGreetingConfig.EMULATOR_URL,
    scene: String = "morning_seated"
): StateFlow<BuddyGreeting> {
    val product = LocalProduct.current
    val repository = remember(baseUrl) { BuddyGreetingRepository(baseUrl) }
    val greeting = remember(scene, baseUrl) { MutableStateFlow(BuddyGreeting.Idle) }
    LaunchedEffect(scene, baseUrl) {
        val result = if (product != null) runCatching {
            val response = JSONObject(product.request("/buddy/greeting", "POST", JSONObject().put("scene", scene)))
            BuddyGreeting(response.getBoolean("show"), response.getString("message"), response.getString("source"))
        }.getOrNull() else repository.fetch(GreetingRequest(scene))
        if (result != null) greeting.value = result
        // 失败时保持 Idle，首页正常展示，不阻塞。
    }
    return remember(greeting) { greeting.asStateFlow() }
}
