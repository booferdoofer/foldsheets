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
    private lateinit var diag: TextView
    private lateinit var exactStatus: TextView
    private var lastTest = ""
    private lateinit var snapStatus: TextView
    private var hingeEvents = 0
    private val liveValues = linkedMapOf<String, String>()
    private val liveCounts = mutableMapOf<String, Int>()
    private val blurListener = java.util.function.Consumer<Boolean> { runOnUiThread { refresh() } }
    private lateinit var sensors: SensorManager
    private var lastAngle = Float.NaN
    private val ticker = object : Runnable {
        override fun run() { updateLive(); live.postDelayed(this, 150) }
    }

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
        col.addView(text("\nScreenshot animation (blur + zoom)", 18f, bold = true))
        snapStatus = text("", 14f).also { col.addView(it) }
        col.addView(text("Turn on \"Fold Blur\" under Installed apps in Accessibility. If Android says the " +
            "setting is restricted: Settings → Apps → Fold Blur → ⋮ (top right) → Allow restricted settings, then try again.", 13f))
        col.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(toggle("Use screenshot animation when available", s.screenshotAnim) {
            Prefs.putBool(this, Prefs.K_SNAP, it)
        })
        col.addView(slider("Zoom", 0, 20, s.zoomPct, "%") { Prefs.putInt(this, Prefs.K_ZOOM, it) })
        col.addView(text("\nAngle calibration", 18f, bold = true))
        col.addView(text("Takes about 40 seconds. A voice tells you each position and the phone buzzes " +
            "when it's measuring: fully open while turning it around, then ¾ open, L shape, ¼ open, closed. " +
            "Turn your volume up.", 13f))
        col.addView(button("Calibrate angle (guided, with voice)") {
            if (!Prefs.isEnabled(this)) {
                Toast.makeText(this, "Turn on Enabled first", Toast.LENGTH_SHORT).show(); return@button
            }
            FoldBlurService.action(this, FoldBlurService.ACTION_CALIBRATE)
        })
        col.addView(button("Reset calibration") { FoldBlurService.action(this, FoldBlurService.ACTION_RESET_CAL) })
        col.addView(text("", 6f))
        col.addView(toggle("Blur follows fold position (holds when you pause)", s.followPosition) {
            Prefs.putBool(this, Prefs.K_POSITION, it)
        })
        col.addView(slider("Clear if left part-open (11 = never)", 1, 11,
            if (s.partOpenClearMs == 0L) 11 else (s.partOpenClearMs / 1000).toInt(), "s") {
            Prefs.putInt(this, Prefs.K_PARTOPEN, it)
        })
        col.addView(toggle("Smooth blur (animate blur strength)", s.smooth) {
            Prefs.putBool(this, Prefs.K_SMOOTH, it)
        })
        col.addView(slider("Blur strength", 0, 150, s.blurPx, "px") { Prefs.putInt(this, Prefs.K_BLUR, it) })
        col.addView(slider("Focus-in time", 150, 600, s.fadeOutMs.toInt(), "ms") { Prefs.putInt(this, Prefs.K_FADE_OUT, it) })
        col.addView(slider("Depth dim", 0, 40, (s.dim * 100).roundToInt(), "%") { Prefs.putInt(this, Prefs.K_DIM, it) })

        // ---- Exact hinge angle through Wireless debugging ----
        col.addView(text("\nSamsung hinge motion (Wireless debugging)", 18f, bold = true))
        exactStatus = text("", 14f).also { col.addView(it) }
        col.addView(text("1. Tap \"Start pairing\" (allow notifications).\n" +
            "2. In Developer options → Wireless debugging, turn it on, then tap \"Pair device with pairing code\".\n" +
            "3. Pull down the notification shade and type the 6-digit code into the Fold Blur notification.", 13f))
        col.addView(button("Start pairing") {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            PairingNotif.show(this, "Open the pairing-code box in Wireless debugging, then tap Enter code here.", askForCode = true)
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })
        col.addView(button("Test exact angle") {
            lastTest = "Testing… (Wireless debugging must be on)"
            ExactAngle.test(this) { r -> lastTest = r }
        })
        col.addView(button("Start / restart hinge feed") {
            ExactAngle.stop()
            Prefs.putBool(this, Prefs.K_EXACT, true)
            ExactAngle.start(this)
        })
        col.addView(button("Preview transition") { preview() })
        col.addView(button("Test: hold layer for 2 seconds") {
            if (!Settings.canDrawOverlays(this)) return@button
            val cur = Prefs.load(this)
            // force a clearly visible tint so we can tell if the layer appears at all
            overlay.configure(cur.copy(dim = maxOf(cur.dim, 0.35f), blurPx = maxOf(cur.blurPx, 80)))
            overlay.test(2000)
        })
        col.addView(button("Record fold test (10 s)") {
            if (!Prefs.isEnabled(this)) {
                Toast.makeText(this, "Turn on Enabled first", Toast.LENGTH_SHORT).show()
                return@button
            }
            FoldBlurService.record(this)
            Toast.makeText(this, "Recording 10 s: close slowly, open slowly, close fast, open fast", Toast.LENGTH_LONG).show()
        })
        col.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(text("\nEvent log", 16f, bold = true))
        diag = text("", 12f).also { col.addView(it) }
        diag.setTypeface(Typeface.MONOSPACE)
        diag.setTextIsSelectable(true)
        col.addView(button("Copy log") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Fold Blur log", Diag.text()))
            Toast.makeText(this, "Log copied, paste it in the chat", Toast.LENGTH_SHORT).show()
        }, col.indexOfChild(diag))
        Diag.listener = { runOnUiThread { diag.text = Diag.text() } }
        if (savedInstanceState == null) {
            Diag.log("DISCOVERY\n" + runCatching { Discovery.run(this) }.getOrElse { "failed: $it" })
            Diag.log(SamsungBlur.probe())
            val hs = sensors.getSensorList(Sensor.TYPE_ALL).filter { s ->
                listOf("hinge", "fold", "angle", "hall").any { s.name.contains(it, true) || s.stringType.contains(it, true) }
            }
            Diag.log("all sensors: " + sensors.getSensorList(Sensor.TYPE_ALL).joinToString(", ") { it.name })
            Diag.log("hinge-type sensors: " + hs.joinToString("; ") { "${it.name} (${it.stringType}, step ${it.resolution}, ${it.minDelay}us)" })
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        sensors.getSensorList(Sensor.TYPE_ALL).filter { s ->
            s.type == Sensor.TYPE_HINGE_ANGLE ||
                listOf("hinge", "fold", "angle").any { s.name.contains(it, true) || s.stringType.contains(it, true) }
        }.forEach {
            val ok = sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            liveValues.putIfAbsent(it.name, if (ok) "no readings yet" else "REFUSED")
        }
        if (Prefs.isEnabled(this) && Settings.canDrawOverlays(this)) FoldBlurService.start(this)
        getSystemService(WindowManager::class.java).addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
        diag.text = Diag.text()
        live.post(ticker)
    }

    override fun onPause() {
        live.removeCallbacks(ticker)
        sensors.unregisterListener(this)
        getSystemService(WindowManager::class.java).removeCrossWindowBlurEnabledListener(blurListener)
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_HINGE_ANGLE) { lastAngle = e.values[0]; hingeEvents++ }
        val n = (liveCounts[e.sensor.name] ?: 0) + 1
        liveCounts[e.sensor.name] = n
        liveValues[e.sensor.name] = e.values.take(3).joinToString(" / ") { "%.1f".format(it) } + "  ($n)"
        updateLive()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun preview() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" first", Toast.LENGTH_SHORT).show()
            return
        }
        overlay.configure(Prefs.load(this))
        Diag.log("preview tapped")
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

    private fun refreshExact() {
        if (::snapStatus.isInitialized) {
            snapStatus.text = if (SnapService.instance != null)
                "Accessibility: on ✓" + (if (Live.snapMs > 0) "   last screenshot took ${Live.snapMs} ms" else "")
            else "Accessibility: off (using live blur instead)"
        }
        if (!::exactStatus.isInitialized) return
        val paired = Prefs.isPaired(this)
        val a = ExactAngle.angle
        exactStatus.text = "Paired: ${if (paired) "yes" else "no"}   Feed: ${ExactAngle.status}" +
            (if (lastTest.isNotEmpty()) "\nTest: $lastTest" else "")
    }

    private fun updateLive() {
        if (!::live.isInitialized) return
        val m = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val angle = if (lastAngle.isNaN()) "—" else "${lastAngle.roundToInt()}°"
        refreshExact()
        val est = Live.estAngle
        live.text = "Estimated angle: ${if (est.isNaN()) "— (turn on Enabled)" else "~${est.roundToInt()}°"}  ${Live.note}\n" +
            (if (Live.note2.isNotEmpty()) "Now: ${Live.note2}\n" else "") +
            "Hinge: $angle ($hingeEvents updates)    Panel: ${m?.physicalWidth}×${m?.physicalHeight}\n" +
            liveValues.entries.joinToString("\n") { "• ${it.key}: ${it.value}" } + "\n"
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
