package com.lookbuddy.app.data

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MemoryVault : SessionVault {
    private var value: StoredSession? = null
    @Synchronized override fun read() = value
    @Synchronized override fun save(session: StoredSession?) { value = session }
    @Synchronized override fun replace(expected: StoredSession, updated: StoredSession?): Boolean { if (value != expected) return false; value = updated; return true }
}
class ApiClientTest {
    private val user = UserDto("user-1", "test@example.com", "Test", "Asia/Shanghai")
    private fun tokens(access: String = "old", refresh: String = "refresh") = TokenPairDto(access, refresh, user)
    @Test fun originsAndPathsAreConstrained() {
        assertEquals("http://10.0.2.2:8000/", normalizeBaseUrl("http://10.0.2.2:8000", true))
        assertEquals("https://example.com/", normalizeBaseUrl("https://example.com", false))
        for (url in listOf("http://example.com", "http://10.evil.example", "https://u:p@example.com", "https://example.com/api", "https://example.com/?a=1")) {
            try { normalizeBaseUrl(url, true); fail(url) } catch (_: IllegalArgumentException) { }
        }
        try { normalizeBaseUrl("http://127.0.0.1:8000", false); fail() } catch (_: IllegalArgumentException) { }
        for (path in listOf("https://evil.example", "/v1/../account", "/v1/%2e%2e/account", "/v1/x\\x", "//evil.example")) {
            try { apiPath(path); fail(path) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun authenticatesAndRenewsOnlyOnce() = runBlocking {
        val vault = MemoryVault().apply { save(StoredSession(tokens(), "epoch")) }
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(ApiJson.encodeToString(tokens("new", "rotated"))))
        server.enqueue(MockResponse().setBody(ApiJson.encodeToString(user)))
        val client = ApiClient(server.url("/").toString(), vault)
        try {
            assertEquals(user, client.api.me())
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            val refresh = server.takeRequest(); assertEquals("/v1/auth/refresh", refresh.path); assertNull(refresh.getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization")); assertEquals("new", vault.read()!!.tokens.accessToken)
        } finally { client.close(); server.shutdown() }
    }
    @Test fun refreshServerErrorDoesNotClearLogin() = runBlocking { refreshFailure(500, false) }
    @Test fun invalidRefreshClearsLogin() = runBlocking { refreshFailure(401, true) }
    private suspend fun refreshFailure(code: Int, cleared: Boolean) {
        val vault = MemoryVault().apply { save(StoredSession(tokens(), "epoch")) }
        val server = MockWebServer(); server.start(); server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setResponseCode(code))
        val client = ApiClient(server.url("/").toString(), vault)
        try { try { client.api.me(); fail() } catch (_: HttpException) { }; assertEquals(cleared, vault.read() == null); assertEquals(2, server.requestCount) }
        finally { client.close(); server.shutdown() }
    }
    @Test fun lateRefreshCannotRestoreLoggedOutSession() = runBlocking {
        val vault = MemoryVault().apply { save(StoredSession(tokens(), "epoch")) }
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val server = MockWebServer(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/v1/auth/refresh") { started.countDown(); release.await(5, TimeUnit.SECONDS); return MockResponse().setBody(ApiJson.encodeToString(tokens("new"))) }
            return MockResponse().setResponseCode(401)
        } }; server.start()
        val client = ApiClient(server.url("/").toString(), vault)
        try {
            val work = async(Dispatchers.IO) { try { client.api.me(); false } catch (_: HttpException) { true } }
            assertTrue(started.await(5, TimeUnit.SECONDS)); vault.save(null); release.countDown()
            assertTrue(work.await()); assertNull(vault.read())
        } finally { release.countDown(); client.close(); server.shutdown() }
    }
    @Test fun concurrent401sShareOneRefresh() = runBlocking {
        val vault = MemoryVault().apply { save(StoredSession(tokens(), "epoch")) }
        val arrivals = CountDownLatch(2); val refreshes = AtomicInteger()
        val server = MockWebServer(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/v1/auth/refresh") { refreshes.incrementAndGet(); return MockResponse().setBody(ApiJson.encodeToString(tokens("new"))) }
            if (request.getHeader("Authorization") == "Bearer old") { arrivals.countDown(); arrivals.await(5, TimeUnit.SECONDS); return MockResponse().setResponseCode(401) }
            return MockResponse().setBody(ApiJson.encodeToString(user))
        } }; server.start(); val client = ApiClient(server.url("/").toString(), vault)
        try { val values = listOf(async(Dispatchers.IO) { client.api.me() }, async(Dispatchers.IO) { client.api.me() }).awaitAll(); assertEquals(listOf(user, user), values); assertEquals(1, refreshes.get()) }
        finally { client.close(); server.shutdown() }
    }
    @Test fun consoleCannotExposeTokensOrBypassAccountConfirmation() = runBlocking {
        val server = MockWebServer(); server.start(); val client = ApiClient(server.url("/").toString(), MemoryVault()); val repository = LookBuddyRepository(client, MemoryVault())
        try {
            for (path in listOf("/v1/auth/refresh", "/v1/%61uth/login", "/v1/account?x=1")) {
                try { repository.execute(path, "POST", "{}"); fail(path) } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
        } finally { repository.close(); server.shutdown() }
    }
    @Test fun failedDeletionIsNotReportedAsSuccess() = runBlocking {
        val vault = MemoryVault().apply { save(StoredSession(tokens(), "epoch")) }
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setResponseCode(403))
        val repository = LookBuddyRepository(ApiClient(server.url("/").toString(), vault), vault)
        try {
            try { repository.deleteAccount(); fail() } catch (error: HttpException) { assertEquals(500, error.code()) }
            assertNotNull(vault.read())
            try { repository.execute("/v1/diaries/2026-10-09", "DELETE", "{}"); fail() } catch (error: HttpException) { assertEquals(403, error.code()) }
        } finally { repository.close(); server.shutdown() }
    }
}
