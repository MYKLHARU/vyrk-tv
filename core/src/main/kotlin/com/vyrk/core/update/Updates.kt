package com.vyrk.core.update

import com.vyrk.core.addon.ProtocolJson
import kotlinx.serialization.Serializable

@Serializable
data class GithubAsset(val name: String, val browser_download_url: String, val size: Long = 0, val content_type: String = "")

@Serializable
data class GithubRelease(
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val html_url: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

object Updates {
    const val CHECK_EVERY_MS = 4L * 3600 * 1000
    /** Opening the app checks again once this long has passed since the last check. */
    const val OPEN_CHECK_GAP_MS = 10L * 60 * 1000

    /** A release build ("0.1.11"), not a CI or pre-release one ("0.1.0-ci.41", "1.0.0-beta"). */
    fun isReleaseVersion(versionName: String): Boolean = versionName.isNotBlank() && '-' !in versionName && '+' !in versionName

    /** Check on opening the app: automatic updates on, and not checked in the last few minutes. */
    fun shouldCheckOnOpen(lastCheckMs: Long, nowMs: Long, automatic: Boolean): Boolean =
        automatic && (lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= OPEN_CHECK_GAP_MS)

    fun latestUrl(repo: String): String? =
        repo.trim().takeIf { Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""").matches(it) }
            ?.let { "https://api.github.com/repos/$it/releases/latest" }

    fun parse(json: String): GithubRelease = ProtocolJson.decodeFromString(GithubRelease.serializer(), json)

    /** Parses "v1.2.3" / "1.2.3" into comparable parts; non-numeric suffixes are ignored. */
    fun versionParts(tag: String): List<Int> =
        tag.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(tag: String, currentVersionName: String): Boolean {
        val a = versionParts(tag)
        val b = versionParts(currentVersionName)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Usable update: not a draft or pre-release, newer, with an APK for this ABI or universal. */
    fun pickAsset(release: GithubRelease, currentVersionName: String, abis: List<String>): GithubAsset? {
        if (release.draft || release.prerelease) return null
        if (!isNewer(release.tag_name, currentVersionName)) return null
        val apks = release.assets.filter { it.name.endsWith(".apk") }
        for (abi in abis) apks.firstOrNull { it.name.contains(abi) }?.let { return it }
        return apks.firstOrNull { it.name.contains("universal") }
    }

    fun shouldCheck(lastCheckMs: Long?, nowMs: Long, automatic: Boolean): Boolean =
        automatic && (lastCheckMs == null || nowMs - lastCheckMs >= CHECK_EVERY_MS)
}
