package com.skylightlim.umtimetable

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring

/**
 * The shared frame of the app's web screens (Spectrum sign-in and pages, MAYA, TimeEdit), in the app's
 * own look and theme: a top bar like the main screen's (the site small in the accent, the title in Big
 * Shoulders, a Done or Close pill), an optional status line and action button, a loading sweep and the
 * page. The bar rises in and the pill pops in; once the page has loaded the pill nudges, so it's seen.
 * Titles and status lines roll over when they change. Back steps through the page's history first.
 */
abstract class WebScreen : Activity() {
    protected lateinit var ui: Ui
    protected lateinit var web: WebView
    protected lateinit var title: TextView
    protected lateinit var status: TextView
    protected lateinit var action: TextView
    /** The loading sweep: VISIBLE while a page loads, INVISIBLE otherwise. */
    protected lateinit var progress: View
    private lateinit var bar: WebBar
    private var presser: Motion.Presser? = null

    // On Android 16+ onBackPressed() is no longer called; step back through web history with a
    // callback that's registered only while there is history, so back leaves the screen otherwise.
    // Android 12 has no callback type (making one there crashes): it's made on first use, and
    // onBackPressed steps back there.
    private val historyBack by lazy { OnBackInvokedCallback { web.goBack() } }
    private var historyBackRegistered = false

    /** The page waiting for files from the picker (an assignment's upload). */
    private var fileChosen: ValueCallback<Array<Uri>>? = null

    /**
     * Builds the screen: [WebBar] over the page. [site] names where the page is (Spectrum, MAYA, TimeEdit),
     * over [titleText]; [closeLabel] "Done" or "Close"/"Cancel"; [statusText] null leaves out the status
     * line, [actionLabel] null the button; [compact] is a one-line title, for pages the user only reads.
     */
    @SuppressLint("SetJavaScriptEnabled")
    protected fun setUp(site: String, titleText: String, closeLabel: String, statusText: String? = null, actionLabel: String? = null, compact: Boolean = false) {
        ui = Ui(this, Look.load(this).palette(this), Fonts(this))
        val p = ui.p
        bar = WebBar(ui, site, titleText, closeLabel, statusText, actionLabel, compact) { Haptics.back(it); finish() }
        title = bar.title
        status = bar.status
        action = bar.action
        progress = bar.progress
        web = WebView(this).apply {
            setBackgroundColor(p.ground) // until the page paints
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = Client()
            webChromeClient = Chooser()
        }
        // Microsoft's sign-in and TimeEdit's token check use cookies across sites.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.ground)
            addView(bar.view)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        presser = Motion.Presser(root)
        window.setDecorFitsSystemWindows(false) // as on Android 15+: the listener below pads for the bars
        window.decorView.setBackgroundColor(p.ground)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else lightBars, lightBars)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        bar.enter()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        presser?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
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
            bar.nudgeClose()
        }
    }

    private companion object {
        const val PICK_FILES = 1
    }
}

/**
 * The web screens' top bar, like the main screen's: [site] small in the accent over the [title] in Big
 * Shoulders, and a pill to leave: "Done" in the accent with a tick, "Close" or "Cancel" in the text
 * colour with a cross ([onClose]). Under them an optional [status] line and [action] button, then a hairline with the
 * loading sweep ([progress]) along it. [view] is all of it.
 */
class WebBar(ui: Ui, site: String, titleText: String, closeLabel: String, statusText: String?, actionLabel: String?, compact: Boolean, onClose: (View) -> Unit) {
    val title: TextView
    val status: TextView
    val action: TextView = PillButton(ui).apply { text = actionLabel }
    val progress: View = SweepBar(ui.context, ui.p.accent)
    private val close: View
    private val header: LinearLayout
    val view: LinearLayout
    /** The close pill has nudged once, after the first page loaded. */
    private var nudged = false

