package com.frodrigues.obdmqtt.webhook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Minimal read-only access to the HA REST API, used only to read the state of the
 * "clear faults" input_button. Needs a long-lived access token (ideally of a dedicated
 * non-admin user); HTTPS is enforced like for the webhook.
 */
class HaApiClient(haUrl: String, private val token: String, private val timeoutMs: Int = 10_000) {

    private val base = validateHaBaseUrl(haUrl)

    /** Returns the entity state, or null if the entity does not exist (404). */
    suspend fun getState(entityId: String): String? = withContext(Dispatchers.IO) {
        val conn = URI("$base/api/states/$entityId").toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                200 -> parseState(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                404 -> null
                401, 403 -> throw IOException("Jeton Home Assistant refusé (HTTP $code)")
                else -> throw IOException("Réponse HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val STATE_REGEX = Regex("\"state\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        fun parseState(json: String): String? = STATE_REGEX.find(json)?.groupValues?.get(1)
    }
}

/**
 * Decides when an `input_button` press means "clear the faults now". The button state is
 * the ISO timestamp of its last press; only recent presses count, so a stale press is
 * never replayed hours later when the car next starts.
 */
class ClearButtonWatcher(private val maxAge: Duration = Duration.ofMinutes(2)) {

    private var lastSeen: String? = null

    /** True once per new press made less than [maxAge] ago. */
    fun onState(state: String?, now: Instant = Instant.now()): Boolean {
        if (state == null || state == lastSeen) return false
        lastSeen = state
        val pressedAt = runCatching { OffsetDateTime.parse(state).toInstant() }.getOrNull() ?: return false
        // abs(): tolerate a phone clock slightly behind the HA server's
        return Duration.between(pressedAt, now).abs() < maxAge
    }
}
