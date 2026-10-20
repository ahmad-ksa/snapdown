package com.ahmad.snapdown.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ahmad.snapdown.Choice
import com.ahmad.snapdown.Engine
import com.ahmad.snapdown.MainActivity
import com.ahmad.snapdown.MediaInfo
import com.ahmad.snapdown.Status
import com.ahmad.snapdown.Task
import kotlinx.coroutines.launch

private val Red = Color(0xFFD32F2F)

private val Light = lightColorScheme(primary = Red, secondary = Color(0xFF455A64), primaryContainer = Color(0xFFFFDAD6))
private val Dark = darkColorScheme(primary = Color(0xFFFF8A80), secondary = Color(0xFFB0BEC5))

private sealed interface HomeState {
    data object Idle : HomeState
    data class Loading(val url: String) : HomeState
    data class Loaded(val info: MediaInfo) : HomeState
    data class Error(val url: String, val message: String) : HomeState
    data class Playlist(val url: String) : HomeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapDownApp(activity: MainActivity) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        val tasks by Engine.tasks.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        var showSettings by remember { mutableStateOf(false) }
        val activeCount = tasks.count { it.active }
        var tab by activity.tab

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SnapDown", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Red, titleContentColor = Color.White, actionIconContentColor = Color.White
                    ),
                    actions = {
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "الإعدادات") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_BROWSER, onClick = { tab = MainActivity.TAB_BROWSER },
                        icon = { Icon(Icons.Default.SmartDisplay, null) }, label = { Text("يوتيوب") }
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_LINK, onClick = { tab = MainActivity.TAB_LINK },
                        icon = { Icon(Icons.Default.Link, null) }, label = { Text("رابط") }
                    )
                    NavigationBarItem(
                        selected = tab == MainActivity.TAB_DOWNLOADS, onClick = { tab = MainActivity.TAB_DOWNLOADS },
                        icon = {
                            BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
                                Icon(Icons.Default.DownloadDone, null)
                            }
                        },
                        label = { Text("التنزيلات") }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                val toDownloads = { tab = MainActivity.TAB_DOWNLOADS }
                when (tab) {
                    MainActivity.TAB_BROWSER -> BrowserScreen(activity, activity.browser, snackbar, toDownloads)
                    MainActivity.TAB_LINK -> HomeScreen(activity, snackbar, toDownloads)
                    else -> DownloadsScreen(tasks)
                }
            }
        }

        if (showSettings) SettingsDialog { showSettings = false }
    }
}

// ------------------------------------------------------------------ Home