    init {
        val p = ui.p
        val context = ui.context
        title = RollText(context).apply {
            text = titleText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 26f else 30f)
            setTextColor(p.text)
            typeface = ui.f.sign
            includeFontPadding = false
            maxLines = if (compact) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
        }
        status = RollText(context).apply {
            text = statusText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(p.muted)
            typeface = ui.f.body
            setLineSpacing(0f, 1.25f)
        }
        val done = closeLabel == "Done"
        close = ui.row(gap = 6f).apply {
            // Done in the accent; Close and Cancel in the text colour (a grey pill all but vanished on the light page).
            background = ui.pressable(ui.shape(if (done) p.accent else p.text, 99f), 99f)
            setPadding(ui.dp(12), 0, ui.dp(14), 0)
            contentDescription = closeLabel
            val ink = if (done) p.accentInk else p.ground
            addView(ui.icon(if (done) R.drawable.ic_check else R.drawable.ic_close, ink, if (done) 16f else 14f))
            addView(ui.text(closeLabel, 14f, ink, ui.f.bodySemi))
            setOnClickListener { onClose(this) }
        }
        header = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(if (compact) 8 else 12), ui.dp(16), ui.dp(if (compact) 8 else 14))
            addView(ui.row().apply {
                addView(ui.column().apply {
                    addView(ui.label(site, p.accentText, 10.5f))
                    addView(title, ui.wide(top = 3f))
                }, ui.grow())
                // 40dp tall, in a 48dp row so it's as easy to hit as any button.
                addView(FrameLayout(context).apply {
                    addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40), Gravity.CENTER))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)).apply { marginStart = ui.dp(12) })
            })
            if (statusText != null) addView(status, ui.wide(top = 6f))
            // In a frame of its own: the entrance fades the frame, and the button keeps its own fade (until it's ready).
            if (actionLabel != null) addView(FrameLayout(context).apply { addView(action, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }, ui.wide(top = 12f))
        }
        view = ui.column().apply {
            addView(header)
            addView(FrameLayout(context).apply {
                addView(View(context).apply { setBackgroundColor(p.line) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))
                addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3)))
        }
    }

    /** The bar rises in part by part, and the pill pops in after the title. Then titles and status lines roll when they change. */
    fun enter() {
        Motion.enter(header, header.resources.displayMetrics.density * 12)
        if (Motion.enabled) {
            close.alpha = 0f
            close.post {
                close.scaleX = 0.5f
                close.scaleY = 0.5f
                close.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(160).spring(Motion.BOUNCY).start()
            }
        }
        (title as RollText).rolls = true
        (status as RollText).rolls = true
    }

    /** The close pill wiggles once, after the first page has loaded: here's the way back. */
    fun nudgeClose() {
        if (nudged || !Motion.enabled) return
        nudged = true
        close.pivotX = close.width / 2f
        close.pivotY = close.height / 2f
        ObjectAnimator.ofPropertyValuesHolder(
            close,
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f, -7f, 6f, -4f, 2f, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1.08f, 1.04f, 1f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1.08f, 1.04f, 1f, 1f),
        ).setDuration(560).apply { startDelay = 250 }.start()
    }
}

/** Text that rolls over when it changes, once [rolls] is set: the old slips up and out, the new rises in. */
private class RollText(context: Context) : TextView(context) {
    var rolls = false

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!rolls || !isLaidOut || !Motion.enabled || text.toString() == this.text.toString()) return super.setText(text, type)
        val d = 8 * resources.displayMetrics.density
        animate().cancel()
        animate().translationY(-d).alpha(0f).setDuration(110).setInterpolator(Motion.EXIT).withEndAction {
            put(text, type)
            translationY = d
            animate().translationY(0f).spring(Motion.SETTLE).start()
            Motion.fade(this, 0f, 1f, 160)
        }.start()
    }

    private fun put(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

/**
 * The web screen's action (Read this page, Get timetable): a full-width accent pill. Not ready yet, it's
 * faded; when it becomes ready it brightens and pops a little, to say it can be tapped now.
 */
@SuppressLint("ViewConstructor")
private class PillButton(ui: Ui) : TextView(ui.context) {
    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ui.p.accentInk)
        typeface = ui.f.bodySemi
        includeFontPadding = false
        gravity = Gravity.CENTER
        minHeight = ui.dp(48)
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
        background = ui.pressable(ui.shape(ui.p.accent, 14f), 14f)
    }

    override fun setEnabled(enabled: Boolean) {
        val was = isEnabled
        super.setEnabled(enabled)
        if (!isLaidOut || !Motion.enabled) { alpha = if (enabled) 1f else 0.4f; return }
        if (enabled == was) return
        animate().alpha(if (enabled) 1f else 0.4f).setDuration(180).start()
        if (enabled) Motion.pop(this, 0.94f)
    }
}

/** A short accent bar sweeping along the bar's hairline, again and again, while it's shown. */
@SuppressLint("ViewConstructor")
private class SweepBar(context: Context, color: Int) : View(context) {
    private val paint = Paint().apply { this.color = color }
    private var sweeping: ValueAnimator? = null
    private var t = 0f

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else if (visibility != VISIBLE) stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (sweeping != null || !Motion.enabled) return
        sweeping = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        sweeping?.cancel()
        sweeping = null
    }

    override fun onDraw(canvas: Canvas) {
        // With "Remove animations", a still bar the whole width says it's loading.
        if (!Motion.enabled) return canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val seg = width * 0.32f
        val x = -seg + (width + seg) * t
        canvas.drawRoundRect(x, 0f, x + seg, height.toFloat(), height / 2f, height / 2f, paint)
    }
}
