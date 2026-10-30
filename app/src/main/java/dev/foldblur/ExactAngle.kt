package dev.foldblur

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads Samsung's exact "Folding Angle" readings from the system diagnostics report
 * (dumpsys sensorservice) through Wireless debugging. No other app needed.
 */
object ExactAngle {

    @Volatile var angle = Float.NaN; private set
    @Volatile var lastPollMs = 0L; private set      // last time a report was read (uptime)
    @Volatile var running = false; private set
    @Volatile var status = "not started"; private set

    /** Called on the main thread whenever the exact angle changes. */
    var onAngle: ((Float) -> Unit)? = null

    val isLive get() = running && SystemClock.uptimeMillis() - lastPollMs < 1500

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var pollThread: Thread? = null
    private var stream: AdbStream? = null
    private var lastTs = -1.0
    private var loggedRaw = false

    private const val CMD =
        "while true; do dumpsys sensorservice | grep -F -A 14 'Folding Angle'; echo @@END; sleep 0.1; done"

    // ---------- pairing ----------

    fun pair(ctx: Context, portText: String, code: String, done: (String) -> Unit) {
        worker.execute {
            val msg = try {
                val port = portText.trim().toIntOrNull()
                    ?: discover(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 8000)
                    ?: throw Exception("couldn't find the pairing port. Keep the pairing-code box open in Settings.")
                if (AdbManager.get(ctx).pair("127.0.0.1", port, code.trim())) {
                    Prefs.putBool(ctx, Prefs.K_PAIRED, true)
                    "Paired ✓"
                } else "Pairing failed (wrong code?)"
            } catch (e: Throwable) {
                "Pairing error: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { Diag.log(msg); done(msg) }
        }
    }

    private fun discover(ctx: Context, type: String, timeoutMs: Long): Int? {
        val found = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx, type) { host, port ->
            if (host != null && port > 0) { found.set(port); latch.countDown() }
        }
        mdns.start()
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        mdns.stop()
        return found.get().takeIf { it > 0 }
    }

    private fun connect(ctx: Context): AdbManager {
        val m = AdbManager.get(ctx)
        if (!m.isConnected && !m.autoConnect(ctx, 6000)) {
            throw Exception("couldn't reach Wireless debugging. Is it turned on?")
        }
        return m
    }

    // ---------- one-off test ----------

    /** Runs a shell command over Wireless debugging and returns its output, giving up after [timeoutMs]. */
    private fun run(m: AdbManager, cmd: String, timeoutMs: Long = 6000, cap: Int = 3000): String {
        val s = m.openStream("shell:$cmd")
        val out = StringBuffer()
        val t = Thread {
            try {
                val r = s.openInputStream().bufferedReader()
                while (true) {
                    val line = r.readLine() ?: break
                    if (out.length < cap) out.append(line).append('\n')
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        t.join(timeoutMs)
        runCatching { s.close() }
        return out.toString().ifBlank { "(no output)\n" }
    }

    fun test(ctx: Context, done: (String) -> Unit) {
        Diag.log("exact angle test started…")
        Thread {
            val report = try {
                val m = connect(ctx)
                val checks = listOf(
                    "shell permissions" to "dumpsys package com.android.shell | grep -i -E 'SSENSOR|SENSORHUB'",
                    "shell identity" to "id",
                    "Folding Angle in report" to "dumpsys sensorservice | grep -n -i -A5 'folding angle'",
                    "recent events section" to "dumpsys sensorservice | grep -n -i -A4 'recent sensor events'",
                    "display hinge info" to "dumpsys display | grep -i -E 'hinge|angle|fold' | head -15",
                    "window hinge info" to "dumpsys window | grep -i -E 'hinge|fold.*angle|angle' | head -15",
                    "device state" to "dumpsys device_state | head -20",
                )
                val out = StringBuilder("EXACT ANGLE TEST v2\n")
                for ((label, cmd) in checks) {
                    out.append("== ").append(label).append(" ==\n")
                    out.append(runCatching { run(m, cmd) }.getOrElse { "failed: ${it.message}\n" })
                }
                val full = runCatching { run(m, "dumpsys sensorservice", 8000, 400_000) }.getOrDefault("")
                val parsed = parse(full.lines())
                out.append(if (parsed != null) "→ latest exact angle: ${"%.2f".format(parsed.second)}°"
                    else "→ no Folding Angle readings in the report")
                out.toString()
            } catch (e: Throwable) {
                "EXACT ANGLE TEST failed: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post { Diag.log(report); done(report.lineSequence().last()) }
        }.apply { isDaemon = true; start() }
    }

    // ---------- live feed ----------

    fun start(ctx: Context) {
        if (running) return
        running = true
        status = "connecting…"
        loggedRaw = false
        pollThread = Thread {
            try {
                val m = connect(ctx)
                val s = m.openStream("shell:$CMD")
                stream = s
                status = "live"
                main.post { Diag.log("exact angle feed started") }
                val reader = s.openInputStream().bufferedReader()
                val chunk = ArrayList<String>()
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.trim() == "@@END") {
                        lastPollMs = SystemClock.uptimeMillis()
                        if (!loggedRaw) {
                            loggedRaw = true
                            val raw = chunk.joinToString("\n").take(5000)
                            main.post { Diag.log("first exact report:\n$raw") }
                        }
                        parse(chunk)?.let { (ts, a) ->
                            if (ts > lastTs) {
                                lastTs = ts
                                if (a != angle) {
                                    angle = a
                                    main.post { onAngle?.invoke(a) }
                                }
                            }
                        }
                        chunk.clear()
                    } else chunk.add(line)
                }
                status = "stopped"
            } catch (e: Throwable) {
                status = "error: ${e.message ?: e.javaClass.simpleName}"
                main.post { Diag.log("exact angle feed: $status") }
            } finally {
                running = false
                runCatching { stream?.close() }
                stream = null
            }
        }.apply { isDaemon = true; name = "exact-angle"; start() }
    }

    fun stop() {
        running = false
        runCatching { stream?.close() }
        pollThread?.interrupt()
        pollThread = null
    }

    private val tsRe = Regex("ts=\\s*([0-9]+(?:\\.[0-9]+)?)")
    private val valRe = Regex("\\)\\s*(-?[0-9]+(?:\\.[0-9]+)?)")

    /** Finds the newest reading in any "Folding Angle" event block. Returns (timestamp, angle). */
    private fun parse(lines: List<String>): Pair<Double, Float>? {
        var best: Pair<Double, Float>? = null
        var inBlock = false
        for (l in lines) {
            if (l.contains("Folding Angle", true)) {
                inBlock = true
                // Some formats put the first event on the same line as the name.
                if (!l.contains("ts=")) continue
            }
            if (!inBlock) continue
            if (!l.contains("ts=")) { inBlock = false; continue }
            val ts = tsRe.find(l)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val v = valRe.find(l)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            if (best == null || ts > best.first) best = ts to v
        }
        return best
    }
}
