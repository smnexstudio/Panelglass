package com.smnexstudio.panelglass.core.data.update

import android.content.Context
import android.content.pm.ApplicationInfo
import com.smnexstudio.panelglass.core.data.BuildConfig
import com.smnexstudio.panelglass.core.data.download.Downloads
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A published release: its version, the APK and the APK's `.sha256` file on GitHub. */
data class Release(val version: String, val apkName: String, val apkUrl: String, val apkBytes: Long, val shaUrl: String)

/** What the update flow is showing. */
sealed interface UpdateState {
    data object Idle : UpdateState
    /** A check the user asked for (Settings); an automatic one shows nothing until it finds an update. */
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val release: Release, val current: String) : UpdateState
    data class Downloading(val release: Release, val bytes: Long, val total: Long) : UpdateState
    /** Downloaded and verified; Android's installer takes it from here. */
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val release: Release?) : UpdateState
}

/** GitHub's release JSON, versions and checksum files, without Android: unit-tested on the JVM. */
object ReleaseInfo {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The release in GitHub's `releases/latest` JSON, or null when it is a draft or pre-release, or has no APK and
     * checksum pair. Asset URLs must be https on [PREFIX]: the APK is never fetched from anywhere else.
     */
    fun parse(body: String): Release? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (o.bool("draft") || o.bool("prerelease")) return null
        val version = o.str("tag_name")?.removePrefix("v")?.takeIf { parseVersion(it) != null } ?: return null
        val assets = o["assets"]?.runCatching { jsonArray.map { it.jsonObject } }?.getOrNull() ?: return null
        val apkName = "panelglass-$version.apk"
        val apk = assets.firstOrNull { it.str("name") == apkName } ?: return null
        val sha = assets.firstOrNull { it.str("name") == "$apkName.sha256" } ?: return null
        val apkUrl = apk.str("browser_download_url")?.takeIf(::isReleaseAsset) ?: return null
        val shaUrl = sha.str("browser_download_url")?.takeIf(::isReleaseAsset) ?: return null
        return Release(version, apkName, apkUrl, apk["size"]?.jsonPrimitive?.longOrNull ?: 0L, shaUrl)
    }

    /**
     * `1.2.3` (an optional leading `v`) as numbers; null for anything else, a `-suffix` included: the version also
     * names the downloaded file, so only digits and dots are ever accepted.
     */
    fun parseVersion(v: String): List<Int>? {
        val s = v.trim().removePrefix("v")
        if (!VERSION.matches(s)) return null
        return s.split('.').map { it.toInt() }
    }

    /**
     * Whether a file in the updates folder can go: anything but a finished APK of a version newer than [current]
     * (an installed or older release is spent, a `.part` is an interrupted download).
     */
    fun isSpent(fileName: String, current: String): Boolean {
        val v = APK_FILE.matchEntire(fileName)?.groupValues?.get(1) ?: return true
        return !isNewer(v, current)
    }

    /**
     * Whether [candidate] is a later version than [current]. An unreadable version is never newer, and nothing is
     * newer than an unreadable [current]: an update is offered only when both versions are known.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Whether [body] is a release as GitHub sends one (a JSON object with a `tag_name`), whether or not it is one the
     * app offers. Anything else (an HTML error page, a cut reply) is a failed check, never "up to date".
     */
    fun isReleaseReply(body: String): Boolean =
        runCatching { json.parseToJsonElement(body).jsonObject.str("tag_name") != null }.getOrDefault(false)

    /** The hash for [fileName] in a `sha256sum` line (`<64 hex>  <name>`), or null. */
    fun sha256For(text: String, fileName: String): String? = text.lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName && it[0].matches(HEX64) }
        ?.get(0)?.lowercase()

    /**
     * Whether [url] is a file of this repository's releases: https on [PREFIX] as resolved, not only as written. A path
     * that leaves the prefix once its `.` / `..` segments are resolved (written or percent-encoded), any other escape
     * (`%2F`, a backslash), a query, a fragment, a user or a port is refused. GitHub's asset URLs have none of these.
     */
    internal fun isReleaseAsset(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.toString() == url && url.startsWith(PREFIX) && '%' !in parsed.encodedPath &&
            parsed.query == null && parsed.fragment == null && parsed.username.isEmpty()
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true

    /** `owner/name` of the repository updates come from, set at build time (`-PupdateRepo`, see core/data's build file). */
    val REPO: String = BuildConfig.UPDATE_REPO
    val PREFIX = "https://github.com/$REPO/releases/download/"
    val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private val HEX64 = Regex("[0-9a-fA-F]{64}")
    private val VERSION = Regex("""\d{1,4}(\.\d{1,4}){0,3}""")
    private val APK_FILE = Regex("""panelglass-(\d{1,4}(?:\.\d{1,4}){0,3})\.apk""")
}

/**
 * In-app updates from GitHub Releases, the only place Panelglass is published (README › Install). A check asks
 * GitHub for the latest release — nothing about the user is sent. An update is offered once a day at most ("Later"
 * puts it off until the next day's check); Settings checks on demand. The APK comes through Android's DownloadManager, is
 * checked against the release's `.sha256`, and goes to Android's installer, which also refuses an APK not signed
 * with the installed app's key. Debuggable builds (`.debug`, their own app) never check by themselves.
 */
