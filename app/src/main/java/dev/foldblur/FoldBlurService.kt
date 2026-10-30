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
    private var panelKey = 0L
    private var settings = BlurSettings(70, 90, 320, 0.12f, true)

    /** Called once the hinge has stopped moving: bring the screen into focus. */
    private val settle = Runnable {
        if (folding) {
            Diag.log("$trail  (${System.currentTimeMillis() - gestureStartMs} ms)")
            folding = false
        }
        overlay.release()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val k = currentPanelKey()
            if (k != panelKey) {          // physical panel changed = fold handoff
                Diag.log("screen switch detected")
                panelKey = k              // (rotation alone doesn't change this)
                if (folding) {
                    trail.append(" [switch]")
                    keepBlurred(if (fineSeen) SETTLE_FINE_MID_MS else SETTLE_COARSE_MID_MS)
                } else {
                    trigger(HOLD_AFTER_SWAP_MS)
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> startListening()
                Intent.ACTION_SCREEN_OFF -> { stopListening(); overlay.dismissNow() }
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

        if (getSystemService(PowerManager::class.java).isInteractive) startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        settings = Prefs.load(this)
        return START_STICKY
    }

    override fun onDestroy() {
        Diag.log("listener stopped")
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
        folding = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val name = e.sensor.name
        if (firstSeen.add(name)) {
            Diag.log("first reading from $name: " + e.values.take(4).joinToString("/") { "%.1f".format(it) })
        }
        val isFine = fine != null && e.sensor == fine
        val isCoarse = e.sensor == hinge
        if (isFine) fineSeen = true
        if (!isFine && !isCoarse) return
        // Once the precise sensor is working, the 90°-step one is only used for the log.
        if (isCoarse && fineSeen) return

        val a = e.values[0]
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
            settings = Prefs.load(this)
            overlay.configure(settings)
        }
        if (!isFine || abs(a - trailAngle) >= 20f || a <= CLOSED_DEG || a >= OPEN_DEG) {
            if (trailAngle.toInt() != a.toInt()) trail.append("→").append(a.toInt())
            trailAngle = a
        }
        val atRest = a <= CLOSED_DEG || a >= OPEN_DEG
        keepBlurred(
            when {
                atRest -> SETTLE_AT_REST_MS            // fully open/closed: focus soon
                isFine -> SETTLE_FINE_MID_MS           // precise sensor went quiet part-way = stopped
                else -> SETTLE_COARSE_MID_MS           // 90° steps: wait for the next step
            }
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun keepBlurred(settleMs: Long) {
        overlay.hold()
        main.removeCallbacks(settle)
        main.postDelayed(settle, settleMs)
    }

    private fun trigger(holdMs: Long) {
        settings = Prefs.load(this)
        overlay.configure(settings)
        overlay.hold()
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
        private const val SETTLE_COARSE_MID_MS = 2000L // coarse sensor: hold up to 2 s for next step
        private const val HOLD_AFTER_SWAP_MS = 250L  // switch with no hinge readings at all

        fun start(c: Context) =
            c.startForegroundService(Intent(c, FoldBlurService::class.java))

        fun stop(c: Context) =
            c.stopService(Intent(c, FoldBlurService::class.java))
    }
}
