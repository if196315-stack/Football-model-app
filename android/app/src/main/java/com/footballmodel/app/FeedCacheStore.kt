package com.footballmodel.app

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Disk cache for the multi-megabyte match feeds. SharedPreferences remains
 * suitable for small ETags/timestamps, but not for repeatedly rewriting JSON
 * payloads during the foreground refresh cycle.
 */
internal object FeedCacheStore {
    private val filenames = mapOf(
        "domestic_json" to "domestic-feed.json",
        "uefa_json" to "uefa-feed.json",
        "merged_json" to "merged-feed.json"
    )
    private val lock = Any()

    fun read(directory: File, key: String): String? = synchronized(lock) {
        val file = resolve(directory, key) ?: return@synchronized null
        if (!file.isFile) return@synchronized null
        try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun write(directory: File, key: String, content: String): Boolean = synchronized(lock) {
        val target = resolve(directory, key) ?: return@synchronized false
        if (!directory.exists() && !directory.mkdirs()) return@synchronized false
        val temporary = File(directory, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                    stream.fd.sync()
                }
            }
            if (temporary.renameTo(target)) return@synchronized true
            // Some filesystems do not replace an existing target on renameTo.
            if (target.exists() && target.delete() && temporary.renameTo(target)) {
                return@synchronized true
            }
            temporary.delete()
            false
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    fun delete(directory: File, key: String): Boolean = synchronized(lock) {
        val file = resolve(directory, key) ?: return@synchronized false
        !file.exists() || file.delete()
    }

    private fun resolve(directory: File, key: String): File? =
        filenames[key]?.let { File(directory, it) }
}
