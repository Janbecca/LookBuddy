package com.lookbuddy.app.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

class DemoReplay(private val api: LookBuddyApi) {
    companion object { val scenarios = listOf("recovery", "recurrence", "gap", "chair", "break", "return", "ignored", "snoozed", "expression_relaxed", "expression_tense", "expression_unchanged", "expression_gap") }
    suspend fun run(scenario: String, learning: Boolean): JsonObject {
        require(scenario in scenarios)
        val device = api.postJson("v1/devices", buildJsonObject { put("name", "Android demo 回放"); put("platform", "android") }).jsonObject
        val session = api.postJson("v1/sessions", buildJsonObject { put("deviceId", device.id()) }).jsonObject
        val sessionId = session.id()
        try {
            if (scenario.startsWith("expression_")) return expression(sessionId, scenario, learning)
            val category = if (scenario in listOf("recovery", "recurrence", "gap", "ignored", "snoozed")) "posture" else "sedentary"
            var intervention = create(sessionId, category, learning)
            if (scenario in listOf("ignored", "snoozed")) return api.postJson("v1/debug/interventions/${intervention.id()}/respond", buildJsonObject { put("action", scenario) }).jsonObject
            val start = anchor(intervention).plusSeconds(1)
            suspend fun sample(second: Int, presence: String, posture: String, confidence: Double = 0.0) {
                intervention = api.postJson("v1/interventions/${intervention.id()}/samples", buildJsonObject {
                    put("frameId", "android-$second"); put("capturedAt", start.plusSeconds(second.toLong()).toString()); put("source", "replay"); put("cameraHealthy", true)
                    put("quality", if (presence == "away") "insufficient" else "observable"); put("posture", posture); put("presence", presence); put("standingConfidence", confidence)
                }).jsonObject["intervention"]!!.jsonObject
            }
            if (category == "posture") {
                val end = if (scenario == "recurrence") 82 else if (scenario == "gap") 20 else 140
                for (second in 0..end step 2) sample(second, "present", if (scenario == "recurrence" && second >= 22) "bad" else "normal")
                if (scenario == "gap") sample(26, "present", "normal")
            } else {
                sample(0, if (scenario == "chair") "seated" else "standing", "unknown", if (scenario == "chair") 0.0 else 0.8)
                if (scenario == "break") {
                    val minutes = intervention["context"]!!.jsonObject["preferences"]!!.jsonObject["breakMinutes"]!!.jsonPrimitive.int
                    for (second in 2..minutes * 60 + 2 step 2) sample(second, "away", "unknown")
                } else { sample(2, "away", "unknown"); if (scenario == "return") sample(4, "present", "unknown") }
            }
            return intervention
        } finally {
            withContext(NonCancellable) { try { api.postJson("v1/sessions/$sessionId/finish", buildJsonObject { }) } catch (_: Exception) { /* offline */ } }
        }
    }
    private suspend fun create(sessionId: String, category: String, learning: Boolean) = api.postJson("v1/debug/interventions", buildJsonObject { put("sessionId", sessionId); put("category", category); put("learning", learning) }).jsonObject
    private fun anchor(value: JsonObject): Instant {
        val displayed = value["displayedAt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        return Instant.parse(displayed ?: value["context"]!!.jsonObject["observationStartedAt"]!!.jsonPrimitive.content)
    }
    private suspend fun expression(sessionId: String, scenario: String, learning: Boolean): JsonObject {
        val previous = api.getJson("v1/expressions?namespace=demo&limit=1").jsonArray
        val latest = previous.firstOrNull()?.jsonObject?.get("capturedAt")?.jsonPrimitive?.content?.let { Instant.parse(it).plusSeconds(1) }
        val now = Instant.now(); val clock = if (latest != null && latest > now) latest else now
        val before = features(0.05, 0.4, 0.1, 0.4)
        for (second in 0..30) sendExpression(sessionId, clock.plusSeconds(second.toLong()), before)
        var intervention = create(sessionId, "emotion", learning)
        val after = when (scenario) { "expression_relaxed" -> features(0.55, 0.1, 0.05, 0.1); "expression_tense" -> features(0.05, 0.9, 0.1, 0.8); else -> before }
        val start = anchor(intervention).plusSeconds(1)
        for (second in 0..(if (scenario == "expression_gap") 30 else 150) step 2) {
            val response = sendExpression(sessionId, start.plusSeconds(second.toLong()), after)
            response["intervention"]?.takeUnless { it is JsonNull }?.let { intervention = it.jsonObject }
        }
        if (scenario == "expression_gap") {
            val response = sendExpression(sessionId, start.plusSeconds(36), after)
            response["intervention"]?.takeUnless { it is JsonNull }?.let { intervention = it.jsonObject }
        }
        return intervention
    }
    private suspend fun sendExpression(sessionId: String, at: Instant, features: JsonObject) = api.postJson("v1/expressions", buildJsonObject {
        put("expressionId", "exp_${UUID.randomUUID()}"); put("sessionId", sessionId); put("namespace", "demo"); put("source", "replay")
        put("capturedAt", at.toString()); put("cameraHealthy", true); put("quality", "observable"); put("features", features)
    }).jsonObject
    private fun features(smile: Double, brow: Double, frown: Double, press: Double) = buildJsonObject {
        put("faceVisible", true); put("frontal", true); put("lightingOk", true); put("smile", smile); put("browDown", brow)
        put("frown", frown); put("mouthPress", press); put("lookDown", 0.1); put("eyeClosed", 0.05)
    }
    private fun JsonObject.id() = this["id"]!!.jsonPrimitive.content
}
