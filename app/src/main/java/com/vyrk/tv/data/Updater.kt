package com.vyrk.tv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vyrk.core.update.GithubAsset
import com.vyrk.core.update.GithubRelease
import com.vyrk.core.update.Updates
import com.vyrk.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** In-app updates from GitHub Releases. Disabled in store builds. */
class Updater(private val context: Context, private val http: OkHttpClient) {

    sealed interface Check {
        data object UpToDate : Check
        data object Disabled : Check
        data class Available(val release: GithubRelease, val asset: GithubAsset) : Check
        data class Failed(val reason: String) : Check
    }

    suspend fun check(): Check = withContext(Dispatchers.IO) {
        if (BuildConfig.STORE_BUILD) return@withContext Check.Disabled
        val url = Updates.latestUrl(BuildConfig.UPDATE_REPO) ?: return@withContext Check.Disabled
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                if (r.code == 404) return@runCatching Check.UpToDate // no stable release yet
                if (!r.isSuccessful) return@runCatching Check.Failed("HTTP ${r.code}")
                val rel = Updates.parse(r.body.string())
                val abis = Build.SUPPORTED_ABIS.toList()
                Updates.pickAsset(rel, BuildConfig.VERSION_NAME, abis)?.let { Check.Available(rel, it) } ?: Check.UpToDate
            }
        }.getOrElse { Check.Failed(it.message ?: "error") }
    }

    /** Resumable download into the cache, then validation before the installer sees it. */
    suspend fun download(asset: GithubAsset, onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val have = if (file.exists()) file.length() else 0L
            val req = Request.Builder().url(asset.browser_download_url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val append = r.code == 206
                val total = (if (append) have else 0L) + r.body.contentLength()
                RandomAccessFile(file, "rw").use { raf ->
                    if (!append) raf.setLength(0)
                    raf.seek(if (append) have else 0L)
                    val buf = ByteArray(64 * 1024)
                    var done = if (append) have else 0L
                    r.body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            validate(file).getOrThrow()
            file
        }
    }

    /** Same package, newer version code, same signing certificate as the installed app. */
    fun validate(apk: File): Result<Unit> = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("not an APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "package name differs" }
        require(versionCode(archive) > versionCode(installed)) { "not newer" }
        require(certs(archive) == certs(installed)) { "signing certificate differs" }
    }

    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.toList().orEmpty() else @Suppress("DEPRECATION") p.signatures?.toList().orEmpty()
        return sigs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= 26) context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
