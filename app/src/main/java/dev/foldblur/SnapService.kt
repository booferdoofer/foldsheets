package dev.foldblur

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.exp

/**
 * Accessibility service used only for two things: taking a screenshot the moment a fold
 * starts, and drawing that screenshot over everything (blurred + zoomed) while the real
 * screen switches underneath. It reads no screen content and ignores all events.
 *
 * Speed notes:
 *  - The screenshot stays a GPU (hardware) bitmap: no copying, no decoding.
 *  - Blur, zoom and fade are view properties drawn by the RenderThread on the GPU.
 *  - The overlay window exists only during a fold.
 */
class SnapService : AccessibilityService() {

    companion object {
        @Volatile var instance: SnapService? = null; private set
    }

    private lateinit var wm: WindowManager
    private val choreo by lazy { Choreographer.getInstance() }

    private var root: FrameLayout? = null
    private var image: ImageView? = null
    private var bitmap: Bitmap? = null
    private var lastCaptureMs = 0L

    // animation state (0..1 = how far into the fold the effect is)
    private var progress = 0f
    private var target = 0f
    private var alphaNow = 1f
    private var releasing = false
    private var lastFrameNs = 0L
    private var running = false
    private var lastRadius = -1f

    private var blurPx = 90f
    private var zoom = 0.08f
    private var fadeInMs = 90f
    private var fadeOutMs = 400f

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        instance = this
        Diag.log("screenshot animation ready (Accessibility on)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        dismiss()
        instance = null
        return super.onUnbind(intent)
    }

    val isShowing get() = root != null

    fun configure(s: BlurSettings) {
        blurPx = s.blurPx.toFloat().coerceAtLeast(1f)
        zoom = s.zoomPct / 100f
        fadeInMs = s.fadeInMs.toFloat()
        fadeOutMs = s.fadeOutMs.toFloat()
    }

    /** Screenshots are limited by Android to about 3 per second. */
    fun canCapture() = SystemClock.uptimeMillis() - lastCaptureMs > 350

    /** Take a screenshot; [done] gets the GPU bitmap (or null) on the main thread. */
    fun capture(done: (Bitmap?) -> Unit) {
        lastCaptureMs = SystemClock.uptimeMillis()
        val t0 = lastCaptureMs
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val buf = r.hardwareBuffer
                val b = runCatching { Bitmap.wrapHardwareBuffer(buf, r.colorSpace) }.getOrNull()
                buf.close()
                Live.snapMs = SystemClock.uptimeMillis() - t0
                done(b)
            }
            override fun onFailure(errorCode: Int) {
                Diag.log("screenshot failed (code $errorCode)")
                done(null)
            }
        })
    }

    /** Put the screenshot on screen, pixel-identical to what's there, ready to animate. */
    fun show(b: Bitmap, startProgress: Float) {
        dismiss()
        bitmap = b
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(b)
        }
        val fl = FrameLayout(this).apply {
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val lp = LP(
            LP.MATCH_PARENT, LP.MATCH_PARENT, LP.TYPE_ACCESSIBILITY_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or LP.FLAG_LAYOUT_IN_SCREEN or
                LP.FLAG_LAYOUT_NO_LIMITS or LP.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
            title = "FoldBlurSnap"
        }
        try {
            wm.addView(fl, lp)
        } catch (e: Exception) {
            Diag.log("snapshot overlay failed: ${e.message}")
            bitmap = null
            return
        }
        root = fl; image = iv
        progress = startProgress; target = startProgress
        alphaNow = 1f; releasing = false; lastRadius = -1f
        applyFrame()
    }

    /** Follow the fold: 0 = sharp, 1 = fully blurred and zoomed. */
    fun setTarget(p: Float) {
        if (root == null) return
        releasing = false
        target = p.coerceIn(0f, 1f)
        kick()
    }

    /** The fold is done: unblur, unzoom and fade the screenshot away to reveal the real screen. */
    fun release() {
        if (root == null) return
        releasing = true
        target = 0f
        kick()
    }

    fun dismiss() {
        choreo.removeFrameCallback(frame)
        running = false
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
        image?.setImageDrawable(null)
        image = null
        bitmap?.recycle()
        bitmap = null
    }

    private fun kick() {
        if (!running) {
            running = true
            lastFrameNs = 0L
            choreo.postFrameCallback(frame)
        }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (root == null) { running = false; return }
            val dt = if (lastFrameNs == 0L) 16f else ((now - lastFrameNs) / 1e6f).coerceIn(1f, 50f)
            lastFrameNs = now
            val tau = if (target > progress) fadeInMs / 2f else fadeOutMs / 3f
            progress += (target - progress) * (1f - exp(-dt / tau.coerceAtLeast(10f)))
            if (abs(target - progress) < 0.002f) progress = target
            if (releasing) {
                alphaNow += (0f - alphaNow) * (1f - exp(-dt / (fadeOutMs / 3f).coerceAtLeast(10f)))
                if (alphaNow < 0.01f) { dismiss(); return }
            } else if (alphaNow < 1f) {
                // a new fold started while the last one was fading out: bring it back
                alphaNow += (1f - alphaNow) * (1f - exp(-dt / 60f))
                if (alphaNow > 0.995f) alphaNow = 1f
            }
            applyFrame()
            if (progress != target || releasing || alphaNow < 1f) choreo.postFrameCallback(this)
            else running = false
        }
    }

    private fun applyFrame() {
        val iv = image ?: return
        val fl = root ?: return
        // ease so the start of the fold reacts gently, like iOS
        val e = progress * progress * (3f - 2f * progress)
        val s = 1f + zoom * e
        iv.scaleX = s; iv.scaleY = s
        val r = blurPx * e
        // Only rebuild the blur when it changes visibly (saves GPU work).
        if (abs(r - lastRadius) >= 0.75f || (r < 0.5f) != (lastRadius < 0.5f)) {
            iv.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            lastRadius = r
        }
        fl.alpha = alphaNow
    }
}