private val sites = listOf(
    "YouTube" to "https://m.youtube.com",
    "TikTok" to "https://www.tiktok.com",
    "Instagram" to "https://www.instagram.com",
    "Facebook" to "https://m.facebook.com",
    "X" to "https://x.com",
    "Snapchat" to "https://www.snapchat.com/spotlight",
    "Pinterest" to "https://www.pinterest.com",
    "Reddit" to "https://www.reddit.com",
    "SoundCloud" to "https://soundcloud.com",
    "Vimeo" to "https://vimeo.com",
    "Dailymotion" to "https://www.dailymotion.com",
    "Twitch" to "https://www.twitch.tv",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(activity: MainActivity, snackbar: SnackbarHostState, onOpenDownloads: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<HomeState>(HomeState.Idle) }
    val ready by Engine.ready.collectAsState()
    val initError by Engine.initError.collectAsState()
    val shared by activity.sharedUrl
    val clip by activity.clipUrl

    fun analyze(link: String) {
        val l = MainActivity.extractUrl(link) ?: run {
            state = HomeState.Error(link, "الصق رابطًا صحيحًا يبدأ بـ https://"); return
        }
        url = l
        if (Engine.isPurePlaylist(l)) { state = HomeState.Playlist(l); return }
        state = HomeState.Loading(l)
        scope.launch {
            state = try {
                HomeState.Loaded(Engine.fetchInfo(l))
            } catch (t: Throwable) {
                HomeState.Error(l, t.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(250) ?: "تعذر قراءة الرابط")
            }
        }
    }

    fun start(title: String, thumb: String?, choice: Choice) {
        Engine.enqueue(url, title, thumb, choice)
        state = HomeState.Idle
        url = ""
        scope.launch {
            val r = snackbar.showSnackbar("بدأ التحميل: ${choice.label}", actionLabel = "عرض")
            if (r == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    // A link shared from another app: analyze immediately.
    LaunchedEffect(shared) {
        shared?.let { analyze(it); activity.sharedUrl.value = null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("الصق رابط الفيديو هنا") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { analyze(url) }),
            trailingIcon = {
                if (url.isNotEmpty()) IconButton(onClick = { url = ""; state = HomeState.Idle }) {
                    Icon(Icons.Default.Clear, "مسح")
                } else IconButton(onClick = { clip?.let { url = it } }) {
                    Icon(Icons.Default.ContentPaste, "لصق")
                }
            }
        )

        Button(
            onClick = { analyze(url) },
            enabled = url.isNotBlank() && state !is HomeState.Loading,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("تحليل الرابط")
        }

        if (clip != null && clip != url && state !is HomeState.Loading) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("رابط في الحافظة", style = MaterialTheme.typography.labelMedium)
                        Text(clip!!, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { analyze(clip!!) }) { Text("تحميل") }
                }
            }
        }

        if (!ready) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initError == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("جاري تجهيز محرك التحميل (أول تشغيل فقط)…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("تعذر تشغيل المحرك: $initError", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        when (val s = state) {
            HomeState.Idle -> SitesGrid { u -> activity.browser.open(u); activity.tab.intValue = MainActivity.TAB_BROWSER }
            is HomeState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("جاري قراءة الرابط…")
                }
            }
            is HomeState.Error -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تعذر قراءة الرابط", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                    Text("يمكنك محاولة التحميل مباشرة:", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { start(Engine.hostOf(s.url), null, Choice("أفضل جودة", false, null)) }) {
                            Text("فيديو")
                        }
                        OutlinedButton(onClick = { start(Engine.hostOf(s.url), null, Choice("MP3", true, null)) }) {
                            Text("صوت MP3")
                        }
                    }
                }
            }
            is HomeState.Loaded -> {
                InfoCard(s.info) { choice -> start(s.info.title, s.info.thumbnail, choice) }
                if (Engine.playlistId(s.info.url) != null) PlaylistCard(s.info.url, snackbar, onOpenDownloads) { state = HomeState.Idle; url = "" }
            }
            is HomeState.Playlist -> PlaylistCard(s.url, snackbar, onOpenDownloads) { state = HomeState.Idle; url = "" }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SitesGrid(open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("المواقع المدعومة (أكثر من 1000 موقع)", style = MaterialTheme.typography.titleSmall)
        Text(
            "انسخ رابط أي منشور ثم الصقه هنا، أو اضغط «مشاركة» داخل أي تطبيق واختر SnapDown.",
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sites.forEach { (name, link) -> AssistChip(onClick = { open(link) }, label = { Text(name) }) }
        }
    }
}

private val standardHeights = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoCard(info: MediaInfo, onChoose: (Choice) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        if (info.thumbnail != null) {
            AsyncImage(
                model = info.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(info.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(info.site, info.uploader, info.durationSec.takeIf { it > 0 }?.let { fmtDuration(it) })
            Text(meta.joinToString(" • "), style = MaterialTheme.typography.bodySmall)

            Text("فيديو", style = MaterialTheme.typography.titleSmall)
            // Map the real heights to familiar quality steps (e.g. 1088 -> 1080p).
            val qualities = info.heights.mapNotNull { h -> standardHeights.firstOrNull { h >= it * 0.9 } }.distinct()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = { onChoose(Choice("أفضل جودة", false, null)) }) {
                    Icon(Icons.Default.Movie, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("أفضل جودة")
                }
                qualities.forEach { q ->
                    OutlinedButton(onClick = { onChoose(Choice("${q}p", false, q)) }) { Text("${q}p") }
                }
            }
            Text("صوت", style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(onClick = { onChoose(Choice("MP3", true, null)) }) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("MP3 بأعلى جودة")
            }
        }
    }
}

