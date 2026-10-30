package dev.foldblur

import android.content.Context
import android.content.SharedPreferences

data class BlurSettings(
    val blurPx: Int,        // peak blur radius
    val fadeInMs: Long,     // how fast the blur comes in when folding starts
    val fadeOutMs: Long,    // how long the new screen takes to come into focus
    val dim: Float,         // 0..1 extra darkening at peak, for depth
    val hingePreBlur: Boolean,
    val smooth: Boolean = true,
    val followPosition: Boolean = true,
    val partOpenClearMs: Long = 5000,
    val screenshotAnim: Boolean = true,
    val zoomPct: Int = 8,
)

object Prefs {
    const val K_ENABLED = "enabled"
    const val K_BLUR = "blur"
    const val K_FADE_OUT = "fade_out"
    const val K_DIM = "dim"
    const val K_HINGE = "hinge"
    const val K_SMOOTH = "smooth"
    const val K_POSITION = "follow_position"
    const val K_PARTOPEN = "partopen_s"
    const val K_SNAP = "screenshot_anim"
    const val K_ZOOM = "zoom_pct"
    const val K_PAIRED = "adb_paired"
    const val K_EXACT = "exact_enabled"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun load(c: Context): BlurSettings = sp(c).let {
        BlurSettings(
            blurPx = it.getInt(K_BLUR, 70).coerceIn(0, 150),
            fadeInMs = 90L,
            fadeOutMs = it.getInt(K_FADE_OUT, 450).coerceIn(150, 600).toLong(),
            dim = it.getInt(K_DIM, 12).coerceIn(0, 40) / 100f,
            hingePreBlur = it.getBoolean(K_HINGE, true),
            smooth = it.getBoolean(K_SMOOTH, false),
            followPosition = it.getBoolean(K_POSITION, true),
            partOpenClearMs = it.getInt(K_PARTOPEN, 5).let { s -> if (s >= 11) 0L else s * 1000L },
            screenshotAnim = it.getBoolean(K_SNAP, true),
            zoomPct = it.getInt(K_ZOOM, 8).coerceIn(0, 20),
        )
    }

    fun isPaired(c: Context) = sp(c).getBoolean(K_PAIRED, false)
    fun exactEnabled(c: Context) = sp(c).getBoolean(K_EXACT, true)
    fun isEnabled(c: Context) = sp(c).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(K_ENABLED, v).apply()
    fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

/** On-screen event log so problems are visible without a computer. */
object Diag {
    private val lines = ArrayDeque<String>()
    var listener: (() -> Unit)? = null
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun log(s: String) {
        lines.addFirst("${fmt.format(java.util.Date())}  $s")
        while (lines.size > 25) lines.removeLast()
        listener?.invoke()
    }

    fun text() = lines.joinToString("\n")
}

/** Live values the service publishes for the app screen. */
object Live {
    @Volatile var estAngle = Float.NaN
    @Volatile var note = ""
    @Volatile var note2 = ""
    @Volatile var snapMs = 0L
}
