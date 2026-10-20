package com.ahmad.snapdown

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Information about a link, shown before the user picks a quality. */
data class MediaInfo(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val durationSec: Int,
    val site: String,
    val heights: List<Int>,
)

/** What the user chose to download. height == null means "best available". */
data class Choice(val label: String, val audioOnly: Boolean, val height: Int?)

enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELED }

data class Task(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val label: String,
    val audioOnly: Boolean,
    val height: Int?,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val error: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val sizeBytes: Long = 0,
    val time: Long = System.currentTimeMillis(),
) {
    val active get() = status == Status.QUEUED || status == Status.RUNNING
}

object Engine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private lateinit var app: Context

    private val readyDeferred = CompletableDeferred<Unit>()
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _engineVersion = MutableStateFlow<String?>(null)
    val engineVersion: StateFlow<String?> = _engineVersion.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        _tasks.value = loadHistory()
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _engineVersion.value = YoutubeDL.getInstance().version(app)
                _ready.value = true
                readyDeferred.complete(Unit)
                autoUpdate()
            } catch (t: Throwable) {
                _initError.value = t.message ?: t.toString()
                readyDeferred.completeExceptionally(t)
            }
        }
    }

    // ---------- yt-dlp self update (sites change often) ----------

    private fun prefs() = app.getSharedPreferences("snapdown", Context.MODE_PRIVATE)

    private fun autoUpdate() {
        val last = prefs().getLong("last_update", 0)
        if (System.currentTimeMillis() - last < 24 * 3600 * 1000L) return
        scope.launch { runCatching { updateEngine() } }
    }

    /** Returns a short Arabic status message. */
    suspend fun updateEngine(): String = withContext(Dispatchers.IO) {
        readyDeferred.await()
        val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        prefs().edit().putLong("last_update", System.currentTimeMillis()).apply()
        _engineVersion.value = YoutubeDL.getInstance().version(app)
        if (status == YoutubeDL.UpdateStatus.DONE) "تم تحديث محرك التحميل" else "المحرك محدّث بالفعل"
    }

    // ---------- link analysis ----------

    suspend fun fetchInfo(url: String): MediaInfo = withContext(Dispatchers.IO) {
        readyDeferred.await()
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        cookiesFor(url)?.let { req.addOption("--cookies", it) }
        val info = YoutubeDL.getInstance().getInfo(req)
        val formats = info.formats ?: arrayListOf()
        val heights = formats
            .filter { (it.vcodec ?: "none") != "none" && it.height > 0 }
            .map { it.height }
            .distinct()
            .sortedDescending()
        MediaInfo(
            url = url,
            title = info.title ?: info.fulltitle ?: "بدون عنوان",
            thumbnail = info.thumbnail,
            uploader = info.uploader,
            durationSec = info.duration,
            site = info.extractorKey ?: hostOf(url),
            heights = heights,
        )
    }

    fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host?.removePrefix("www.")?.removePrefix("m.") }.getOrNull() ?: ""

    fun isYouTube(url: String): Boolean {
        val h = hostOf(url)
        return h.endsWith("youtube.com") || h == "youtu.be" || h.endsWith("youtube-nocookie.com")
    }

    /** Playlist id from a YouTube link (watch?v=..&list=.. or playlist?list=..), ignoring auto mixes. */
    fun playlistId(url: String): String? {
        if (!isYouTube(url)) return null
        val id = runCatching { Uri.parse(url).getQueryParameter("list") }.getOrNull() ?: return null
        return id.takeIf { it.isNotBlank() }
    }

    /** True when the link is only a playlist/channel page (no single video in it). */
    fun isPurePlaylist(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val path = u.path ?: ""
        if (isYouTube(url)) {
            if (u.getQueryParameter("v") != null || path.startsWith("/shorts/") || hostOf(url) == "youtu.be") return false
            return u.getQueryParameter("list") != null || path.startsWith("/@") || path.startsWith("/channel/") ||
                path.startsWith("/c/") || path.startsWith("/user/")
        }
        return false
    }

    fun playlistUrl(url: String): String =
        playlistId(url)?.let { "https://www.youtube.com/playlist?list=$it" } ?: url

    data class Entry(val url: String, val title: String, val thumbnail: String?)

    /** Reads all items of a playlist/channel quickly (without fetching every video). */
    suspend fun fetchPlaylist(url: String): Pair<String, List<Entry>> = withContext(Dispatchers.IO) {
        readyDeferred.await()
        val req = YoutubeDLRequest(url)
        req.addOption("--flat-playlist")
        req.addOption("-J")
        cookiesFor(url)?.let { req.addOption("--cookies", it) }
        val root = JSONObject(YoutubeDL.getInstance().execute(req).out)
        val list = mutableListOf<Entry>()
        fun collect(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                // channels contain nested tabs (Videos / Shorts) that are playlists themselves
                if (e.optString("_type") == "playlist" && e.has("entries")) { collect(e.optJSONArray("entries")); continue }
                val title = e.optString("title")
                if (title == "[Private video]" || title == "[Deleted video]") continue
                val id = e.optString("id")
                var u = e.optString("url").ifEmpty { e.optString("webpage_url") }
                if (!u.startsWith("http") && id.isNotEmpty() && isYouTube(url)) u = "https://www.youtube.com/watch?v=$id"
                if (!u.startsWith("http")) continue
                val thumbs = e.optJSONArray("thumbnails")
                val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.ifEmpty { null }
                    ?: if (isYouTube(u) && id.isNotEmpty()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null
                list += Entry(u, title.ifEmpty { id }, thumb)
            }
        }
        collect(root.optJSONArray("entries"))
        (root.optString("title").ifEmpty { "قائمة تشغيل" }) to list
    }

    /** Downloads every item of a playlist. Returns the number of queued videos. */
    suspend fun enqueuePlaylist(url: String, choice: Choice): Int {
        val (_, entries) = fetchPlaylist(url)
        entries.forEach { enqueue(it.url, it.title, it.thumbnail, choice) }
        return entries.size
    }

    /**
     * Exports the in-app browser's cookies for this site to a Netscape cookie file,
     * so private / login-only posts (Instagram, Facebook, X…) can be downloaded.
     * YouTube is skipped on purpose: it works without login and cookies can trigger blocks.
     */
    fun cookiesFor(url: String): String? = runCatching {
        if (isYouTube(url)) return null
        val host = Uri.parse(url).host ?: return null
        val parts = host.split('.')
        val domain = if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        val raw = android.webkit.CookieManager.getInstance().getCookie("https://$domain")
            ?: android.webkit.CookieManager.getInstance().getCookie(url)
            ?: return null
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        raw.split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
            val name = it.substringBefore('=')
            val value = it.substringAfter('=')
            sb.append(".$domain\tTRUE\t/\tTRUE\t2147483647\t$name\t$value\n")
        }
        val f = File(app.cacheDir, "cookies-$domain.txt")
        f.writeText(sb.toString())
        f.absolutePath
    }.getOrNull()

    // ---------- downloads ----------

    fun enqueue(url: String, title: String, thumbnail: String?, choice: Choice) {
        val task = Task(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            thumbnail = thumbnail,
            label = choice.label,
            audioOnly = choice.audioOnly,
            height = choice.height,
        )
        _tasks.update { listOf(task) + it }
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        scope.launch { slots.withPermit { run(task.id) } }
    }

    fun retry(id: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        _tasks.update { list -> list.filterNot { it.id == id } }
        enqueue(t.url, t.title, t.thumbnail, Choice(t.label, t.audioOnly, t.height))
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        set(id) { if (it.active) it.copy(status = Status.CANCELED) else it }
        saveHistory()
    }

    fun remove(id: String, deleteFile: Boolean) {
        val t = _tasks.value.find { it.id == id } ?: return
        if (t.active) cancel(id)
        if (deleteFile && t.uri != null) {
            runCatching { app.contentResolver.delete(Uri.parse(t.uri), null, null) }
        }
        _tasks.update { list -> list.filterNot { it.id == id } }
        saveHistory()
    }

    private fun set(id: String, f: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private suspend fun run(id: String) {
        val task = _tasks.value.find { it.id == id } ?: return
        if (task.status != Status.QUEUED) return
        set(id) { it.copy(status = Status.RUNNING) }
        val dir = File(app.cacheDir, "dl/$id").apply { deleteRecursively(); mkdirs() }
        try {
            readyDeferred.await()
            val req = YoutubeDLRequest(task.url)
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            cookiesFor(task.url)?.let { req.addOption("--cookies", it) }
            req.addOption("-o", dir.absolutePath + "/%(title).80B [%(id)s].%(ext)s")
            if (task.audioOnly) {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
                req.addOption("--embed-thumbnail")
            } else {
                req.addOption("-f", "bv*+ba/b")
                val sort = if (task.height != null) "res:${task.height},ext:mp4:m4a" else "res,ext:mp4:m4a"
                req.addOption("-S", sort)
                req.addOption("--merge-output-format", "mp4")
            }
            YoutubeDL.getInstance().execute(req, id) { progress, eta, _ ->
                set(id) { it.copy(progress = progress.coerceIn(0f, 100f), etaSec = eta) }
            }
            val file = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("لم يتم العثور على الملف بعد التحميل")
            val (uri, mime) = publish(file, task.audioOnly)
            set(id) {
                it.copy(status = Status.DONE, progress = 100f, uri = uri.toString(), mime = mime, sizeBytes = file.length())
            }
        } catch (e: YoutubeDL.CanceledException) {
            set(id) { it.copy(status = Status.CANCELED) }
        } catch (t: Throwable) {
            if (_tasks.value.find { it.id == id }?.status != Status.CANCELED) {
                set(id) { it.copy(status = Status.FAILED, error = friendlyError(t)) }
            }
        } finally {
            dir.deleteRecursively()
            saveHistory()
        }
    }

    private fun friendlyError(t: Throwable): String {
        val msg = t.message ?: t.toString()
        return when {
            msg.contains("login", true) || msg.contains("cookies", true) ->
                "المحتوى خاص أو يتطلب تسجيل الدخول"
            msg.contains("Unsupported URL", true) -> "هذا الرابط غير مدعوم"
            msg.contains("Unable to resolve host", true) || msg.contains("network", true) ->
                "تحقق من اتصال الإنترنت"
            else -> msg.lineSequence().lastOrNull { it.contains("ERROR") }?.take(200) ?: msg.take(200)
        }
    }

    /** Copies the finished file into public storage (Movies/Music/Pictures › SnapDown). */
    private fun publish(file: File, audio: Boolean): Pair<Uri, String> {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (audio) "audio/mpeg" else "video/mp4"
        val kind = when {
            mime.startsWith("audio") -> Environment.DIRECTORY_MUSIC
            mime.startsWith("image") -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_MOVIES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when (kind) {
                Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$kind/SnapDown")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("تعذر حفظ الملف")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri to mime
        } else {
            @Suppress("DEPRECATION")
            val outDir = File(Environment.getExternalStoragePublicDirectory(kind), "SnapDown").apply { mkdirs() }
            var out = File(outDir, file.name)
            var n = 1
            while (out.exists()) out = File(outDir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
            file.copyTo(out)
            MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), arrayOf(mime), null)
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", out)
            return uri to mime
        }
    }

    // ---------- history persistence ----------

    private fun saveHistory() {
        val arr = JSONArray()
        _tasks.value.filter { !it.active }.take(300).forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("url", t.url); put("title", t.title)
                put("thumb", t.thumbnail ?: ""); put("label", t.label)
                put("audio", t.audioOnly); put("height", t.height ?: -1)
                put("status", t.status.name); put("error", t.error ?: "")
                put("uri", t.uri ?: ""); put("mime", t.mime ?: "")
                put("size", t.sizeBytes); put("time", t.time)
            })
        }
        prefs().edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory(): List<Task> = runCatching {
        val arr = JSONArray(prefs().getString("history", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(
                id = o.getString("id"),
                url = o.getString("url"),
                title = o.getString("title"),
                thumbnail = o.optString("thumb").ifEmpty { null },
                label = o.optString("label"),
                audioOnly = o.optBoolean("audio"),
                height = o.optInt("height", -1).takeIf { it > 0 },
                status = Status.valueOf(o.getString("status")),
                progress = 100f,
                error = o.optString("error").ifEmpty { null },
                uri = o.optString("uri").ifEmpty { null },
                mime = o.optString("mime").ifEmpty { null },
                sizeBytes = o.optLong("size"),
                time = o.optLong("time"),
            )
        }
    }.getOrDefault(emptyList())
}
