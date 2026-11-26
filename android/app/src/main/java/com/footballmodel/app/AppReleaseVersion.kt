package com.footballmodel.app

/** Version metadata published with each signed GitHub Release. */
internal data class AppReleaseVersion(
    val versionName: String,
    val versionCode: Long?
) : Comparable<AppReleaseVersion> {
    override fun compareTo(other: AppReleaseVersion): Int {
        val nameComparison = compareVersionNames(versionName, other.versionName)
        if (nameComparison != 0) return nameComparison
        val leftCode = versionCode
        val rightCode = other.versionCode
        return if (leftCode != null && rightCode != null) leftCode.compareTo(rightCode) else 0
    }

    fun isNewerThan(installedVersionName: String, installedVersionCode: Long): Boolean {
        val installed = AppReleaseVersion(installedVersionName, installedVersionCode)
        return compareTo(installed) > 0
    }

    companion object {
        private val versionCodePattern = Regex("(?im)^Version-Code:\\s*(\\d+)\\s*$")

        fun fromRelease(tagName: String, releaseBody: String): AppReleaseVersion {
            val code = versionCodePattern.find(releaseBody)?.groupValues?.getOrNull(1)?.toLongOrNull()
            return AppReleaseVersion(tagName.removePrefix("v").trim(), code)
        }

        private fun compareVersionNames(left: String, right: String): Int {
            val a = left.removePrefix("v").split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
            val b = right.removePrefix("v").split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
            for (index in 0 until maxOf(a.size, b.size)) {
                val comparison = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (comparison != 0) return comparison
            }
            return 0
        }
    }
}
