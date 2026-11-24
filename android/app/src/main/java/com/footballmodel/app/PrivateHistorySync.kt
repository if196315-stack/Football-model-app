package com.footballmodel.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Personal history is intentionally stored as readable JSON in the public repository, as requested. */
object PrivateHistorySync {
    private const val REPOSITORY = "if196315-stack/Football-model-app"
    private const val BACKUP_PATH = "private-data/history-v1.json"
    private const val PREFS = "private_history_auth"
    private const val TOKEN_KEY = "github_token"
    private val syncLock = Any()

    fun hasToken(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, null).isNullOrBlank()

    fun saveToken(context: Context, token: String) {
        val value = token.trim()
        require(value.startsWith("github_pat_") || value.startsWith("ghp_")) {
            "Введи GitHub personal access token (github_pat_… или ghp_…)."
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(TOKEN_KEY, value).commit()) { "Не удалось сохранить GitHub token." }
    }

    private fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, null)

    fun syncAsync(context: Context, callback: ((Boolean, String) -> Unit)? = null) {
        val appContext = context.applicationContext
        Thread({
            val result = try {
                syncNow(appContext)
                true to "История сохранена в GitHub и объединена с локальными записями."
            } catch (error: Throwable) {
                false to (error.message ?: "Ошибка синхронизации истории.")
            }
            callback?.let { done ->
                Handler(Looper.getMainLooper()).post { done(result.first, result.second) }
            }
        }, "iq-match-history-sync").start()
    }

    private fun syncNow(context: Context) = synchronized(syncLock) {
        val accessToken = token(context)
            ?: throw IllegalStateException("Сначала подключи GitHub token в настройках.")
        val betsPrefs = context.getSharedPreferences("bet_history", Context.MODE_PRIVATE)
        val signalsPrefs = context.getSharedPreferences("ai_signal_history", Context.MODE_PRIVATE)
        val settingsPrefs = context.getSharedPreferences("bank_settings", Context.MODE_PRIVATE)
        // Fetch the remote snapshot first. Reading local preferences before the network
        // request can capture an older state if a bet is settled while GET is in flight.
        val remoteFile = request(accessToken, "GET", null)
        val remotePayload = if (remoteFile == null) {
            null
        } else {
            val encoded = remoteFile.optString("content")
            if (encoded.isBlank()) throw IllegalStateException("GitHub вернул существующий архив без содержимого; остановил запись, чтобы не затереть историю.")
            try {
                val decoded = Base64.decode(encoded.replace("\\n", ""), Base64.DEFAULT)
                JSONObject(String(decoded, StandardCharsets.UTF_8))
            } catch (error: Exception) {
                throw IllegalStateException("Не удалось прочитать существующий архив; остановил запись, чтобы не потерять историю.", error)
            }
        }
        // Take the local snapshot only after the remote GET, immediately before merge.
        // This includes settlements saved while the network request was running.
        val localBets = parseArray(betsPrefs.getString("bets", "[]"))
        val localSignals = parseArray(signalsPrefs.getString("signals", "[]"))
        val localSettings = JSONObject().apply {
            settingsPrefs.all.forEach { (key, value) ->
                when (value) {
                    is Boolean, is Int, is Long, is Float, is String -> put(key, value)
                }
            }
        }

        val bets = mergeRows(remotePayload?.optJSONArray("bets") ?: JSONArray(), localBets, "id",
            listOf("matchId", "marketKey", "createdAt"))
        val signals = mergeRows(remotePayload?.optJSONArray("signals") ?: JSONArray(), localSignals, "signalKey",
            listOf("matchId", "side", "capturedAt"))
        val settings = JSONObject().apply {
            val remote = remotePayload?.optJSONObject("settings") ?: JSONObject()
            remote.keys().forEach { put(it, remote.get(it)) }
            localSettings.keys().forEach { put(it, localSettings.get(it)) }
        }

        check(betsPrefs.edit().putString("bets", bets.toString()).commit()) { "Не удалось восстановить ставки на телефоне." }
        check(signalsPrefs.edit().putString("signals", signals.toString()).commit()) { "Не удалось восстановить историю сигналов." }
        val settingsEdit = settingsPrefs.edit()
        settings.keys().forEach { key ->
            when (val value = settings.get(key)) {
                is Boolean -> settingsEdit.putBoolean(key, value)
                is Int -> settingsEdit.putInt(key, value)
                is Long -> settingsEdit.putLong(key, value)
                is Double -> settingsEdit.putFloat(key, value.toFloat())
                is String -> settingsEdit.putString(key, value)
            }
        }
        check(settingsEdit.commit()) { "Не удалось восстановить настройки." }

        val payload = JSONObject().put("schemaVersion", 1).put("updatedAt", System.currentTimeMillis())
            .put("bets", bets).put("signals", signals).put("settings", settings)
        val content = Base64.encodeToString(payload.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val body = JSONObject().put("message", "Sync IQ Match personal history").put("content", content)
        remoteFile?.optString("sha")?.takeIf { it.isNotBlank() }?.let { body.put("sha", it) }
        request(accessToken, "PUT", body) ?: throw IllegalStateException("GitHub не подтвердил запись архива.")
    }

    private fun parseArray(value: String?): JSONArray =
        try { JSONArray(value ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun mergeRows(remote: JSONArray, local: JSONArray, primary: String, fallback: List<String>): JSONArray {
        val merged = LinkedHashMap<String, JSONObject>()
        fun add(rows: JSONArray, preferTie: Boolean) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                var key = row.optString(primary).trim()
                if (key.isBlank()) key = fallback.joinToString("|") { row.optString(it) }
                if (key.isBlank()) key = "anonymous:" + i + ":" + row.toString().hashCode()
                val previous = merged[key]
                if (previous == null || shouldReplace(previous, row, preferTie)) merged[key] = JSONObject(row.toString())
            }
        }
        add(remote, false)
        add(local, true)
        return JSONArray().apply { merged.values.forEach { put(it) } }
    }

    private fun shouldReplace(old: JSONObject, fresh: JSONObject, preferTie: Boolean): Boolean {
        // An explicit, timestamped correction must beat stale terminal copies from
        // devices that have not yet received the correction. The marker travels
        // with the row, so subsequent merges preserve the corrected state.
        val oldResetAt = old.optLong("statusResetAt")
        val newResetAt = fresh.optLong("statusResetAt")
        if (oldResetAt != newResetAt) return newResetAt > oldResetAt

        fun rank(row: JSONObject): Int = when (row.optString("status").uppercase()) {
            "WON", "LOST", "PUSH", "VOID", "CANCELLED" -> 2
            "PENDING", "OPEN" -> 1
            else -> 0
        }
        val a = rank(old)
        val b = rank(fresh)
        if (a != b) return b > a
        val oldTime = maxOf(old.optLong("settledAt"), old.optLong("updatedAt"))
        val newTime = maxOf(fresh.optLong("settledAt"), fresh.optLong("updatedAt"))
        if (oldTime != newTime) return newTime > oldTime
        return preferTie
    }

    private fun request(token: String, method: String, body: JSONObject?): JSONObject? {
        val url = URL("https://api.github.com/repos/if196315-stack/Football-model-app/contents/private-data/history-v1.json")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "IQ-Match-Android")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            if (method == "GET" && code == HttpURLConnection.HTTP_NOT_FOUND) return null
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = try { JSONObject(response).optString("message") } catch (_: Exception) { "" }
                throw IllegalStateException("GitHub API " + code + if (message.isNotBlank()) ": " + message else "")
            }
            return if (response.isBlank()) JSONObject().put("ok", true) else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