@Singleton
class AppUpdates internal constructor(
    private val downloads: Downloads,
    private val settings: SettingsRepository,
    /** Built lazily: the first check, not app start, pays for it. Tests route it to a local server. */
    httpClient: () -> OkHttpClient,
    private val installedVersion: () -> String,
    private val debuggable: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        downloads: SystemDownloads,
        settings: SettingsRepository,
    ) : this(
        downloads, settings,
        httpClient = { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(com.smnexstudio.panelglass.core.data.di.HttpsOnly())
            .addNetworkInterceptor(com.smnexstudio.panelglass.core.data.di.HttpsOnly()).build() },
        installedVersion = { kotlin.runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() },
        debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy(httpClient)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null

    val currentVersion: String get() = installedVersion()

    /** Waits for the running check or download (tests). */
    internal suspend fun idle() { job?.join() }

    /**
     * At app start: first the downloaded APKs already installed (or older) are deleted, ~280 MB each; then, unless the
     * user turned it off (Settings › About) or this is a debug build, GitHub is asked at most once a day. Shows
     * something only for a new version.
     */
    fun checkOnStart() {
        if (job?.isActive == true) return
        job = scope.launch {
            clearSpent()
            if (debuggable || !settings.current().autoUpdateCheck) return@launch
            val at = now()
            if (at - settings.updateCheckedAt() < CHECK_EVERY_MS) return@launch
            val release = runCatching { latest() }.getOrNull() ?: return@launch
            settings.setUpdateCheckedAt(at)
            if (ReleaseInfo.isNewer(release.version, currentVersion)) {
                _state.value = UpdateState.Available(release, currentVersion)
            }
        }
    }

    /** Settings › Check for updates: says what it found. */
    fun checkNow() {
        job?.cancel()
        // A check asked for mid-download replaces it: the system download goes too, or it would run on unseen.
        if (_state.value is UpdateState.Downloading) downloads.cancel(KEY)
        _state.value = UpdateState.Checking
        job = scope.launch {
            val release = try { latest() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w(TAG, "Update check failed: ${e::class.simpleName}")
                _state.value = UpdateState.Failed(null); return@launch
            }
            settings.setUpdateCheckedAt(now())
            _state.value = if (release != null && ReleaseInfo.isNewer(release.version, currentVersion))
                UpdateState.Available(release, currentVersion) else UpdateState.UpToDate(currentVersion)
        }
    }

    /** "Later" or closing a message: offered again on the next day's check. */
    fun dismiss() { if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle }

    /** "Update": the APK through DownloadManager, then its SHA-256 checked against the release's. */
    fun download() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        job?.cancel()
        _state.value = UpdateState.Downloading(release, 0, release.apkBytes)
        job = scope.launch {
            try {
                val sha = ReleaseInfo.sha256For(get(release.shaUrl), release.apkName) ?: throw IOException("No checksum")
                val path = "$DIR/${release.apkName}"
                // An earlier, finished download of the same release is used as it is.
                downloads.finalFile(path)?.let { f -> downloads.adopt(f, sha) }?.let { _state.value = UpdateState.Ready(release, it); return@launch }
                clearOld(keep = release.apkName)
                downloads.start(KEY, release.apkUrl, path, "Panelglass ${release.version}", "Panelglass update")
                val apk = downloads.await(KEY, path, minBytes = 1_000_000, sha256 = sha) { p ->
                    _state.value = UpdateState.Downloading(release, p.bytes, if (p.total > 0) p.total else release.apkBytes)
                }
                _state.value = UpdateState.Ready(release, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Update download failed: ${e::class.simpleName}")
                downloads.cancel(KEY)
                _state.value = UpdateState.Failed(release)
            }
        }
    }

    /** Cancel during the download: the system download is removed too. */
    fun cancel() {
        job?.cancel(); job = null
        downloads.cancel(KEY)
        _state.value = UpdateState.Idle
    }

    /** The release on offer, or null when GitHub's latest is not one the app takes; a reply that is no release throws. */
    private suspend fun latest(): Release? {
        val body = get(ReleaseInfo.LATEST)
        if (!ReleaseInfo.isReleaseReply(body)) throw IOException("Unreadable reply")
        return ReleaseInfo.parse(body)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Panelglass")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("Empty reply")
        }
    }

    /** Deletes what [ReleaseInfo.isSpent] says can go, unless a download is running (its `.part` is in use). */
    private fun clearSpent() {
        if (downloads.isActive(KEY)) return
        val current = currentVersion
        downloads.finalFile(DIR)?.listFiles()?.filter { ReleaseInfo.isSpent(it.name, current) }?.forEach { it.delete() }
    }

    /** Only the release being installed stays: an APK is ~300 MB. */
    private fun clearOld(keep: String) {
        downloads.finalFile(DIR)?.listFiles()?.filter { it.name != keep && it.name != "$keep.part" }?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "AppUpdates"
        private const val KEY = "app-update"
        /** Under `getExternalFilesDir(null)`; the app's FileProvider shares it with the installer. */
        const val DIR = "updates"
        const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000
    }
}
