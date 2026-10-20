package com.ahmad.snapdown.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ahmad.snapdown.Choice
import com.ahmad.snapdown.Engine
import kotlinx.coroutines.launch

/** Observable state of the single, long-lived in-app browser. */
class BrowserState {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableStateOf(100)
    var canGoBack by mutableStateOf(false)
    var webView: WebView? = null
    private var pending: String? = null

    /** Opens a link in the in-app browser (works before the WebView exists too). */
    fun open(link: String) {
        webView?.loadUrl(link) ?: run { pending = link }
    }

    internal fun takePending(): String? = pending.also { pending = null }
}

val browserSites = listOf(
    "YouTube" to "https://m.youtube.com",
    "TikTok" to "https://www.tiktok.com",
    "Instagram" to "https://www.instagram.com",
    "Facebook" to "https://m.facebook.com",
    "X" to "https://x.com",
    "Snapchat" to "https://www.snapchat.com/spotlight",
    "Pinterest" to "https://www.pinterest.com",
    "Reddit" to "https://www.reddit.com",
    "Dailymotion" to "https://www.dailymotion.com",
    "Vimeo" to "https://vimeo.com",
)

private const val CODEC_FIX_JS = """
(function(){
  if (window.__sdCodecFix) return; window.__sdCodecFix = true;
  var bad = /hev1|hvc1|hevc|h265|bytevc1|dvh1|dvhe|av01/i;
  try { if (window.MediaSource) { var ist = MediaSource.isTypeSupported.bind(MediaSource);
    MediaSource.isTypeSupported = function(t){ return bad.test(t) ? false : ist(t); }; } } catch(e){}
  try { var cpt = HTMLMediaElement.prototype.canPlayType;
    HTMLMediaElement.prototype.canPlayType = function(t){ return bad.test(t) ? '' : cpt.call(this, t); }; } catch(e){}
  try { if (navigator.mediaCapabilities) { var di = navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
    navigator.mediaCapabilities.decodingInfo = function(c){
      var ct = (c && c.video && c.video.contentType) || '';
      if (bad.test(ct)) return Promise.resolve({supported:false, smooth:false, powerEfficient:false});
      return di(c); }; } } catch(e){}
})();
"""

/** Creates the WebView once; it survives tab switches so playback/page state is kept. */
@SuppressLint("SetJavaScriptEnabled")
fun BrowserState.obtainWebView(activity: Activity): WebView {
    webView?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
    val wv = WebView(activity)
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        setSupportZoom(false)
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

    // Many phones' WebView plays HEVC/AV1 audio but can't draw the frames (gray video on TikTok).
    // Hide those codecs so sites fall back to H.264, which renders everywhere.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(wv, CODEC_FIX_JS, setOf("*"))
    }

    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return true
            // block "open in app" redirects (intent://, vnd.youtube:// …) — stay in the browser
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            this@obtainWebView.url = url
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(CODEC_FIX_JS, null)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // YouTube/TikTok are single-page apps: this is the only callback fired on navigation
            this@obtainWebView.url = url
            canGoBack = view.canGoBack()
        }

        override fun onPageFinished(view: WebView, url: String) {
            canGoBack = view.canGoBack()
            CookieManager.getInstance().flush()
        }
    }

    var customView: View? = null
    var customCallback: WebChromeClient.CustomViewCallback? = null
    wv.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        override fun onReceivedTitle(view: WebView, t: String?) { title = t ?: "" }

        // no gray "play" placeholder while a video loads
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // full-screen video playback
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view; customCallback = callback
            val decor = activity.window.decorView as FrameLayout
            decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            WindowInsetsControllerCompat(activity.window, decor).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() {
            val decor = activity.window.decorView as FrameLayout
            customView?.let { decor.removeView(it) }
            customView = null
            customCallback?.onCustomViewHidden(); customCallback = null
            WindowInsetsControllerCompat(activity.window, decor).show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    wv.loadUrl(takePending() ?: browserSites.first().second)
    webView = wv
    return wv
}

