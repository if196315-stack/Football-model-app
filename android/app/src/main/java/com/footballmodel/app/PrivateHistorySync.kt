package com.footballmodel.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Personal history backup in the user's private GitHub repository. */
object PrivateHistorySync {
    private const val REPOSITORY = "if196315-stack/Football-model-app"
    private const val BACKUP_PATH = "private-data/history-v1.json"
    private const val TOKEN_PREFS = "private_history_auth"
    private const val TOKEN_KEY = "github_token"
    private const val KEY_ALIAS = "iq_match_github_token_v1"
    private val lock = Any()

    fun hasToken(context: Context): Boolean = !readToken(context).isNullOrBlank()

    fun saveToken(context: Context, token: String) {
        val normalized = token.trim()
        require(normalized.startsWith("github_pat_") || normalized.startsWith("ghp_")) {
            "Используй GitHub fine-grained token (github_pat_…)."
        }
        context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            .edit().putString(TOKEN_KEY, encrypt(normalized)).commit()
    }

    private fun readToken(context: Context): String? {
        val encrypted = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null) ?: return null
        return try { decrypt(encrypted) } catch (_: Exception) { null }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
    }

    fun syncAsync(context: Context, callback: ((Boolean, String) -> Unit)? = null) {
        val appContext = context.applicationContext
        Thread({
            val result = try {
                syncNow(appContext)
                true to "История синхронизирована с приватным GitHub-репозиторием."
            } catch (error: Throwable) {
                false to (error.message ?: "Не удалось синхронизировать историю.")
            }
            if (callback != null) Handler(Looper.getMainLooper()).post {
                callback(result.first, result.second)
            }
        }, "iq-match-private-history-sync").start()
    }

    private fun syncNow(context: Context) {
        synchronized(lock) {
            val token = readToken(context)
                ?: throw IllegalStateException("Сначала добавь GitHub token в настройках.")
            val betsPrefs = context.getSharedPreferences("bet_history", Context.MODE_PRIVATE)
            val signalsPrefs = context.getSharedPreferences("ai_signal_history", Context.MODE_PRIVATE)
            val settingsPrefs = context.getSharedPreferences("bank_settings", Context.MODE_PRIVATE)
            val localBets = parseArray(betsPrefs.getString("bets", "[]"))
            val localSignals = parseArray(signalsPrefs.getString("signals", "[]"))
            val localSettings = JSONObject().apply {
                settingsPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean, is Int, is Long, is Float, is String -> put(key, value)
                    }
                }
            }

            val remoteFile = request(token, "GET", null)
            val remotePayload = remoteFile?.optJSONObject("payload")
                ?: remoteFile?.optString("content")?.takeIf { it.isNotBlank() }?.let {
                    try { JSONObject(it) } catch (_: Exception) { null }
                }
            val mergedBets = mergeRows(remotePayload?.optJSONArray("bets") ?: JSONArray(), localBets,
                "id", listOf("date", "matchId", "marketKey", "source", "createdAt"))
            val mergedSignals = mergeRows(remotePayload?.optJSONArray("signals") ?: JSONArray(), localSignals,
                "signalKey", listOf("matchId", "side"))
            val mergedSettings = JSONObject().apply {
                val remote = remotePayload?.optJSONObject("settings") ?: JSONObject()
                remote.keys().forEach { put(it, remote.get(it)) }
                localSettings.keys().forEach { put(it, localSettings.get(it)) }
            }

            // Commit the merged local state first; retries remain idempotent.
            check(betsPrefs.edit().putString("bets", mergedBets.toString()).commit()) { "Не удалось сохранить локальную историю ставок." }
            check(signalsPrefs.edit().putString("signals", mergedSignals.toString()).commit()) { "Не удалось сохранить локальную историю сигналов." }
            val settingsEdit = settingsPrefs.edit()
            mergedSettings.keys().forEach { key ->
                when (val value = mergedSettings.get(key)) {
                    is Boolean -> settingsEdit.putBoolean(key, value)
                    is Int -> settingsEdit.putInt(key, value)
                    is Long -> settingsEdit.putLong(key, value)
                    is Double -> settingsEdit.putFloat(key, value.toFloat())
                    is String -> settingsEdit.putString(key, value)
                }
            }
            check(settingsEdit.commit()) { "Не удалось восстановить настройки." }

            val payload = JSONObject().put("schemaVersion", 1)
                .put("updatedAt", System.currentTimeMillis())
                .put("bets", mergedBets).put("signals", mergedSignals).put("settings", mergedSettings)
            val content = Base64.encodeToString(payload.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            val body = JSONObject().put("message", "Sync personal IQ Match history").put("content", content)
            remoteFile?.optString("sha")?.takeIf { it.isNotBlank() }?.let { body.put("sha", it) }
            request(token, "PUT", body) ?: throw IllegalStateException("GitHub не подтвердил сохранение архива.")
        }
    }

    private fun parseArray(raw: String?): JSONArray =
        try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun mergeRows(remote: JSONArray, local: JSONArray, primaryKey: String, fallback: List<String>): JSONArray {
        val rows = LinkedHashMap<String, JSONObject>()
        fun add(source: JSONArray, preferOnTie: Boolean) {
            for (index in 0 until source.length()) {
                val row = source.optJSONObject(index) ?: continue
                var key = row.optString(primaryKey).trim()
                if (key.isBlank()) key = fallback.joinToString("|") { field ->
                    row.optString(field, if (field == "createdAt") index.toString() else "")
                }
                if (key.isBlank()) key = "row:$index"
                val old = rows[key]
                if (old == null || preferNew(old, row, preferOnTie)) rows[key] = JSONObject(row.toString())
            }
        }
        add(remote, false)
        add(local, true)
        return JSONArray().apply { rows.values.forEach { put(it) } }
    }

    private fun preferNew(old: JSONObject, candidate: JSONObject, preferOnTie: Boolean): Boolean {
        fun rank(row: JSONObject): Int = when (row.optString("status").uppercase()) {
            "WON", "LOST", "PUSH", "VOID", "CANCELLED" -> 2
            "PENDING", "OPEN" -> 1
            else -> 0
        }
        val oldRank = rank(old)
        val newRank = rank(candidate)
        if (newRank != oldRank) return newRank > oldRank
        val oldTime = maxOf(old.optLong("settledAt", 0L), old.optLong("updatedAt", 0L))
        val newTime = maxOf(candidate.optLong("settledAt", 0L), candidate.optLong("updatedAt", 0L))
        if (newTime != oldTime) return newTime > oldTime
        return preferOnTie
    }

    /** Null means a missing backup on GET; all other non-2xx responses fail closed. */
    private fun request(token: String, method: String, body: JSONObject?): JSONObject? {
        val connection = (URL("https://api.github.com/repos/$REPOSITORY/contents/$BACKUP_PATH")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
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
                val detail = try { JSONObject(response).optString("message") } catch (_: Exception) { "" }
                throw IllegalStateException("GitHub API $code" + if (detail.isNotBlank()) ": $detail" else "")
            }
            return if (response.isBlank()) JSONObject().put("ok", true) else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
