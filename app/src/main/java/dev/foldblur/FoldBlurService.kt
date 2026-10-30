package dev.foldblur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import kotlin.math.abs

/**
 * Event-driven listener. It does no polling or timers while idle. It reacts to:
 *  1. hinge movement (blur starts while you fold, *before* the screen switches),
 *  2. the cover <-> inner display handoff (blur clears as the new screen comes in).
 * Everything is unregistered while the screen is off.
 */
class FoldBlurService : Service(), SensorEventListener {

    private lateinit var overlay: BlurOverlay
    private lateinit var sensors: SensorManager
    private lateinit var displays: DisplayManager
    private var hinge: Sensor? = null
    private val main = Handler(Looper.getMainLooper())

    private var listening = false
    private var anchorAngle = Float.NaN
    private var folding = false              // a fold/unfold gesture is in progress
    private var gestureStartMs = 0L
    private val trail = StringBuilder()      // hinge readings during this gesture, for the log
    private val extraSensors = mutableListOf<Sensor>()
    private var fine: Sensor? = null         // Samsung "Folding Angle" (0.01° steps), if present
    private var fineSeen = false             // it has actually delivered readings
    private var trailAngle = Float.NaN
    private val firstSeen = mutableSetOf<String>()

    // Gyroscope: only switched on while the hinge reads "in between" (90°), to tell
    // whether the phone is still being folded or has stopped.
    private var gyro: Sensor? = null
    private var gyroOn = false
    private var midSinceMs = 0L
    private var lastGyroActiveMs = 0L
    private var gyroPeak = 0f

    private val midWatch = Runnable { checkMid() }

    // Position mode: blur strength follows how far the phone is from fully open/closed.
    private val positionMode get() = settings.followPosition
    private var lastMovingMs = 0L
    private fun blurHold() { if (!positionMode) overlay.hold() }
    private fun blurRelease() { if (!positionMode) overlay.release() }

    private var lastCfgMs = 0L
    private lateinit var cal: FoldCal
    private var calRun: CalibrationRun? = null
    private var rv: Sensor? = null
    private val calibrating get() = calRun?.active == true

    private fun updatePosition() {
        val nowCfg = SystemClock.uptimeMillis()
        if (nowCfg - lastCfgMs > 1500) {           // pick up setting changes from the app
            lastCfgMs = nowCfg
            settings = Prefs.load(this)
            overlay.configure(settings)
        }
        if (calibrating) { overlay.setTarget(0f); return }
        if (!positionMode || recording || !listening) return
        if (!settings.hingePreBlur) { overlay.setTarget(0f); return }
        val now = SystemClock.uptimeMillis()
        val hingeMoving = feedLive && now - lastHingeMotionMs < 300
        val c = coarseAngle
        // Without Samsung's hinge feed, the 90° reading is the only reliable sign of a fold in progress.
        val moving = hingeMoving || (!feedLive && c > CLOSED_DEG && c < OPEN_DEG &&
            now - maxOf(lastMagActiveMs, lastGyroActiveMs) < 300)
        if (moving) lastMovingMs = now
        val restingOpenOrShut = (c >= OPEN_DEG || c <= CLOSED_DEG) && !hingeMoving && now - lastMovingMs > 250
        val a = if (estAngle.isNaN()) c else estAngle
        var t = if (restingOpenOrShut) 0f else {
            val fromRest = (minOf(a, 180f - a) / RAMP_DEG).coerceIn(0f, 1f)
            var x = fromRest * fromRest * (3f - 2f * fromRest)          // smoothstep
            if (c > CLOSED_DEG && c < OPEN_DEG) x = maxOf(x, 0.4f)      // definitely part-way
            if (hingeMoving) x = maxOf(x, 0.25f)                        // folding has started
            x
        }
        val clearMs = settings.partOpenClearMs
        if (clearMs > 0 && t > 0f && now - lastMovingMs > clearMs) t = 0f   // left part-open (e.g. Flex mode)
        overlay.setTarget(t)
        Live.note2 = "blur ${(t * 100).toInt()}%${if (hingeMoving) ", hinge moving" else ""}"
    }

