package com.lookbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import retrofit2.HttpException

class LookBuddyRepository(val transport: ApiClient, private val vault: SessionVault) {
    val api = transport.api
    val session get() = vault.read()
    @Volatile private var socket: WebSocket? = null
    private var socketToken: String? = null
    suspend fun authenticate(email: String, password: String, name: String?) {
        val tokens = if (name == null) api.login(LoginRequest(email, password)) else api.register(RegisterRequest(email, password, name))
        withContext(Dispatchers.IO) { vault.save(StoredSession(tokens, UUID.randomUUID().toString())) }
    }
    suspend fun logout() {
        val previous = session
        closeStream()
        withContext(Dispatchers.IO) { vault.save(null) }
        if (previous != null) try { api.logout(RefreshRequest(previous.tokens.refreshToken)) } catch (_: Exception) { /* local logout still effective */ }
    }
    suspend fun deleteAccount() {
        val response = api.deleteAccount()
        if (!response.isSuccessful) throw HttpException(response)
        closeStream(); withContext(Dispatchers.IO) { vault.save(null) }
    }
    suspend fun execute(path: String, method: String, body: String): JsonElement {
        val endpoint = apiPath(path)
        require(!endpoint.contains('%') && !endpoint.startsWith("v1/auth/") && endpoint.substringBefore('?') != "v1/account") { "账号操作请使用专用登录/退出/删除按钮，避免显示令牌" }
        return when (method) {
            "GET" -> api.getJson(endpoint)
            "POST" -> api.postJson(endpoint, ApiJson.parseToJsonElement(body))
            "PATCH" -> api.patchJson(endpoint, ApiJson.parseToJsonElement(body))
            "DELETE" -> { val response = api.deleteJson(endpoint); if (!response.isSuccessful) throw HttpException(response); buildJsonObject { put("status", "deleted") } }
            else -> error("不支持此 HTTP 方法")
        }
    }
    @Synchronized fun closeStream() { socket?.cancel(); socket = null; socketToken = null }
    @Synchronized fun openStream(onUpdate: () -> Unit, onStatus: (String) -> Unit) {
        if (session == null) return
        if (socket != null && socketToken == session!!.tokens.accessToken) return
        closeStream()
        val epoch = session!!.epoch
        socketToken = session!!.tokens.accessToken
        // Auth interceptor supplies a Bearer header. No token in URL or logs.
        val request = Request.Builder().url(transport.baseUrl + "v1/stream").build()
        socket = transport.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (session?.epoch == epoch) { onStatus("实时连接已建立"); onUpdate() } }
            override fun onMessage(webSocket: WebSocket, text: String) { if (session?.epoch == epoch) onUpdate() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected(webSocket, epoch, onStatus) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected(webSocket, epoch, onStatus) }
        })
    }
    @Synchronized private fun disconnected(value: WebSocket, epoch: String, onStatus: (String) -> Unit) {
        if (socket === value) { socket = null; if (session?.epoch == epoch) onStatus("实时断开，前台轮询补查") }
    }
    fun close() { closeStream(); transport.close() }
}
