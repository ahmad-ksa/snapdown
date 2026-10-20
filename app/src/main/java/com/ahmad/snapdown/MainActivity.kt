package com.ahmad.snapdown

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ahmad.snapdown.ui.BrowserState
import com.ahmad.snapdown.ui.SnapDownApp

class MainActivity : ComponentActivity() {

    /** A link that arrived via "Share" from another app. */
    val sharedUrl = mutableStateOf<String?>(null)

    /** A link currently in the clipboard (offered to the user). */
    val clipUrl = mutableStateOf<String?>(null)

    /** 0 = browser (YouTube…), 1 = paste link, 2 = downloads */
    val tab = mutableIntStateOf(0)

    val browser = BrowserState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        askPermissions()
        setContent { SnapDownApp(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let {
                sharedUrl.value = it
                tab.intValue = TAB_LINK
            }
        }
        if (intent.hasExtra(EXTRA_TAB)) tab.intValue = intent.getIntExtra(EXTRA_TAB, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Android only allows reading the clipboard while the app has focus.
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            clipUrl.value = extractUrl(text)
        }
    }

    private fun askPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT < 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_BROWSER = 0
        const val TAB_LINK = 1
        const val TAB_DOWNLOADS = 2
        private val urlRegex = Regex("""https?://[^\s"'<>]+""")
        fun extractUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')') }
    }
}