    // Samsung hinge motion, from the timestamps of its Folding Angle readings (values are hidden).
    private var lastHingeTs = 0.0
    private var lastHingeMotionMs = 0L
    private val feedLive get() = ExactAngle.isLive

    // Fold-test recorder: logs the compass (magnetometer) against the hinge so we can
    // see whether the magnets in the other half give a usable exact angle.
    private var mag: Sensor? = null
    private var recording = false
    private var recStartMs = 0L
    private val rec = StringBuilder()
    private val lastMag = FloatArray(3)
    private var acc: Sensor? = null
    private var subAcc: Sensor? = null
    private var subGyro: Sensor? = null
    private val lastAcc = FloatArray(3)
    private val lastSubAcc = FloatArray(3)
    private val lastGyro = FloatArray(3)
    private val lastSubGyro = FloatArray(3)
    private val recCounts = IntArray(5)

    // Compass-based fold estimate: the magnets in the other half shift the magnetometer
    // reading as the phone folds. We learn what "open" and "closed" look like and
    // place the current reading between them.
    private val magNow = FloatArray(3)
    private val magHistT = LongArray(16)
    private val magHist = Array(16) { FloatArray(3) }
    private var magHistN = 0
    private var magOpen: FloatArray? = null
    private var magClosed: FloatArray? = null
    private var lastMagActiveMs = 0L
    private var coarseAngle = 180f
    private var estAngle = Float.NaN
    private var trailEst = Float.NaN
    private val recSample = Runnable { sampleRec() }
    private val recStop = Runnable { stopRec() }
    private var panelKey = 0L
    private var settings = BlurSettings(70, 90, 320, 0.12f, true)

