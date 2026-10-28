package ani.dantotsu.others.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.defaultHeaders
import ani.dantotsu.parsers.StreamHeaders
import ani.dantotsu.util.Logger
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable

/**
 * "Open in WebView" for an extension source: loads the source's page with its headers and
 * User-Agent in a WebView that shares the app's cookie store (the one the source's OkHttp
 * client reads), so a captcha or Cloudflare challenge solved here clears the source's
 * requests too. Used when a source fails with a message asking for it (AnimePahe/Kwik,
 * Comix, MangaFire, AllManga, MangaDot, ...).
 */
class SourceWebViewActivity : AppCompatActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: url

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 24, 24))
        }
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_round_close_24)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.close)
            setOnClickListener { finish() }
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            setPadding(16, 0, 16, 0)
        }
        bar.addView(close, LinearLayout.LayoutParams(144, 144))
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val web = WebView(this)
        webView = web
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(FrameLayout(this).apply { addView(root) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        val ua = StreamHeaders.get(headers, "User-Agent") ?: defaultHeaders["User-Agent"]
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            if (ua != null) userAgentString = ua
        }
        val cookies = runCatching { Injekt.get<NetworkHelper>().cookieJar.manager }.getOrNull()
            ?: CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                cookies.flush()
            }
        }
        // the WebView sets its own User-Agent; the rest (Referer, Origin, ...) goes with the load
        web.loadUrl(url, StreamHeaders.without(headers, "User-Agent"))
    }

    override fun onDestroy() {
        runCatching { CookieManager.getInstance().flush() }
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, url: String, headers: Map<String, String>, title: String?) {
            val intent = Intent(context, SourceWebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_HEADERS, HashMap(headers) as Serializable)
                putExtra(EXTRA_TITLE, title)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure { Logger.log(it) }
        }

        /**
         * Shows an extension's own error message (they often say what to do, e.g. "open in
         * WebView to solve the captcha") with an "Open in WebView" action when a page url is
         * known. Safe to call from any thread.
         */
        fun showSourceError(
            sourceName: String,
            message: String?,
            url: String?,
            headers: Map<String, String>,
        ) {
            val text = "$sourceName: ${message?.takeIf { it.isNotBlank() } ?: "failed to load"}"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val activity = ani.dantotsu.currActivity()
                    ?.takeIf { !it.isFinishing && !it.isDestroyed }
                if (activity == null) {
                    Logger.log(text)
                    return@post
                }
                runCatching {
                    val snackbar = Snackbar.make(
                        activity.window.decorView.findViewById(android.R.id.content),
                        text,
                        Snackbar.LENGTH_LONG
                    )
                    if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
                        snackbar.setAction(R.string.open_in_webview) {
                            open(activity, url, headers, sourceName)
                        }
                    }
                    snackbar.show()
                }.onFailure { Logger.log(it) }
            }
        }
    }
}
