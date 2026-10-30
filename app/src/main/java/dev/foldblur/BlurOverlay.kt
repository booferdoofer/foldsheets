package dev.foldblur

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A full-screen, invisible-to-touch window that blurs whatever is behind it
 * (Smart Launcher, wallpaper, any app). It doesn't capture the screen or change
 * anything underneath: the system compositor does the blur, and the window is
 * removed completely as soon as the transition ends.
 *
 * Must be used from the main thread.
 */
class BlurOverlay(context: Context) {

    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val choreo = Choreographer.getInstance()

    private var view: View? = null
    private var settings = Prefs.load(ctx)

    private val lp = LP(
        LP.MATCH_PARENT, LP.MATCH_PARENT,
        LP.TYPE_APPLICATION_OVERLAY,
        LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or
            LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS or
            LP.FLAG_BLUR_BEHIND,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "FoldBlur"
        layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        windowAnimations = 0
        blurBehindRadius = 0
    }

    // Our own frame-driven animation, so it still runs if the user has turned
    // system "animator duration scale" down or off.
    private var level = 0f
    private var from = 0f
    private var to = 0f
    private var startNs = 0L
    private var durNs = 0L
    private var interp: TimeInterpolator = DecelerateInterpolator()
    private var onDone: (() -> Unit)? = null
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val p = if (durNs <= 0) 1f
            else ((frameTimeNanos - startNs).toFloat() / durNs).coerceIn(0f, 1f)
            apply(from + (to - from) * interp.getInterpolation(p))
            if (p < 1f) {
                choreo.postFrameCallback(this)
            } else {
                running = false
                val d = onDone; onDone = null; d?.invoke()
            }
        }
    }

    private val easeIn = DecelerateInterpolator(1.5f)
    // iOS-style "ease out": quick start, long soft landing into focus
    private val easeOut = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val safety = Runnable { dismissNow() }

    fun configure(s: BlurSettings) { settings = s }

    val isShowing get() = view != null

    /** Ramp up to full blur and stay there until [release]. */
    fun hold() {
        if (!Settings.canDrawOverlays(ctx)) return
        if (!attach()) return
        animateTo(1f, settings.fadeInMs, easeIn, null)
        // Never leave a blur on screen if something goes wrong.
        val v = view ?: return
        v.removeCallbacks(safety)
        v.postDelayed(safety, SAFETY_MS)
    }

    /** Fade the blur out (new screen comes into focus), then remove the window. */
    fun release() {
        val v = view ?: return
        v.removeCallbacks(safety)
        animateTo(0f, settings.fadeOutMs, easeOut) { detach() }
    }

    fun dismissNow() {
        choreo.removeFrameCallback(frame)
        running = false
        onDone = null
        level = 0f
        detach()
    }

    private fun animateTo(target: Float, fullMs: Long, i: TimeInterpolator, done: (() -> Unit)?) {
        from = level
        to = target
        interp = i
        onDone = done
        // Scale by distance so a half-faded blur doesn't take a full duration to finish.
        durNs = (fullMs * 1_000_000L * abs(target - from)).toLong()
        startNs = System.nanoTime()
        if (!running) {
            running = true
            choreo.postFrameCallback(frame)
        }
    }

    private fun apply(v: Float) {
        level = v
        val vw = view ?: return
        lp.blurBehindRadius = (settings.blurPx * v).roundToInt()
        val a = (255 * settings.dim * v).roundToInt().coerceIn(0, 255)
        vw.setBackgroundColor(Color.argb(a, 0, 0, 0))
        try {
            wm.updateViewLayout(vw, lp)
        } catch (_: Exception) {
            view = null // window was torn down by the system; start fresh next time
        }
    }

    private fun attach(): Boolean {
        if (view != null) return true
        val v = View(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
        lp.blurBehindRadius = 0
        return try {
            wm.addView(v, lp)
            view = v
            level = 0f
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        v.removeCallbacks(safety)
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    companion object {
        private const val SAFETY_MS = 2000L
    }
}