    /** Called once the hinge has stopped moving: bring the screen into focus. */
    private val settle = Runnable { finishGesture() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val k = currentPanelKey()
            if (k != panelKey) {          // physical panel changed = fold handoff
                val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                Diag.log("screen switch detected${if (locked) " (LOCK SCREEN showing: overlays hidden)" else ""}")
                panelKey = k              // (rotation alone doesn't change this)
                if (folding) {
                    trail.append(" [switch]")
                    if (feedLive) blurHold()       // the hinge-motion watch decides when to clear
                    else if (fineSeen) keepBlurred(SETTLE_FINE_MID_MS)
                    else if (!gyroOn) keepBlurred(HOLD_AFTER_SWAP_MS)
                    // else: the gyro watch is already deciding when the fold has stopped
                } else {
                    trigger(HOLD_AFTER_SWAP_MS)
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> { startListening(); startExactIfPaired() }
                Intent.ACTION_SCREEN_OFF -> { stopListening(); overlay.dismissNow(); ExactAngle.stop() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        Diag.log("listener started")
        overlay = BlurOverlay(this)
        sensors = getSystemService(SensorManager::class.java)
        displays = getSystemService(DisplayManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rv = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        cal = FoldCal(this)
        mag = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val all = sensors.getSensorList(Sensor.TYPE_ALL)
        subAcc = all.firstOrNull { it.name.contains("Accelerometer-Sub", true) && !it.name.contains("Uncal", true) }
        subGyro = all.firstOrNull { it.name.contains("Gyroscope-Sub", true) && !it.name.contains("Uncal", true) }
        // Any other sensor that might report hinge/fold position (Samsung vendor sensors).
        sensors.getSensorList(Sensor.TYPE_ALL).filter { s ->
            s != hinge && listOf("hinge", "fold", "angle", "hall").any {
                s.name.contains(it, true) || s.stringType.contains(it, true)
            }
        }.let { extraSensors.addAll(it) }
        fine = extraSensors.firstOrNull { it.stringType == "com.samsung.sensor.folding_angle" }
            ?: extraSensors.firstOrNull { it.name.contains("folding angle", true) }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, f)

        ExactAngle.onAngle = { onExact(it) }
        ExactAngle.onEvents = { onHingeEvents(it) }
        if (getSystemService(PowerManager::class.java).isInteractive) {
            startListening()
            startExactIfPaired()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        settings = Prefs.load(this)
        if (intent?.action == ACTION_RECORD) startRec()
        if (intent?.action == ACTION_CALIBRATE && calRun?.active != true) {
            overlay.dismissNow()
            calRun = CalibrationRun(this, cal) { calRun = null }.also { it.start() }
        }
        if (intent?.action == ACTION_RESET_CAL) { cal.clear(); Diag.log("calibration cleared") }
        return START_STICKY
    }

    private fun startExactIfPaired() {
        if (Prefs.isPaired(this) && Prefs.exactEnabled(this)) ExactAngle.start(this)
    }

    override fun onDestroy() {
        Diag.log("listener stopped")
        ExactAngle.stop()
        ExactAngle.onAngle = null
        ExactAngle.onEvents = null
        stopListening()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        overlay.dismissNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (listening) return
        listening = true
        settings = Prefs.load(this)
        panelKey = currentPanelKey()
        anchorAngle = Float.NaN
        displays.registerDisplayListener(displayListener, main)
        mag?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, main) }
        rv?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, main) }
        val results = (listOfNotNull(hinge) + extraSensors).map {
            val ok = sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, main)
            "${it.name.take(16)}=${if (ok) "ok" else "REFUSED"}"
        }
        Diag.log("sensors: " + results.joinToString(", "))
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        displays.unregisterDisplayListener(displayListener)
        sensors.unregisterListener(this)
        main.removeCallbacks(settle)
        main.removeCallbacks(midWatch)
        stopGyro()
        cal.rotValid = false
        cal.resetEarth()
        calRun?.cancel(); calRun = null
        if (recording) stopRec()
        folding = false
    }

    private fun finishGesture() {
        if (folding) {
            val ms = System.currentTimeMillis() - gestureStartMs
            val gyroNote = if (gyroPeak > 0f) ", gyro peak " + String.format("%.1f", gyroPeak) else ""
            Diag.log("$trail  ($ms ms$gyroNote)")
            gyroPeak = 0f
            stopGyro()
            main.removeCallbacks(midWatch)
            folding = false
        }
        blurRelease()
    }

    private fun checkMid() {
        if (!folding) return
        val now = SystemClock.uptimeMillis()
        val inMid = now - midSinceMs
        val live = feedLive
        val quiet = if (live) now - lastHingeMotionMs else now - maxOf(lastGyroActiveMs, lastMagActiveMs)
        val minHold = if (live) MIN_HOLD_LIVE_MS else MIN_MID_MS
        val quietNeeded = if (live) HINGE_QUIET_MS + ExactAngle.pollMs else GYRO_QUIET_MS
        if ((inMid >= minHold && quiet >= quietNeeded) || inMid >= MAX_MID_MS) {
            trail.append(if (inMid >= MAX_MID_MS) " [max]" else " [stopped]")
            stopGyro()
            main.removeCallbacks(settle)
            finishGesture()
        } else {
            blurHold()
            main.postDelayed(midWatch, 50)
        }
    }

    private fun onHingeEvents(ts: DoubleArray) {
        if (!listening || recording || ts.isEmpty()) return
        val newest = ts.max()
        if (newest <= lastHingeTs) return
        lastHingeTs = newest
        // Real movement = a burst of readings (~every 40 ms); a single reading is just jitter.
        val burst = ts.count { newest - it <= 0.25 }
        if (burst < 3) return
        val nowS = SystemClock.elapsedRealtimeNanos() / 1e9
        val ageMs = ((nowS - newest) * 1000).toLong().coerceAtLeast(0)
        if (ageMs > 600) return
        lastHingeMotionMs = SystemClock.uptimeMillis() - ageMs
        lastMovingMs = lastHingeMotionMs
        updatePosition()
        if (!settings.hingePreBlur) return
        if (!folding) {
            folding = true
            gestureStartMs = System.currentTimeMillis()
            trail.setLength(0)
            trail.append("hinge moving (${ageMs} ms late) from ").append(coarseAngle.toInt())
            trailAngle = coarseAngle
            trailEst = Float.NaN
            settings = Prefs.load(this)
            overlay.configure(settings)
            midSinceMs = SystemClock.uptimeMillis()
        }
        blurHold()
        main.removeCallbacks(settle)
        main.removeCallbacks(midWatch)
        main.postDelayed(midWatch, 50)
    }

    private fun startRec() {
        if (recording) return
        recording = true
        overlay.dismissNow()
        stopGyro()
        recCounts.fill(0)
        rec.setLength(0)
        val list = listOf("acc" to acc, "subAcc" to subAcc, "gyro" to gyro, "subGyro" to subGyro, "mag" to mag)
        val reg = list.joinToString(", ") { (k, sn) ->
            if (sn == null) "$k=missing"
            else "$k=${if (sensors.registerListener(this, sn, SensorManager.SENSOR_DELAY_GAME, main)) "ok" else "REFUSED"}(${sn.name.take(30)}, type ${sn.type})"
        }
        rec.append("FOLD TEST v2: $reg\n")
        rec.append("ms hinge | acc x y z | subAcc x y z | gyro x y z | subGyro x y z | mag x y z\n")
        recStartMs = SystemClock.uptimeMillis()
        Diag.log("fold test recording for 10 s…")
        main.postDelayed(recSample, 300)
        main.postDelayed(recStop, 10_000)
    }

    private fun f1(a: FloatArray) = a.joinToString(" ") { String.format("%.1f", it) }
    private fun f2(a: FloatArray) = a.joinToString(" ") { String.format("%.2f", it) }

    private fun sampleRec() {
        if (!recording) return
        val h = if (anchorAngle.isNaN()) "?" else anchorAngle.toInt().toString()
        rec.append("${SystemClock.uptimeMillis() - recStartMs} $h | ${f1(lastAcc)} | ${f1(lastSubAcc)} | " +
            "${f2(lastGyro)} | ${f2(lastSubGyro)} | ${lastMag.joinToString(" ") { it.toInt().toString() }}\n")
        main.postDelayed(recSample, 50)
    }

    private fun stopRec() {
        if (!recording) return
        recording = false
        main.removeCallbacks(recSample)
        listOfNotNull(acc, subAcc, subGyro).forEach { sensors.unregisterListener(this, it) }
        gyro?.let { sensors.unregisterListener(this, it) }
        rec.append("events: acc=${recCounts[0]} subAcc=${recCounts[1]} gyro=${recCounts[2]} subGyro=${recCounts[3]} mag=${recCounts[4]}")
        Diag.log(rec.toString())
    }

    private fun onMag(v: FloatArray) {
        val now = SystemClock.uptimeMillis()
        magNow[0] = v[0]; magNow[1] = v[1]; magNow[2] = v[2]
        // how fast the field is changing over the last ~200 ms
        val i = magHistN % magHist.size
        magHistT[i] = now; v.copyInto(magHist[i], 0, 0, 3); magHistN++
        var speed = 0f
        for (k in 1 until minOf(magHistN, magHist.size)) {
            val j = ((magHistN - 1 - k) % magHist.size + magHist.size) % magHist.size
            val dt = now - magHistT[j]
            if (dt >= 180) { speed = dist(magNow, magHist[j]) * 1000f / dt; break }
        }
        if (speed >= MAG_MOVING_PER_S) lastMagActiveMs = now
        val steady = now - lastMagActiveMs > 600

        // learn the open / closed fingerprints while the phone sits still in that state
        if (steady && coarseAngle >= OPEN_DEG) magOpen = ema(magOpen, magNow)
        if (steady && coarseAngle <= CLOSED_DEG) magClosed = ema(magClosed, magNow)

        calRun?.let { if (it.active) { it.onMag(magNow); return } }

        val o = magOpen
        val calAngle = if (cal.isCalibrated) {
            if (!folding && now - lastMovingMs > 800) cal.learnEarth(magNow, coarseAngle)
            cal.angle(magNow).let { a ->
                if (a.isNaN()) a else when {
                    coarseAngle >= OPEN_DEG -> a.coerceAtLeast(130f)
                    coarseAngle <= CLOSED_DEG -> a.coerceAtMost(50f)
                    else -> a.coerceIn(5f, 175f)
                }
            }
        } else Float.NaN
        estAngle = when {
            coarseAngle >= OPEN_DEG && !folding -> 180f
            coarseAngle <= CLOSED_DEG && !folding -> 0f
            !calAngle.isNaN() -> if (estAngle.isNaN()) calAngle else estAngle + 0.35f * (calAngle - estAngle)
            o == null -> coarseAngle
            else -> {
                val span = magClosed?.let { dist(it, o) }?.coerceAtLeast(30f) ?: 85f
                val frac = (dist(magNow, o) / span).coerceIn(0f, 1f)
                (180f * (1f - Math.pow(frac.toDouble(), 1.5).toFloat())).coerceIn(0f, 180f)
            }
        }
        Live.estAngle = estAngle
        updatePosition()
        Live.note = when {
            cal.isCalibrated && cal.earthValid -> "your calibration, tilt-compensated"
            cal.isCalibrated -> "your calibration (open the phone flat for a moment)"
            o == null -> "auto: learning open position"
            magClosed == null -> "auto: learning closed position"
            else -> "auto-calibrated (tap Calibrate for better)"
        }
        if (folding && (trailEst.isNaN() || abs(estAngle - trailEst) >= 25f)) {
            trail.append(" ~").append(estAngle.toInt())
            trailEst = estAngle
        }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]
        return kotlin.math.sqrt(x * x + y * y + z * z)
    }

    private fun ema(old: FloatArray?, v: FloatArray): FloatArray {
        if (old == null) return v.copyOf()
        for (k in 0..2) old[k] += 0.05f * (v[k] - old[k])
        return old
    }

    private fun startGyro() {
        if (gyroOn) return
        val g = gyro ?: return
        gyroOn = sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME, main)
    }

    private fun stopGyro() {
        if (!gyroOn) return
        gyroOn = false
        val g = gyro ?: return
        sensors.unregisterListener(this, g)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor == rv) {
            SensorManager.getRotationMatrixFromVector(cal.rot, e.values)
            cal.rotValid = true
            return
        }
        if (recording) {
            val target = when (e.sensor) {
                mag -> { recCounts[4]++; lastMag }
                acc -> { recCounts[0]++; lastAcc }
                subAcc -> { recCounts[1]++; lastSubAcc }
                gyro -> { recCounts[2]++; lastGyro }
                subGyro -> { recCounts[3]++; lastSubGyro }
                else -> null
            }
            if (target != null) {
                target[0] = e.values[0]; target[1] = e.values[1]; target[2] = e.values[2]
                return
            }
        }
        if (e.sensor == mag) { onMag(e.values); return }
        if (e.sensor == hinge) { coarseAngle = e.values[0]; updatePosition() }
        if (recording && e.sensor == hinge) { anchorAngle = e.values[0]; return }
        if (e.sensor == gyro) {
            val v = e.values
            val speed = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])  // rad/s
            if (speed > gyroPeak) gyroPeak = speed
            if (speed >= GYRO_MOVING_RAD_S) lastGyroActiveMs = SystemClock.uptimeMillis()
            return
        }
        val name = e.sensor.name
        if (firstSeen.add(name)) {
            Diag.log("first reading from $name: " + e.values.take(4).joinToString("/") { "%.1f".format(it) })
        }
        val isFine = fine != null && e.sensor == fine
        val isCoarse = e.sensor == hinge
        if (isFine) fineSeen = true
        if (!isFine && !isCoarse) return
        // Once the precise sensor is working, the 90°-step one is only used for the log.
        if (isCoarse && ExactAngle.isLive) {
            if (folding) trail.append(" [${e.values[0].toInt()}]")
            return
        }
        if (isCoarse && fineSeen) return
        handleAngle(e.values[0], isFine)
    }

    /** Exact angle from Wireless debugging (dumpsys), delivered on the main thread. */
    private fun onExact(a: Float) {
        if (!listening || recording) return
        coarseAngle = a
        handleAngle(a, true)
    }

    private fun handleAngle(a: Float, isFine: Boolean) {
        if (anchorAngle.isNaN()) { anchorAngle = a; return }
        if (abs(a - anchorAngle) < (if (isFine) FINE_THRESHOLD_DEG else 1f)) return
        val prev = anchorAngle
        anchorAngle = a
        if (!settings.hingePreBlur) return

        if (!folding) {
            folding = true
            gestureStartMs = System.currentTimeMillis()
            trail.setLength(0)
            trail.append(if (isFine) "angle " else "hinge ").append(prev.toInt())
            trailAngle = prev
            trailEst = Float.NaN
            settings = Prefs.load(this)
            overlay.configure(settings)
        }
        if (!isFine || abs(a - trailAngle) >= 20f || a <= CLOSED_DEG || a >= OPEN_DEG) {
            if (trailAngle.toInt() != a.toInt()) trail.append("→").append(a.toInt())
            trailAngle = a
        }
        val atRest = a <= CLOSED_DEG || a >= OPEN_DEG
        when {
            feedLive -> {                              // Samsung hinge motion decides when it stops
                if (midSinceMs == 0L || !folding) midSinceMs = SystemClock.uptimeMillis()
                blurHold()
                main.removeCallbacks(settle)
                main.removeCallbacks(midWatch)
                main.postDelayed(midWatch, 50)
            }
            atRest -> {                                // fully open/closed: focus soon
                stopGyro()
                main.removeCallbacks(midWatch)
                keepBlurred(SETTLE_AT_REST_MS)
            }
            isFine -> keepBlurred(SETTLE_FINE_MID_MS)  // precise sensor went quiet part-way = stopped
            else -> {                                  // "somewhere in between": watch the motion
                val now = SystemClock.uptimeMillis()
                midSinceMs = now
                lastGyroActiveMs = now
                startGyro()
                blurHold()
                main.removeCallbacks(settle)
                main.removeCallbacks(midWatch)
                main.postDelayed(midWatch, 50)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun keepBlurred(settleMs: Long) {
        blurHold()
        main.removeCallbacks(settle)
        main.postDelayed(settle, settleMs)
    }

    private fun trigger(holdMs: Long) {
        settings = Prefs.load(this)
        overlay.configure(settings)
        blurHold()
        main.removeCallbacks(settle)
        main.postDelayed(settle, holdMs)
    }

    private fun currentPanelKey(): Long {
        val m = displays.getDisplay(Display.DEFAULT_DISPLAY)?.mode ?: return 0L
        return m.physicalWidth.toLong() * 100_000L + m.physicalHeight
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Fold Blur", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Fold Blur is active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "foldblur"
        private const val NOTIF_ID = 1
        private const val MOVE_THRESHOLD_DEG = 1f    // react to any real movement
        private const val CLOSED_DEG = 10f           // at or below = fully closed
        private const val OPEN_DEG = 170f            // at or above = fully open
        private const val SETTLE_AT_REST_MS = 200L   // after reaching open/closed, then focus
        private const val FINE_THRESHOLD_DEG = 1.5f  // ignore jitter on the precise sensor
        private const val SETTLE_FINE_MID_MS = 400L  // precise sensor still for this long = stopped
        private const val GYRO_MOVING_RAD_S = 0.5f   // faster than this = still folding
        private const val MAG_MOVING_PER_S = 25f      // compass change rate that means "still folding"
        private const val GYRO_QUIET_MS = 250L       // no motion for this long = stopped
        private const val RAMP_DEG = 45f             // blur reaches full this far from open/closed
        private const val HINGE_QUIET_MS = 120L      // no Samsung hinge readings for this long = stopped
        private const val MIN_HOLD_LIVE_MS = 250L
        private const val MIN_MID_MS = 600L          // always hold at least this long part-way
        private const val MAX_MID_MS = 3000L         // never hold longer than this part-way
        private const val HOLD_AFTER_SWAP_MS = 250L  // switch with no hinge readings at all

        const val ACTION_RECORD = "dev.foldblur.RECORD"
        const val ACTION_CALIBRATE = "dev.foldblur.CALIBRATE"
        const val ACTION_RESET_CAL = "dev.foldblur.RESET_CAL"

        fun action(c: Context, a: String) =
            c.startForegroundService(Intent(c, FoldBlurService::class.java).setAction(a))

        fun record(c: Context) =
            c.startForegroundService(Intent(c, FoldBlurService::class.java).setAction(ACTION_RECORD))

        fun start(c: Context) =
            c.startForegroundService(Intent(c, FoldBlurService::class.java))

        fun stop(c: Context) =
            c.stopService(Intent(c, FoldBlurService::class.java))
    }
}
