package com.skylightlim.umtimetable

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.skylightlim.umtimetable.Motion.spring
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The attendance QR scanner: the camera, and the lecturer's code marks the user on Spectrum with the app's
 * own sign-in, no camera app or browser in between. Opened from Settings, or from auto attendance's
 * "Couldn't mark" notification for a class ([AutoAttendance]): then it names the class and why, and keeps
 * the class's attendance page a button away.
 *
 * A rotating code lasts about 15 seconds, so Spectrum is opened ahead: while the user aims, a hidden page
 * signs in (Microsoft again, if Spectrum's session ran out), and a code read goes straight to
 * assets/attendance-mark.js there, which opens the code's link ([AttendanceCode.link]) the way the
 * browser would. One that expired on the way is shrugged off: the next code reads by itself. Frames are
 * read with [QrDecoder]; a pinch zooms in on a screen across the hall. Each try goes to [AttendanceLog]
 * (the session, never the code's password).
 */
class ScanActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var screen: ScanScreen
    private lateinit var texture: TextureView
    private var presser: Motion.Presser? = null
    private val main = Handler(Looper.getMainLooper())

    /** The class from the notification ("WIX1001 tutorial"), what stopped auto attendance, its attendance page, the notification. */
    private var what: String? = null
    private var why: String? = null
    private var pageUrl: String? = null
    private var notification: String? = null

    // The camera, opened and closed on its own thread; frames are read on another.
    private val cameraThread = HandlerThread("scan-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val readThread = HandlerThread("scan-read").apply { start() }
    private val readHandler = Handler(readThread.looper)
    /** Main thread: the screen is in front, between onResume and onPause: only then is the camera open. */
    private var resumed = false
    /** Main thread: the camera is open, or opening. */
    private var cameraOn = false
    /** Each opening's number: callbacks of an earlier one close what they get. */
    @Volatile private var generation = 0
    private var device: CameraDevice? = null // camera thread
    private var session: CameraCaptureSession? = null // camera thread
    private var request: CaptureRequest.Builder? = null // camera thread
    private var previewSurface: Surface? = null // camera thread
    private var reader: ImageReader? = null
    private var previewSize: Size? = null
    private var zoomRange = 1f..1f
    @Volatile private var zoom = 1f
    /** The camera was refused: the screen says so until it's allowed (in Android's settings, say). */
    private var blocked = false
    /** Frames are read for a code; not while one is being marked, nor once it's done. */
    @Volatile private var reading = true
    private var luma = ByteArray(0) // read thread
    private val decoder = QrDecoder() // read thread

    // Spectrum, open in a page nobody sees.
    private var web: WebView? = null
    private var loads = 0
    private var pageReady = false
    /** Why the page isn't ready: "signedout" or "unreachable"; null while it loads. */
    private var pageTrouble: String? = null
    private var lastHost = ""
    private var lastPath = ""
    /** A code read before the page was ready: sent once it is. */
    private var waiting: Read? = null
    /** A code is being marked. */
    private var busy = false
    private var asks = 0
    /** Marked: the camera's off and the screen says so. */
    private var done = false
    /** When each code's text was last answered: the same one isn't sent again for a few seconds. */
    private val answeredAt = HashMap<String, Long>()
    private val backToAim = Runnable { if (!busy && !done && cameraOn) aim() }

    private class Read(val text: String, val code: AttendanceCode)

    private val pinch by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = (zoom * d.scaleFactor).coerceIn(zoomRange.start, zoomRange.endInclusive)
                if (abs(z - zoom) < 0.005f) return true
                zoom = z
                cameraHandler.post { repeat() }
                screen.showZoom(z)
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        what = intent.getStringExtra(EXTRA_WHAT)
        why = intent.getStringExtra(EXTRA_WHY)
        pageUrl = intent.getStringExtra(EXTRA_URL)
        notification = intent.getStringExtra(EXTRA_NOTIFICATION)
        // Over the camera the screen is always dark, in the chosen accent.
        ui = Ui(this, Scheme.load(this).applyTo(Palette.DARK), Fonts(this))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.decorView.setBackgroundColor(Color.BLACK)
        window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)

        texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeCamera(); return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
        screen = ScanScreen(ui, what) { Haptics.back(it); finish() }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(texture, FrameLayout.LayoutParams(-1, -1))
            addView(screen.view, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                screen.inset(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        presser = Motion.Presser(root)
        setContentView(root)
        screen.enter()
        aim()
        warm()
        if (!hasCamera()) requestPermissions(arrayOf(Manifest.permission.CAMERA), ASK_CAMERA)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Back from signing in on Spectrum's page: open it again here.
        if (pageTrouble == "signedout") warm()
        // Back from Android's settings with the camera allowed.
        if (blocked && hasCamera()) aim()
        openCamera()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        closeCamera()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web?.destroy()
        web = null
        cameraHandler.post { cameraThread.quitSafely() }
        readThread.quitSafely()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (cameraOn) pinch.onTouchEvent(ev)
        presser?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != ASK_CAMERA) return
        if (hasCamera()) { aim(); openCamera() } else noCamera()
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // --- what the screen says ----------------------------------------------------------------

    private fun aim() {
        if (done) return
        blocked = false
        screen.show(
            ScanScreen.Mood.AIM, "Point at the QR code",
            why?.let { "$it Hold the lecturer's code in the frame." } ?: "Hold the lecturer's code in the frame. Pinch to zoom in on a screen far away.",
            pageUrl?.let { url -> "Open in Spectrum" to { openSpectrum(url) } },
            quiet = true,
        )
    }

    private fun noCamera() {
        blocked = true
        screen.show(
        ScanScreen.Mood.TROUBLE, "The camera is off",
        "Let ${getString(R.string.app_name)} use the camera to read the lecturer's QR code. It only reads codes: nothing is kept or sent but the code's link, to Spectrum.",
        "Allow the camera" to {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) requestPermissions(arrayOf(Manifest.permission.CAMERA), ASK_CAMERA)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        },
        )
    }

    /** Says [title] and [body] for a while, then goes back to aiming (the camera still reading). */
    private fun brief(title: String, body: String, button: Pair<String, () -> Unit>? = null, mood: ScanScreen.Mood = ScanScreen.Mood.TROUBLE) {
        screen.show(mood, title, body, button, quiet = true)
        main.removeCallbacks(backToAim)
        main.postDelayed(backToAim, 4_000)
    }

    private fun openSpectrum(url: String) = startActivity(Intent(this, SpectrumWebActivity::class.java).putExtra(SpectrumWebActivity.EXTRA_URL, url))

    // --- a code read ---------------------------------------------------------------------------

    private fun scanned(text: String) {
        if (done || busy || isFinishing) return
        val now = SystemClock.elapsedRealtime()
        if (answeredAt[text]?.let { now - it < SAME_AGAIN } == true) return
        val code = AttendanceCode.parse(text)
        answeredAt[text] = now
        if (code == null) return brief("Not an attendance code", "That QR code isn't for a Spectrum attendance session. Point at the one your lecturer shows.")
        busy = true
        reading = false
        main.removeCallbacks(backToAim)
        Haptics.tick(screen.view)
        screen.show(ScanScreen.Mood.WORKING, "Marking you", "Sending the code to Spectrum…", null)
        AttendanceLog.add(this, "scan: session ${code.sessid}${if (code.qrpass != null) " (the code carries a password)" else ""}${what?.let { ", for $it" } ?: ""}; ${AutoAttendance.conditions(this)}")
        waiting = Read(text, code)
        if (pageReady) send() else if (pageTrouble != null) warm()
    }

    private fun send() {
        val read = waiting ?: return
        val page = web ?: return
        waiting = null
        val ask = ++asks
        main.postDelayed({ if (ask == asks) answered(read, JSONObject().put("error", "unreachable").put("detail", "no answer in time")) }, ANSWER_WITHIN)
        AutoAttendance.ask(page, dry = false, link = read.code.link()) { answer -> main.post { if (ask == asks) answered(read, answer) } }
    }

    private fun answered(read: Read, answer: JSONObject) {
        asks++ // a late answer to the same ask is dropped
        busy = false
        answeredAt[read.text] = SystemClock.elapsedRealtime()
        AttendanceLog.add(this, "scan: session ${read.code.sessid}: $answer")
        if (answer.optBoolean("ok")) return marked(answer)
        reading = true
        val typeIt = "Type it in Spectrum" to { openSpectrum(AttendanceCode(read.code.sessid, null).link()) }
        val said = answer.optString("detail").takeIf { it.isNotBlank() && !it.startsWith("no ") }
        when (answer.optString("error")) {
            // A rotating code goes stale every few seconds: this one did on the way. The next one is read by itself.
            "qr" -> brief("That code just changed", "Keep it in the frame: the next one goes by itself.", mood = ScanScreen.Mood.AIM)
            "password" -> brief("It needs the password", "Spectrum didn't take the code's password. Type the one your lecturer shows.", typeIt)
            "network" -> brief("Only on UM's Wi-Fi", "Spectrum takes this session from the campus network only. Join UM's Wi-Fi, then scan again.")
            "closed", "notopen" -> brief("The session isn't open", "Spectrum isn't taking attendance for it right now. Scan again once your lecturer opens it.")
            "nostatus" -> brief("Spectrum offered no status", "Mark it on the session's page instead.", typeIt)
            "signedout" -> signIn()
            "unreachable", "blocked" -> brief("Couldn't reach Spectrum", "Check your connection, then scan again.")
            else -> brief("Spectrum didn't take it", said ?: "Scan again, or mark it on the session's page.", typeIt)
        }
    }

    private fun marked(answer: JSONObject) {
        done = true
        reading = false
        main.removeCallbacks(backToAim)
        notification?.let { getSystemService(NotificationManager::class.java).cancel(it.hashCode()) }
        val status = answer.optString("status").ifBlank { "present" }.lowercase()
        val course = courseOf(answer.optInt("cmid")) ?: what?.substringBefore(' ')
        val at = SpectrumStore.hm(System.currentTimeMillis())
        Haptics.threshold(screen.view, true)
        screen.show(
            ScanScreen.Mood.DONE,
            if (answer.optBoolean("already")) "Marked already" else "Marked $status",
            listOfNotNull(course, if (answer.optBoolean("already")) "Spectrum had your attendance for this session." else "Spectrum recorded it at $at.").joinToString(" · "),
            "Done" to { finish() },
        )
        closeCamera()
    }

    /** The course of the attendance activity [cmid], from the last sync. */
    private fun courseOf(cmid: Int): String? {
        if (cmid <= 0) return null
        val activities = SpectrumStore.json(this, "attendance-activities")
        val courseId = activities.keys().asSequence().firstOrNull { id -> activities.optJSONArray(id)?.objects()?.any { it.optInt("cmid") == cmid } == true } ?: return null
        return SpectrumStore.array(this, "courses").objects().firstOrNull { it.optInt("id").toString() == courseId }?.optString("code")?.ifBlank { null }
    }

    private fun signIn() {
        pageTrouble = "signedout"
        screen.show(ScanScreen.Mood.TROUBLE, "Sign in to Spectrum", "Spectrum wants you to sign in again. Then come back here and scan.", "Sign in" to { openSpectrum("${Spectrum.SITE}/my/") })
    }

    // --- Spectrum, opened ahead --------------------------------------------------------------

    /**
     * Opens a small Spectrum page in a page nobody sees, so the sign-in is fresh when a code is read: the
     * code's link is then only one request away. A code read meanwhile waits for it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun warm() {
        web?.destroy()
        pageReady = false
        pageTrouble = null
        val load = ++loads
        val page = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, true)
        web = page
        page.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (view !== web) return
                val u = Uri.parse(url)
                lastHost = u.host.orEmpty()
                lastPath = u.path.orEmpty()
                if (lastHost != Uri.parse(Spectrum.SITE).host || lastPath.startsWith("/login/") || lastPath.startsWith("/auth/")) return
                pageReady = true
                pageTrouble = null
                send()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view !== web || !request.isForMainFrame) return
                pageTrouble = "unreachable"
                waiting?.let { waiting = null; answered(it, JSONObject().put("error", "unreachable").put("detail", error.description.toString())) }
            }
        }
        page.loadUrl("${Spectrum.SITE}/user/preferences.php")
        // Not on Spectrum by then: Microsoft (or Spectrum's own login page) wants the user, or Spectrum's out of reach.
        main.postDelayed({
            if (load != loads || pageReady) return@postDelayed
            pageTrouble = if (hostIn(lastHost, "microsoftonline.com", "live.com") || lastPath.startsWith("/login/")) "signedout" else "unreachable"
            waiting?.let { waiting = null; answered(it, JSONObject().put("error", pageTrouble)) }
        }, PAGE_WITHIN)
    }

    // --- the camera ------------------------------------------------------------------------------

    /** Opens the back camera into the preview and the frame reader, once the preview's there and the camera's allowed. */
    private fun openCamera() {
        if (cameraOn || !resumed || done || isFinishing || !texture.isAvailable || !hasCamera()) return
        val st = texture.surfaceTexture ?: return
        val cm = getSystemService(CameraManager::class.java)
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull()
        }.getOrNull() ?: return cameraTrouble()
        val traits = cm.getCameraCharacteristics(id)
        val map = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return cameraTrouble()
        val frameSize = choose(map.getOutputSizes(ImageFormat.YUV_420_888), null)
        val preview = choose(map.getOutputSizes(SurfaceTexture::class.java), frameSize)
        previewSize = preview
        zoomRange = traits.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { max(1f, it.lower)..max(1f, min(it.upper, 10f)) } ?: 1f..1f
        val autofocus = traits.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let { modes ->
            listOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_AUTO).firstOrNull { it in modes }
        }
        st.setDefaultBufferSize(preview.width, preview.height)
        fit()
        val frames = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.YUV_420_888, 2)
        frames.setOnImageAvailableListener(::frame, readHandler)
        reader = frames
        cameraOn = true
        reading = !busy
        val mine = ++generation
        cameraHandler.post {
            try {
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (mine != generation) return camera.close()
                        device = camera
                        val surface = Surface(st)
                        previewSurface = surface
                        startSession(camera, surface, frames, autofocus)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (device === camera) device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (device === camera) device = null
                        if (mine == generation) main.post { cameraTrouble() }
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                main.post { cameraTrouble() }
            }
        }
    }

    private fun startSession(camera: CameraDevice, preview: Surface, frames: ImageReader, autofocus: Int?) {
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(frames.surface))
        val onCamera = Executor { cameraHandler.post(it) }
        runCatching {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, onCamera, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== camera) return s.close()
                    session = s
                    request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                        addTarget(frames.surface)
                        autofocus?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                    }
                    repeat()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    main.post { cameraTrouble() }
                }
            }))
        }.onFailure { main.post { cameraTrouble() } }
    }

    /** Camera thread: the preview request again, at the zoom pinched to. */
    private fun repeat() {
        val r = request ?: return
        val s = session ?: return
        if (zoomRange.endInclusive > 1f) r.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        runCatching { s.setRepeatingRequest(r.build(), null, cameraHandler) }
    }

    private fun closeCamera() {
        if (!cameraOn) return
        cameraOn = false
        generation++
        val frames = reader
        reader = null
        cameraHandler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { frames?.close() } // a frame being read then fails, and is dropped
            previewSurface?.release()
            session = null
            device = null
            request = null
            previewSurface = null
        }
    }

    private fun cameraTrouble() {
        if (done) return
        screen.show(ScanScreen.Mood.TROUBLE, "The camera didn't open", "Another app may be using it. Close that one, then come back here.", pageUrl?.let { url -> "Open in Spectrum" to { openSpectrum(url) } }, quiet = true)
    }

    /**
     * The biggest size up to 1920 × 1080 (a code across a lecture hall needs the pixels) in [like]'s shape,
     * else 16:9, else any.
     */
    private fun choose(sizes: Array<Size>, like: Size?): Size {
        val fits = sizes.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { sizes.toList() }
        val aspect = like?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        return fits.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }.maxByOrNull { it.width * it.height }
            ?: fits.maxBy { it.width * it.height }
    }

    /**
     * Fills the screen with the preview without stretching it, cutting off the sides as a camera app does.
     * Turned sideways (a tablet), it's turned back too.
     */
    private fun fit() {
        val size = previewSize ?: return
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val cx = vw / 2
        val cy = vh / 2
        val m = Matrix()
        val rotation = display?.rotation ?: Surface.ROTATION_0
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val view = RectF(0f, 0f, vw, vh)
            val buffer = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat()).apply { offset(cx - centerX(), cy - centerY()) }
            m.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
            val scale = max(vh / size.height, vw / size.width)
            m.postScale(scale, scale, cx, cy)
            m.postRotate(90f * (rotation - 2), cx, cy)
        } else {
            // Upright already, but stretched to the view: back to its own shape (portrait: height × width), filling it.
            val bw = size.height.toFloat()
            val bh = size.width.toFloat()
            val scale = max(vw / bw, vh / bh)
            m.setScale(bw * scale / vw, bh * scale / vh, cx, cy)
            if (rotation == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
        }
        texture.setTransform(m)
    }

    /** Read thread: the newest frame's brightness, looked through for a code. */
    private fun frame(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val text = try {
            if (!reading) null else {
                val plane = image.planes[0]
                val bytes = plane.buffer
                val stride = plane.rowStride
                if (luma.size < stride * image.height) luma = ByteArray(stride * image.height)
                val n = min(bytes.remaining(), luma.size)
                bytes.get(luma, 0, n)
                decoder.decode(luma, stride, image.width, image.height)
            }
        } catch (e: Exception) {
            null // the reader closed under it, or a frame it couldn't take
        } finally {
            image.close()
        }
        if (text != null) main.post { scanned(text) }
    }

    companion object {
        const val EXTRA_WHAT = "what"
        const val EXTRA_WHY = "why"
        const val EXTRA_URL = "url"
        const val EXTRA_NOTIFICATION = "notification"
        private const val ASK_CAMERA = 1
        /** The same code isn't sent again within this, once answered (ms). */
        private const val SAME_AGAIN = 5_000L
        private const val ANSWER_WITHIN = 30_000L
        private const val PAGE_WITHIN = 30_000L

        fun intent(c: Context) = Intent(c, ScanActivity::class.java)

        /**
         * The scanner for class [what] ("WIX1001 tutorial"), which auto attendance couldn't mark ([why]), from
         * its notification ([key], taken away once it's marked); [url] is the class's attendance page.
         */
        fun pending(c: Context, what: String, why: String, url: String, key: String): PendingIntent = PendingIntent.getActivity(
            c, "scan-$key".hashCode(),
            intent(c).putExtra(EXTRA_WHAT, what).putExtra(EXTRA_WHY, why).putExtra(EXTRA_URL, url).putExtra(EXTRA_NOTIFICATION, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * The scanner's face over the camera: a top bar like the web screens' (the class small in the accent,
 * "Scan to mark" in Big Shoulders, a Close pill), the [Finder] frame, a zoom badge while pinching and a
 * card at the bottom saying what's going on, with a button when there's something to do. The camera
 * isn't part of it, so the debug renderer can draw it alone.
 */
class ScanScreen(private val ui: Ui, what: String?, onClose: (View) -> Unit) {
    enum class Mood { AIM, WORKING, DONE, TROUBLE }

    val view: FrameLayout
    val finder = Finder(ui)
    private val bar: LinearLayout
    private val card: LinearLayout
    private val status: TextView
    private val body: TextView
    private val button: TextView
    private val zoom: TextView
    private val close: View
    private var mood: Mood? = null
    private val hideZoom = Runnable { zoom.animate().alpha(0f).setDuration(220).start() }

    init {
        val p = ui.p
        val c = ui.context
        close = ui.row(gap = 6f).apply {
            background = ui.pressable(ui.shape(p.text, 99f), 99f)
            setPadding(ui.dp(12), 0, ui.dp(14), 0)
            contentDescription = "Close"
            addView(ui.icon(R.drawable.ic_close, p.ground, 14f))
            addView(ui.text("Close", 14f, p.ground, ui.f.bodySemi))
            setOnClickListener { onClose(this) }
        }
        bar = ui.column().apply {
            // Dark behind the words at the top, fading into the picture.
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            addView(ui.row().apply {
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28))
                addView(ui.column().apply {
                    addView(ui.label(what ?: "Attendance", p.accentText, 10.5f))
                    addView(ui.text("Scan to mark", 30f, p.text, ui.f.sign), ui.wide(top = 3f))
                }, ui.grow())
                addView(FrameLayout(c).apply {
                    addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40), Gravity.CENTER))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)).apply { marginStart = ui.dp(12) })
            })
        }
        status = RollText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(p.text)
            typeface = ui.f.sign
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body = RollText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(p.muted)
            typeface = ui.f.body
            setLineSpacing(0f, 1.25f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        button = ui.text("", 15f, p.accentInk, ui.f.bodySemi).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = ui.pressable(ui.shape(p.accent, 14f), 14f)
            visibility = View.GONE
        }
        card = ui.column().apply {
            background = ui.shape(p.surface, 24f)
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            isClickable = true // a press on the card doesn't reach the camera behind it
            addView(status)
            addView(body, ui.wide(top = 8f))
            addView(button, ui.wide(top = 16f))
        }
        zoom = ui.text("1×", 13f, p.text, ui.f.monoBold).apply {
            background = ui.shape(0x99000000.toInt(), 99f)
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            alpha = 0f
        }
        view = FrameLayout(c).apply {
            clipChildren = false
            addView(finder, FrameLayout.LayoutParams(-1, -1))
            addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            addView(bar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(card, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val m = ui.dp(12)
                setMargins(m, 0, m, m)
            })
        }
        // The zoom badge sits just under the frame.
        finder.onHole = { hole -> zoom.translationY = hole.bottom + ui.dp(16) }
    }

    /** Room for the status and navigation bars (and a cutout). */
    fun inset(left: Int, top: Int, right: Int, bottom: Int) {
        bar.setPadding(left, top, right, 0)
        (card.layoutParams as FrameLayout.LayoutParams).apply {
            val m = ui.dp(12)
            setMargins(m + left, 0, m + right, m + bottom)
        }
        card.requestLayout()
    }

    /** The bar and the card rise in; then the status and its line roll over as they change. */
    fun enter() {
        Motion.enter(bar, ui.dp(12).toFloat())
        Motion.riseIn(card, ui.dp(40).toFloat())
        if (Motion.enabled) {
            close.alpha = 0f
            close.post {
                close.scaleX = 0.5f
                close.scaleY = 0.5f
                close.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(160).spring(Motion.BOUNCY).start()
            }
        }
        view.post {
            (status as RollText).rolls = true
            (body as RollText).rolls = true
        }
    }

    /**
     * What's going on: the frame's [mood], a [title] and its [line], and an optional button (its label and
     * what it does): in the accent, or grey when [quiet] (another way round, while scanning is still the way).
     */
    fun show(mood: Mood, title: String, line: String, action: Pair<String, () -> Unit>?, quiet: Boolean = false) {
        status.text = title
        body.text = line
        val was = button.visibility == View.VISIBLE
        if (action == null) button.visibility = View.GONE
        else {
            button.background = ui.pressable(ui.shape(if (quiet) ui.p.surface2 else ui.p.accent, 14f), 14f)
            button.setTextColor(if (quiet) ui.p.text else ui.p.accentInk)
            button.text = action.first
            button.contentDescription = action.first
            button.setOnClickListener { Haptics.tick(it); action.second() }
            button.visibility = View.VISIBLE
            if (!was && view.isLaidOut) Motion.pop(button, 0.9f)
        }
        if (mood != this.mood) {
            this.mood = mood
            finder.mood(mood)
        }
    }

    /** Every move of the frame at its end, at once (for a still of a state). */
    fun settle() = finder.settle()

    /** The zoom pinched to, for a moment. */
    fun showZoom(z: Float) {
        zoom.text = if (z < 9.95f) "%.1f×".format(z) else "${z.toInt()}×"
        zoom.animate().cancel()
        zoom.alpha = 1f
        zoom.removeCallbacks(hideZoom)
        zoom.postDelayed(hideZoom, 900)
    }
}

