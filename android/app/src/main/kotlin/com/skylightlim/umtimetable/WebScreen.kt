package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The shared frame of the app's web screens (Spectrum sign-in and pages, MAYA, TimeEdit): a title
 * with a close button, an optional status line and action button, a progress bar and the page.
 * Back steps through the page's history first.
 */
abstract class WebScreen : Activity() {
    protected lateinit var web: WebView
    protected lateinit var title: TextView
    protected lateinit var status: TextView
    protected lateinit var action: Button
    protected lateinit var progress: ProgressBar

    // On Android 16+ onBackPressed() is no longer called; step back through web history with a
    // callback that's registered only while there is history, so back leaves the screen otherwise.
    // Android 12 has no callback type (making one there crashes): it's made on first use, and
    // onBackPressed steps back there.
    private val historyBack by lazy { OnBackInvokedCallback { web.goBack() } }
    private var historyBackRegistered = false

    /** The page waiting for files from the picker (an assignment's upload). */
    private var fileChosen: ValueCallback<Array<Uri>>? = null

    /**
     * Builds the screen. [statusText] null leaves out the status line, [actionLabel] null the button.
     * [compact] is a one-line title bar, for pages the user only reads.
     */
    @SuppressLint("SetJavaScriptEnabled")
    protected fun setUp(titleText: String, closeLabel: String, statusText: String? = null, actionLabel: String? = null, compact: Boolean = false) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        title = TextView(this).apply {
            text = titleText
            textSize = if (compact) 16f else 18f
            if (compact) maxLines = 1
            setTextColor(getColor(R.color.w_text))
            paint.isFakeBoldText = true
        }
        val close = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = closeLabel
            setOnClickListener { finish() }
        }
        status = TextView(this).apply {
            text = statusText
            textSize = 13f
            setTextColor(getColor(R.color.w_muted))
        }
        action = Button(this).apply { text = actionLabel }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = Client()
            webChromeClient = Chooser()
        }
        // Microsoft's sign-in and TimeEdit's token check use cookies across sites.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_ground))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, if (compact) 0 else pad / 2, pad / 2, if (compact) 0 else pad / 2)
                addView(bar)
                if (statusText != null) addView(status, LinearLayout.LayoutParams(-1, -2).apply { rightMargin = pad / 2 })
                if (actionLabel != null) addView(action, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pad / 2 })
            })
            addView(progress, LinearLayout.LayoutParams(-1, -2))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        window.setDecorFitsSystemWindows(false) // as on Android 15+: the listener below pads for the bars
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    /**
     * Whether [url] leaves the page. Web pages stay; other schemes (e.g. the Microsoft
     * Authenticator app during SiswaMail sign-in) go to the app that handles them.
     */
    protected open fun leaves(url: Uri): Boolean {
        if (url.scheme == "https" || url.scheme == "http") return false
        runCatching { startActivity(Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    protected open fun pageStarted(url: String) = Unit
    protected open fun pageFinished(url: String) = Unit

    private fun updateHistoryBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val enabled = web.canGoBack()
        if (enabled == historyBackRegistered) return
        historyBackRegistered = enabled
        if (enabled) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, historyBack)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(historyBack)
    }

    @SuppressLint("GestureBackNavigation") // gestures on Android 13+ go to the callback above
    @Deprecated("Android 12 (and 13 to 15 when the callback isn't used) still calls this")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES) return
        val clip = data?.clipData
        fileChosen?.onReceiveValue(
            if (resultCode == RESULT_OK && clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        )
        fileChosen = null
    }

    /**
     * WebView ignores a page's file button (Spectrum's "Choose file") unless the app opens a picker.
     * Like Chrome, it offers any file: the page itself checks the type.
     */
    private inner class Chooser : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileChosen?.onReceiveValue(null)
            fileChosen = null
            val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            try {
                startActivityForResult(pick, PICK_FILES)
            } catch (e: ActivityNotFoundException) {
                return false
            }
            fileChosen = callback
            return true
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = leaves(request.url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            progress.visibility = View.VISIBLE
            pageStarted(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = updateHistoryBack()

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.INVISIBLE
            updateHistoryBack()
            pageFinished(url)
        }
    }

    private companion object {
        const val PICK_FILES = 1
    }
}
