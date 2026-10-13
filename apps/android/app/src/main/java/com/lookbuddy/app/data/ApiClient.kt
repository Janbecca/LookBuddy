package com.lookbuddy.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

val ApiJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
interface SessionVault {
    fun read(): StoredSession?
    fun save(session: StoredSession?)
    fun replace(expected: StoredSession, updated: StoredSession?): Boolean
}
fun normalizeBaseUrl(value: String, debug: Boolean): String {
    val url = value.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) { "后端地址仅支持无凭证的 HTTP(S) origin" }
    val parts = url.host.split('.').map { it.toIntOrNull() }
    val ipv4 = parts.size == 4 && parts.all { it != null && it in 0..255 }
    val privateHost = url.host == "localhost" || url.host == "::1" || (ipv4 && (parts[0] == 127 || parts[0] == 10 ||
        (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)))
    require(url.isHttps || (debug && privateHost)) { "远端后端必须使用 HTTPS；Debug HTTP 仅允许本机/局域网" }
    return url.toString()
}
fun apiPath(value: String): String {
    val path = value.trim().removePrefix("/")
    val decoded = URLDecoder.decode(path, "UTF-8")
    require(path.startsWith("v1/") && !decoded.contains("..") && !decoded.contains('\\') && !decoded.contains(':') && !decoded.contains('#') && !decoded.any { it.isWhitespace() }) { "只允许本后端 /v1/ API" }
    return path
}

class ApiClient(val baseUrl: String, private val vault: SessionVault) {
    private val origin = baseUrl.toHttpUrl()
    private val refreshLock = Any()
    private val plain = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private data class RequestIdentity(val epoch: String)
    val client: OkHttpClient = plain.newBuilder().addInterceptor { chain ->
        val url = chain.request().url
        check(url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme) { "拒绝跨后端请求" }
        val session = vault.read()
        val authPath = url.encodedPath.startsWith("/v1/auth/")
        val request = chain.request().newBuilder()
        if (!authPath && session != null) request.header("Authorization", "Bearer ${session.tokens.accessToken}").tag(RequestIdentity::class.java, RequestIdentity(session.epoch))
        chain.proceed(request.build())
    }.authenticator(Authenticator { _, response ->
        if (response.priorResponse != null || response.request.url.encodedPath.startsWith("/v1/auth/")) return@Authenticator null
        synchronized(refreshLock) {
            val before = vault.read() ?: return@synchronized null
            if (response.request.tag(RequestIdentity::class.java)?.epoch != before.epoch) return@synchronized null
            if (response.request.header("Authorization") != "Bearer ${before.tokens.accessToken}") {
                return@synchronized response.request.newBuilder().header("Authorization", "Bearer ${before.tokens.accessToken}").build()
            }
            val request = Request.Builder().url(origin.resolve("v1/auth/refresh")!!)
                .post(ApiJson.encodeToString(RefreshRequest(before.tokens.refreshToken)).toRequestBody("application/json".toMediaType())).build()
            try {
                plain.newCall(request).execute().use { refresh ->
                    if (!refresh.isSuccessful) {
                        if (refresh.code == 401) vault.replace(before, null)
                        return@use null
                    }
                    val tokens = ApiJson.decodeFromString<TokenPairDto>(refresh.body!!.string())
                    if (tokens.user.id != before.tokens.user.id || !vault.replace(before, before.copy(tokens = tokens))) return@use null
                    response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
                }
            } catch (_: Exception) { null } // Offline must not silently clear a valid session.
        }
    }).build()
    val api: LookBuddyApi = Retrofit.Builder().baseUrl(baseUrl).client(client).addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build().create(LookBuddyApi::class.java)
    fun close() { client.dispatcher.cancelAll(); plain.dispatcher.cancelAll(); client.connectionPool.evictAll(); plain.connectionPool.evictAll() }
}
