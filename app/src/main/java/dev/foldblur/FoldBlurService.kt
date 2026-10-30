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
    private var panelKey = 0L
    private var settings = BlurSettings(70, 90, 320, 0.12f, true)

    private val settle = Runnable { overlay.release() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val k = currentPanelKey()
            if (k != panelKey) {          // physical panel changed = fold handoff
                panelKey = k              // (rotation alone doesn't change this)
                trigger(HOLD_AFTER_SWAP_MS)
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
        overlay = BlurOverlay(this)
        sensors = getSystemService(SensorManager::class.java)
        displays = getSystemService(DisplayManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

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
        hinge?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, main) }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        displays.unregisterDisplayListener(displayListener)
        sensors.unregisterListener(this)
        main.removeCallbacks(settle)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val a = e.values[0]
        if (anchorAngle.isNaN()) { anchorAngle = a; return }
        if (abs(a - anchorAngle) >= MOVE_THRESHOLD_DEG) {
            anchorAngle = a
            if (settings.hingePreBlur) trigger(HOLD_AFTER_HINGE_MS)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
        private const val MOVE_THRESHOLD_DEG = 3f   // ignore sensor jitter
        private const val HOLD_AFTER_HINGE_MS = 120L // stay blurred this long after hinge stops
        private const val HOLD_AFTER_SWAP_MS = 180L  // let the new screen draw before focusing

        fun start(c: Context) =
            c.startForegroundService(Intent(c, FoldBlurService::class.java))

        fun stop(c: Context) =
            c.stopService(Intent(c, FoldBlurService::class.java))
    }
}