/** Whether the page currently open is something worth downloading. */
fun isDownloadable(url: String): Boolean {
    val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val path = u.path ?: ""
    val host = Engine.hostOf(url)
    return when {
        Engine.isYouTube(url) -> u.getQueryParameter("v") != null || path.startsWith("/shorts/") ||
            u.getQueryParameter("list") != null || path.startsWith("/watch")
        host.endsWith("tiktok.com") -> path.contains("/video/") || path.contains("/photo/")
        host.endsWith("instagram.com") -> path.startsWith("/p/") || path.startsWith("/reel") || path.startsWith("/tv/") || path.startsWith("/stories/")
        host.endsWith("facebook.com") || host == "fb.watch" ->
            path.contains("/videos/") || path.contains("/reel") || path.startsWith("/watch") || path.contains("/share/")
        host == "x.com" || host.endsWith("twitter.com") -> path.contains("/status/")
        else -> path.length > 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(activity: Activity, state: BrowserState, snackbar: SnackbarHostState, onOpenDownloads: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sheetFor by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    BackHandler(enabled = state.canGoBack) { state.webView?.goBack() }

    fun notifyStarted(msg: String) {
        scope.launch {
            if (snackbar.showSnackbar(msg, actionLabel = "عرض") == SnackbarResult.ActionPerformed) onOpenDownloads()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { state.webView?.goBack() }, enabled = state.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    browserSites.forEach { (name, link) ->
                        FilterChip(
                            selected = baseDomain(state.url) == baseDomain(link),
                            onClick = { state.webView?.loadUrl(link) },
                            label = { Text(name) }
                        )
                    }
                }
                IconButton(onClick = { state.webView?.reload() }) { Icon(Icons.Default.Refresh, "تحديث") }
            }
            if (state.progress < 100) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            AndroidView(
                factory = { state.obtainWebView(activity) },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isDownloadable(state.url)) {
            ExtendedFloatingActionButton(
                onClick = { sheetFor = state.url },
                icon = { Icon(Icons.Default.Download, null) },
                text = { Text("تحميل") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding()
            )
        }
    }

    sheetFor?.let { pageUrl ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val isPlaylist = Engine.playlistId(pageUrl) != null || Engine.isPurePlaylist(pageUrl)
        val single = !Engine.isPurePlaylist(pageUrl)
        val title = state.title.removeSuffix(" - YouTube").ifBlank { Engine.hostOf(pageUrl) }

        fun downloadOne(choice: Choice) {
            Engine.enqueue(pageUrl, title, ytThumb(pageUrl), choice)
            sheetFor = null
            notifyStarted("بدأ التحميل: ${choice.label}")
        }

        fun downloadAll(choice: Choice) {
            busy = true
            scope.launch {
                val msg = try {
                    val n = Engine.enqueuePlaylist(Engine.playlistUrl(pageUrl), choice)
                    "أُضيف $n فيديو للتحميل (${choice.label})"
                } catch (t: Throwable) {
                    "تعذر قراءة القائمة: ${t.message?.take(120)}"
                }
                busy = false
                sheetFor = null
                notifyStarted(msg)
            }
        }

        ModalBottomSheet(onDismissRequest = { if (!busy) sheetFor = null }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (single) {
                    Text("هذا الفيديو", style = MaterialTheme.typography.titleSmall)
                    QualityButtons(enabled = !busy, onChoose = ::downloadOne)
                }
                if (isPlaylist) {
                    if (single) HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlaylistPlay, null)
                        Spacer(Modifier.width(6.dp))
                        Text("قائمة التشغيل كاملة", style = MaterialTheme.typography.titleSmall)
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("جاري قراءة القائمة…")
                    } else QualityButtons(enabled = true, onChoose = ::downloadAll)
                }
            }
        }
    }
}

private fun baseDomain(url: String): String = Engine.hostOf(url).split('.').takeLast(2).joinToString(".")

/** One-tap quality buttons used for single videos and whole playlists. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QualityButtons(enabled: Boolean, onChoose: (Choice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = enabled, onClick = { onChoose(Choice("أفضل جودة", false, null)) }) {
                Icon(Icons.Default.Movie, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("أفضل جودة")
            }
            FilledTonalButton(enabled = enabled, onClick = { onChoose(Choice("MP3", true, null)) }) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("MP3")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1080, 720, 480, 360).forEach { q ->
                OutlinedButton(enabled = enabled, onClick = { onChoose(Choice("${q}p", false, q)) }) { Text("$q") }
            }
        }
    }
}

fun ytThumb(url: String): String? {
    if (!Engine.isYouTube(url)) return null
    val u = Uri.parse(url)
    val id = u.getQueryParameter("v")
        ?: u.path?.removePrefix("/shorts/")?.takeIf { u.path?.startsWith("/shorts/") == true }?.substringBefore('/')
        ?: u.lastPathSegment?.takeIf { Engine.hostOf(url) == "youtu.be" }
        ?: return null
    return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}
