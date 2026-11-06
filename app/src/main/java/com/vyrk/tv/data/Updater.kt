package com.vyrk.tv.data

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
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
        val url = BuildConfig.UPDATE_CHECK_URL.ifBlank { null } ?: Updates.latestUrl(BuildConfig.UPDATE_REPO) ?: return@withContext Check.Disabled
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

    /**
     * Resumable download into the cache, then validation before the installer sees it. A dropped
     * connection is retried (up to three tries, resuming where the server allows it); a file that
     * fails validation is deleted, so it can't block the next try.
     */
    suspend fun download(asset: GithubAsset, onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                fetch(asset, file, onProgress)
                validate(file).onFailure { file.delete(); throw it }
                return@withContext Result.success(file)
            } catch (e: java.io.IOException) {
                last = e
                android.util.Log.w("VyrkUpdate", "download try $attempt failed", e)
                kotlinx.coroutines.delay(1_000L * attempt)
            } catch (e: Throwable) {
                return@withContext Result.failure(e)
            }
        }
        file.delete()
        Result.failure(last ?: java.io.IOException("download failed"))
    }

    private fun fetch(asset: GithubAsset, file: File, onProgress: (Float) -> Unit) {
        val have = if (file.exists()) file.length() else 0L
        val req = Request.Builder().url(asset.browser_download_url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 416 && have > 0) return // already complete; validation decides
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
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

    /**
     * Installs through a PackageInstaller session. On Android 12+ no confirmation is asked when
     * VYRK is the app's installer of record (true after it has installed one update itself);
     * otherwise Android shows its confirmation, which [UpdateStatusReceiver] opens. VYRK closes
     * while it is replaced. Falls back to the plain install screen if a session can't be used.
     */
    fun install(apk: File) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("vyrk.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, UpdateStatusReceiver::class.java).setAction(UpdateStatusReceiver.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
            Diagnostics.log("update", "install session committed")
            android.util.Log.i("VyrkUpdate", "install session committed")
        }.onFailure {
            Diagnostics.log("update", "session install failed (${it.message}); using the install screen")
            android.util.Log.i("VyrkUpdate", "session install failed: ${it.message}")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            @Suppress("DEPRECATION")
            val i = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    companion object {
        /** Automatic update already tried in this run of the app (it checks once per opening). */
        @Volatile var triedThisLaunch = false
        /** Messages from the installer for the UI (a failed update); null when there's nothing to show. */
        val events = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
}

/** Installer results: opens Android's confirmation when it needs one, and reports failures. */
class UpdateStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        android.util.Log.i("VyrkUpdate", "installer status $status")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Android doesn't let an app reopen itself from here (background activity starts are
            // blocked), so the toast before the install says VYRK closes for a moment.
            PackageInstaller.STATUS_SUCCESS -> Diagnostics.log("update", "installed")
            // Cancelled on the confirmation: nothing to report.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Diagnostics.log("update", "cancelled")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Diagnostics.log("update", "install failed: $msg")
                Updater.events.value = msg
            }
        }
    }

    companion object { const val ACTION = "com.vyrk.tv.UPDATE_STATUS" }
}