@Composable
private fun PlaylistCard(link: String, snackbar: SnackbarHostState, onOpenDownloads: () -> Unit, onStarted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlaylistPlay, null); Spacer(Modifier.width(6.dp))
                Text("تحميل قائمة التشغيل كاملة", fontWeight = FontWeight.Bold)
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("جاري قراءة القائمة…")
            } else QualityButtons(enabled = true) { choice ->
                busy = true
                scope.launch {
                    val msg = try {
                        val n = Engine.enqueuePlaylist(Engine.playlistUrl(link), choice)
                        "أُضيف $n فيديو للتحميل (${choice.label})"
                    } catch (t: Throwable) {
                        "تعذر قراءة القائمة: ${t.message?.take(120)}"
                    }
                    busy = false
                    onStarted()
                    if (snackbar.showSnackbar(msg, actionLabel = "عرض") == SnackbarResult.ActionPerformed) onOpenDownloads()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Downloads

@Composable
private fun DownloadsScreen(tasks: List<Task>) {
    var confirmDelete by remember { mutableStateOf<Task?>(null) }
    if (tasks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("لا توجد تنزيلات بعد", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(tasks, key = { it.id }) { t -> TaskRow(t, onDelete = { confirmDelete = t }) }
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف") },
            text = { Text(if (t.status == Status.DONE) "هل تريد حذف الملف من الجهاز أيضًا؟" else "إزالة من القائمة؟") },
            confirmButton = {
                TextButton(onClick = { Engine.remove(t.id, deleteFile = true); confirmDelete = null }) {
                    Text(if (t.status == Status.DONE) "حذف الملف" else "إزالة")
                }
            },
            dismissButton = {
                if (t.status == Status.DONE) TextButton(onClick = { Engine.remove(t.id, deleteFile = false); confirmDelete = null }) {
                    Text("من القائمة فقط")
                } else TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun TaskRow(t: Task, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (t.thumbnail != null) AsyncImage(t.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(if (t.audioOnly) Icons.Default.MusicNote else Icons.Default.Movie, null)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                val statusText = when (t.status) {
                    Status.QUEUED -> "في الانتظار • ${t.label}"
                    Status.RUNNING -> "${t.progress.toInt()}٪ • ${t.label}" + (if (t.etaSec > 0) " • متبقٍ ${fmtDuration(t.etaSec.toInt())}" else "")
                    Status.DONE -> "${t.label} • ${fmtSize(t.sizeBytes)}"
                    Status.FAILED -> "فشل: ${t.error ?: ""}"
                    Status.CANCELED -> "أُلغي"
                }
                Text(
                    statusText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (t.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (t.active) {
                    Spacer(Modifier.height(6.dp))
                    if (t.status == Status.RUNNING && t.progress > 0f) LinearProgressIndicator(progress = { t.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            when (t.status) {
                Status.QUEUED, Status.RUNNING ->
                    IconButton(onClick = { Engine.cancel(t.id) }) { Icon(Icons.Default.Close, "إلغاء") }
                Status.DONE -> Row {
                    IconButton(onClick = { open(ctx, t) }) { Icon(Icons.Default.PlayArrow, "تشغيل") }
                    IconButton(onClick = { share(ctx, t) }) { Icon(Icons.Default.Share, "مشاركة") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "حذف") }
                }
                Status.FAILED, Status.CANCELED -> Row {
                    IconButton(onClick = { Engine.retry(t.id) }) { Icon(Icons.Default.Refresh, "إعادة") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "حذف") }
                }
            }
        }
    }
}

private fun open(ctx: android.content.Context, t: Task) {
    val uri = t.uri ?: return
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), t.mime ?: "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }.onFailure { Toast.makeText(ctx, "لا يوجد تطبيق لتشغيل الملف أو أنه حُذف", Toast.LENGTH_SHORT).show() }
}

private fun share(ctx: android.content.Context, t: Task) {
    val uri = t.uri ?: return
    val send = Intent(Intent.ACTION_SEND).setType(t.mime ?: "video/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "مشاركة"))
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Engine.engineVersion.collectAsState()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الإعدادات") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("إصدار محرك التحميل (yt-dlp): ${version ?: "…"}")
                Text(
                    "المواقع تغيّر أنظمتها باستمرار. إذا توقف التحميل من موقع ما، حدّث المحرك. يتحدث تلقائيًا مرة كل يوم.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("الملفات تُحفظ في: Movies/SnapDown و Music/SnapDown", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val msg = runCatching { Engine.updateEngine() }.getOrElse { "فشل التحديث: ${it.message}" }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        busy = false
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("تحديث المحرك الآن")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

// ------------------------------------------------------------------ helpers

private fun fmtDuration(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