/**
 * Where to hold the code: the camera dimmed around a rounded square, its corners marked in the accent.
 * Aiming, the corners breathe; marking, they close in and the square glows; done, a tick pops up in an
 * accent disc; trouble, the corners go white and shake their heads.
 */
@SuppressLint("ViewConstructor")
class Finder(private val ui: Ui) : View(ui.context) {
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private val corners = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(5f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ui.p.accent
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.p.accent }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.p.accent }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ui.p.accentInk
    }
    private val path = Path()
    private val hole = RectF()
    /** Told where the square is, once it's laid out. */
    var onHole: ((RectF) -> Unit)? = null

    /** How far in the corners are (1: at the square's edge). */
    private var squeeze = 1f
    /** The accent glow inside the square, 0 to 1. */
    private var lit = 0f
    /** The tick's disc, 0 (none) to 1. */
    private var ticked = 0f
    /** The corners' sideways shake, px. */
    private var shake = 0f
    private var breathing: ValueAnimator? = null
    private val moves = mutableListOf<ValueAnimator>()
    private var mood = ScanScreen.Mood.AIM

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = min(w * 0.7f, h * 0.36f)
        val cx = w / 2f
        val cy = h * 0.42f
        hole.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        onHole?.invoke(hole)
    }

    override fun onDetachedFromWindow() {
        stopAll()
        super.onDetachedFromWindow()
    }

    private fun stopAll() {
        breathing?.cancel()
        breathing = null
        moves.forEach { it.cancel() }
        moves.clear()
    }

    /** Every move at its end, the breathing at rest. */
    fun settle() {
        moves.toList().forEach { it.end() }
        breathing?.cancel()
        breathing = null
        if (mood == ScanScreen.Mood.AIM) squeeze = 1f
        invalidate()
    }

    /** Springs [from] → [to] with [spring], each step through [set]. */
    private fun move(from: Float, to: Float, spring: Motion.Spring, delay: Long = 0, set: (Float) -> Unit) {
        if (!Motion.enabled) return set(to)
        moves += ValueAnimator.ofFloat(from, to).apply {
            interpolator = spring
            duration = spring.duration
            startDelay = delay
            addUpdateListener { set(it.animatedValue as Float); invalidate() }
            start()
        }
    }

    fun mood(m: ScanScreen.Mood) {
        mood = m
        stopAll()
        when (m) {
            ScanScreen.Mood.AIM -> {
                corners.color = ui.p.accent
                move(squeeze, 1f, Motion.SETTLE) { squeeze = it }
                move(lit, 0f, Motion.QUICK) { lit = it }
                move(ticked, 0f, Motion.QUICK) { ticked = it }
                shake = 0f
                // In and out, slowly, as if looking.
                if (Motion.enabled) breathing = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1400
                    startDelay = 600
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { squeeze = 1f - 0.035f * (it.animatedValue as Float); invalidate() }
                    start()
                }
            }
            ScanScreen.Mood.WORKING -> {
                corners.color = ui.p.accent
                move(squeeze, 0.9f, Motion.BOUNCY) { squeeze = it }
                move(lit, 1f, Motion.SMOOTH) { lit = it }
                shake = 0f
            }
            ScanScreen.Mood.DONE -> {
                corners.color = ui.p.accent
                move(squeeze, 0.84f, Motion.SETTLE) { squeeze = it }
                move(lit, 0f, Motion.SMOOTH) { lit = it }
                move(0f, 1f, Motion.BOUNCY, delay = 60) { ticked = it }
                shake = 0f
            }
            ScanScreen.Mood.TROUBLE -> {
                corners.color = ui.p.text
                move(squeeze, 1f, Motion.SETTLE) { squeeze = it }
                move(lit, 0f, Motion.QUICK) { lit = it }
                move(ticked, 0f, Motion.QUICK) { ticked = it }
                // A no: left, right, left, settling.
                if (Motion.enabled) moves += ValueAnimator.ofFloat(0f, -1f, 0.8f, -0.5f, 0.25f, 0f).apply {
                    duration = 520
                    addUpdateListener { shake = ui.dp(9f) * (it.animatedValue as Float); invalidate() }
                    start()
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (hole.isEmpty) return
        val r = ui.dp(28f).toFloat()
        // The camera dimmed but for the square; more so once it's done.
        dim.alpha = (0x8C + (0xD0 - 0x8C) * ticked).toInt()
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(hole, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)
        if (lit > 0f) {
            glow.alpha = (lit * 0.22f * 255).toInt()
            canvas.drawRoundRect(hole, r, r, glow)
        }

        canvas.save()
        canvas.translate(shake, 0f)
        canvas.scale(squeeze, squeeze, hole.centerX(), hole.centerY())
        val len = ui.dp(46f).toFloat()
        path.reset()
        path.fillType = Path.FillType.WINDING
        val (l, t, rt, b) = listOf(hole.left, hole.top, hole.right, hole.bottom)
        // Each corner: along one edge, round the corner, along the other.
        path.moveTo(l, t + len); path.lineTo(l, t + r); path.quadTo(l, t, l + r, t); path.lineTo(l + len, t)
        path.moveTo(rt - len, t); path.lineTo(rt - r, t); path.quadTo(rt, t, rt, t + r); path.lineTo(rt, t + len)
        path.moveTo(rt, b - len); path.lineTo(rt, b - r); path.quadTo(rt, b, rt - r, b); path.lineTo(rt - len, b)
        path.moveTo(l + len, b); path.lineTo(l + r, b); path.quadTo(l, b, l, b - r); path.lineTo(l, b - len)
        canvas.drawPath(path, corners)
        canvas.restore()

        if (ticked > 0.001f) {
            val cx = hole.centerX()
            val cy = hole.centerY()
            val radius = hole.width() * 0.26f
            canvas.save()
            canvas.scale(ticked, ticked, cx, cy)
            canvas.drawCircle(cx, cy, radius, disc)
            tick.strokeWidth = radius * 0.16f
            path.reset()
            path.moveTo(cx - radius * 0.42f, cy + radius * 0.02f)
            path.lineTo(cx - radius * 0.1f, cy + radius * 0.34f)
            path.lineTo(cx + radius * 0.46f, cy - radius * 0.3f)
            canvas.drawPath(path, tick)
            canvas.restore()
        }
    }
}
