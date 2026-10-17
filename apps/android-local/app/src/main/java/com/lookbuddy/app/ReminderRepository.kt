package com.lookbuddy.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Existing SQLite APIs serialize UTC columns without an offset; newer responses include Z. */
internal fun reminderInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse {
    java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC)
}

/** Ignore future values individually, so one new mood cannot abort profile/session sync. */
internal fun parseMoodRecords(records: JSONArray): Map<LocalDate, Mood> = buildMap {
    for (i in 0 until records.length()) {
        val row = records.optJSONObject(i) ?: continue
        val mood = Mood.fromBackend(row.optString("mood")) ?: continue
        val day = runCatching { LocalDate.parse(row.optString("date")) }.getOrNull() ?: continue
        put(day, mood)
    }
}

/** One account and connection generation. Foreground collectors own all polling work. */
internal class ReminderRepository(
    private val request: suspend (String, String, JSONObject?) -> String,
    private val claimId: String,
    private val intervalMs: Long = 12_000L,
    private val now: () -> Instant = { Instant.now() }
) : HomeReminderSource {
    constructor(client: ProductClient, identity: String) : this(
        request = { path, method, body -> client.request(path, method, body, expectedIdentity = identity) },
        claimId = "android:${client.installation}"
    )

    private var active: HomeReminderEvent? = null
    private val refreshes = Channel<Unit>(Channel.CONFLATED)
    fun refresh() { refreshes.trySend(Unit) }
    private val skipped = linkedSetOf<String>()
    override val updates: Flow<HomeReminderEvent?> = flow {
        // Re-entering foreground immediately resumes a still-pending card or queries pending.
        emit(active)
        while (true) {
            try {
                val pending = JSONArray(request("/v1/reminders/pending", "GET", null))
                currentCoroutineContext().ensureActive()
                val rows = (0 until pending.length()).mapNotNull { pending.optJSONObject(it) }
                    .filter { row -> runCatching { reminderInstant(row.getString("expiresAt")).isAfter(now()) }.getOrDefault(false) }
                if (active != null && rows.none { it.optString("id") == active?.id }) active = null
                if (active == null) for (row in rows) {
                    val id = row.optString("id")
                    if (id.isBlank() || id in skipped) continue
                    val kind = HomeReminderKind.fromBackendKey(row.optString("category")) ?: continue
                    try {
                        request("/v1/interventions/$id/displayed", "POST", JSONObject().put("claimId", claimId))
                        currentCoroutineContext().ensureActive()
                        active = HomeReminderEvent(id, kind, message = row.optString("message", kind.message), remote = true)
                        break
                    } catch (e: ProductHttpException) {
                        if (e.status != 409 && e.status != 404) throw e
                        // A display-budget 409 may become eligible later. Retry on the next poll.
                    }
                }
                emit(active)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep confirmed card; next foreground poll retries. */ }
            withTimeoutOrNull(intervalMs) { refreshes.receive() }
        }
    }
    override val reminders = updates.filterNotNull()

    override suspend fun respond(id: String, response: HomeReminderResponse) {
        check(active?.id == id) { "提醒已更新，请刷新" }
        request("/v1/reminders/$id/respond", "POST", JSONObject()
            .put("action", response.backendKey).put("claimId", claimId)
            .apply { if (response == HomeReminderResponse.Snoozed) put("snoozeMinutes", 10) })
        currentCoroutineContext().ensureActive()
        skipped.add(id)
        if (skipped.size > 128) skipped.remove(skipped.first())
        if (active?.id == id) active = null
    }
}
