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
                    keepBlurred(SETTLE_MID_MS)   // still folding: keep holding
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
        hinge?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, main) }
        extraSensors.forEach { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, main) }
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
        if (e.sensor != hinge) {
            // Vendor sensor: just record what it says during a gesture so we can learn from it.
            if (folding) trail.append(" {${e.sensor.name.take(14)}:${e.values.take(3).joinToString("/") { it.toInt().toString() }}}")
            return
        }
        val a = e.values[0]
        if (anchorAngle.isNaN()) { anchorAngle = a; return }
        if (abs(a - anchorAngle) < MOVE_THRESHOLD_DEG) return
        val prev = anchorAngle
        anchorAngle = a
        if (!settings.hingePreBlur) return

        if (!folding) {
            folding = true
            gestureStartMs = System.currentTimeMillis()
            trail.setLength(0)
            trail.append("hinge ${prev.toInt()}")
            settings = Prefs.load(this)
            overlay.configure(settings)
        }
        trail.append("→${a.toInt()}")
        // Fully open or fully closed: the fold is done, focus soon.
        // Anywhere in between: the user may still be moving it, keep holding.
        val atRest = a <= CLOSED_DEG || a >= OPEN_DEG
        keepBlurred(if (atRest) SETTLE_AT_REST_MS else SETTLE_MID_MS)
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
        private const val SETTLE_MID_MS = 700L       // part-way and no new reading: assume it stopped
        private const val HOLD_AFTER_SWAP_MS = 250L  // switch with no hinge readings at all

        fun start(c: Context) =
            c.startForegroundService(Intent(c, FoldBlurService::class.java))

        fun stop(c: Context) =
            c.stopService(Intent(c, FoldBlurService::class.java))
    }
}
