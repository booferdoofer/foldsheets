package dev.foldblur

import android.content.Context
import android.content.SharedPreferences

data class BlurSettings(
    val blurPx: Int,        // peak blur radius
    val fadeInMs: Long,     // how fast the blur comes in when folding starts
    val fadeOutMs: Long,    // how long the new screen takes to come into focus
    val dim: Float,         // 0..1 extra darkening at peak, for depth
    val hingePreBlur: Boolean,
)

object Prefs {
    const val K_ENABLED = "enabled"
    const val K_BLUR = "blur"
    const val K_FADE_OUT = "fade_out"
    const val K_DIM = "dim"
    const val K_HINGE = "hinge"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun load(c: Context): BlurSettings = sp(c).let {
        BlurSettings(
            blurPx = it.getInt(K_BLUR, 70).coerceIn(0, 150),
            fadeInMs = 90L,
            fadeOutMs = it.getInt(K_FADE_OUT, 450).coerceIn(150, 600).toLong(),
            dim = it.getInt(K_DIM, 12).coerceIn(0, 40) / 100f,
            hingePreBlur = it.getBoolean(K_HINGE, true),
        )
    }

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
