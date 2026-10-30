package dev.foldblur

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Guided calibration with spoken prompts and vibrations, so it works even while the
 * phone is folded and the inner screen is off. Runs inside the service.
 */
class CalibrationRun(
    private val ctx: Context,
    private val cal: FoldCal,
    private val onFinished: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val vib = ctx.getSystemService(Vibrator::class.java)

    private val prompts = listOf(
        "Hold the phone fully open, and slowly turn it around in different directions.",
        "Fold it to about three quarters open, and hold still.",
        "Fold it to an L shape, a right angle, and hold still.",
        "Fold it to about a quarter open, and hold still.",
        "Close the phone fully, and hold still.",
    )
    private val captureMs = longArrayOf(7000, 2000, 2000, 2000, 2000)

    var active = false; private set
    var capturing = false; private set
    private var step = 0
    private val ms = ArrayList<FloatArray>()
    private val rs = ArrayList<FloatArray>()
    private val pts = arrayOfNulls<FloatArray>(5)

    fun start() {
        if (active) return
        active = true
        step = 0
        Live.note = "calibrating…"
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
            main.post { beginStep() }
        }
    }

    fun cancel() {
        active = false
        capturing = false
        main.removeCallbacksAndMessages(null)
        tts?.shutdown(); tts = null
    }

    /** Feed every compass reading here while calibrating. */
    fun onMag(m: FloatArray) {
        if (!capturing || !cal.rotValid) return
        ms.add(m.copyOf())
        rs.add(cal.rot.copyOf())
    }

    private fun say(text: String) {
        Diag.log("calibration: $text")
        Live.note = text
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cal$step")
    }

    private fun buzz(ms: Long) {
        runCatching { vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun beginStep() {
        if (!active) return
        say("Step ${step + 1} of 5. ${prompts[step]}")
        // time to get into position, then capture
        main.postDelayed({
            if (!active) return@postDelayed
            ms.clear(); rs.clear()
            capturing = true
            buzz(80)
            main.postDelayed({ endStep() }, captureMs[step])
        }, if (step == 0) 4500 else 4000)
    }

    private fun endStep() {
        capturing = false
        buzz(40)
        if (step == 0) {
            val r = cal.solveOpen(ms, rs)
            if (r == null) {
                say("Not enough turning. Let's try that again, turn it in more directions.")
                main.postDelayed({ beginStep() }, 3500)
                return
            }
            pts[0] = r.first
            cal.setEarth(r.second)
        } else {
            if (ms.size < 10) {
                say("I didn't get enough readings. Let's repeat this step.")
                main.postDelayed({ beginStep() }, 3000)
                return
            }
            val sum = FloatArray(3)
            for (k in ms.indices) {
                cal.rot.let { rs[k].copyInto(it) }
                val f = cal.foldField(ms[k])
                for (i in 0..2) sum[i] += f[i]
            }
            pts[step] = FloatArray(3) { sum[it] / ms.size }
        }
        step++
        if (step < 5) {
            beginStep()
        } else {
            cal.save(Array(5) { pts[it]!! })
            say("Calibration done. You can open the phone now.")
            Diag.log("calibration points: " + pts.joinToString(" | ") { p -> p!!.joinToString(",") { "%.0f".format(it) } })
            main.postDelayed({ cancel(); onFinished() }, 4000)
        }
    }
}
