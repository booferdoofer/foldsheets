package dev.foldblur

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Controls + a diagnostics readout for testing on the device. */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var overlay: BlurOverlay
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var sensors: SensorManager
    private var lastAngle = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlay = BlurOverlay(this)
        sensors = getSystemService(SensorManager::class.java)

        val pad = dp(20)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { _, ins ->
            val s = ins.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(pad + s.left, pad + s.top, pad + s.right, pad + s.bottom)
            ins
        }
        setContentView(scroll)

        col.addView(text("Fold Blur", 26f, bold = true))
        status = text("", 14f).also { col.addView(it) }
        live = text("", 14f).also { col.addView(it) }

        col.addView(button("Allow \"Display over other apps\"") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("Battery → set to Unrestricted") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        val s = Prefs.load(this)
        col.addView(toggle("Enabled", Prefs.isEnabled(this)) { on ->
            if (on && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow \"Display over other apps\" first", Toast.LENGTH_SHORT).show()
            }
            Prefs.setEnabled(this, on)
            if (on && Settings.canDrawOverlays(this)) FoldBlurService.start(this) else FoldBlurService.stop(this)
            refresh()
        })
        col.addView(toggle("Start blurring while the hinge moves", s.hingePreBlur) {
            Prefs.putBool(this, Prefs.K_HINGE, it)
        })
        col.addView(slider("Blur strength", 0, 150, s.blurPx, "px") { Prefs.putInt(this, Prefs.K_BLUR, it) })
        col.addView(slider("Focus-in time", 150, 600, s.fadeOutMs.toInt(), "ms") { Prefs.putInt(this, Prefs.K_FADE_OUT, it) })
        col.addView(slider("Depth dim", 0, 40, (s.dim * 100).roundToInt(), "%") { Prefs.putInt(this, Prefs.K_DIM, it) })

        col.addView(button("Preview transition") { preview() })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (Prefs.isEnabled(this) && Settings.canDrawOverlays(this)) FoldBlurService.start(this)
        updateLive()
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) { lastAngle = e.values[0]; updateLive() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun preview() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" first", Toast.LENGTH_SHORT).show()
            return
        }
        overlay.configure(Prefs.load(this))
        overlay.hold()
        window.decorView.postDelayed({ overlay.release() }, 220)
    }

    private fun refresh() {
        val wm = getSystemService(WindowManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        status.text = buildString {
            appendLine()
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} Display over other apps")
            appendLine("${mark(wm.isCrossWindowBlurEnabled)} System blur available (off in Power saving)")
            appendLine("${mark(sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null)} Hinge sensor")
            appendLine("${mark(pm.isIgnoringBatteryOptimizations(packageName))} Battery unrestricted")
        }
    }

    private fun updateLive() {
        val m = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val angle = if (lastAngle.isNaN()) "—" else "${lastAngle.roundToInt()}°"
        live.text = "Hinge: $angle    Panel: ${m?.physicalWidth}×${m?.physicalHeight}\n"
    }

    // --- tiny view helpers (no extra libraries) ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = initial; textSize = 16f
        setPadding(0, dp(12), 0, dp(12))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun slider(label: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val t = text("$label: $initial $unit", 16f)
            addView(t)
            addView(SeekBar(this@MainActivity).apply {
                this.min = min; this.max = max; progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        t.text = "$label: $v $unit"
                        if (fromUser) onChange(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }
}
