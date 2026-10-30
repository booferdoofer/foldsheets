package dev.foldblur

import android.content.Context
import kotlin.math.sqrt

/**
 * Compass-based fold angle with tilt compensation and a user calibration.
 *
 * Model (in the sensor half's own frame):
 *     compass = foldField(angle) + R^T * earthField
 * R comes from the phone's orientation (gyro + accelerometer, no compass), so the Earth's
 * part can be subtracted. What remains depends only on the fold angle, and is matched
 * against the user's calibration points.
 */
class FoldCal(private val ctx: Context) {

    val angles = floatArrayOf(180f, 135f, 90f, 45f, 0f)
    var points: Array<FloatArray>? = load(); private set
    val isCalibrated get() = points != null

    /** Earth's field in the orientation sensor's world frame. Re-learned every session. */
    val earth = FloatArray(3)
    var earthValid = false; private set

    /** Device -> world rotation matrix (row-major), from the game rotation vector. */
    val rot = FloatArray(9)
    var rotValid = false

    fun earthInDevice(out: FloatArray = FloatArray(3)): FloatArray {
        for (i in 0..2) out[i] = rot[i] * earth[0] + rot[3 + i] * earth[1] + rot[6 + i] * earth[2]
        return out
    }

    private fun toWorld(v: FloatArray): FloatArray =
        FloatArray(3) { j -> rot[j * 3] * v[0] + rot[j * 3 + 1] * v[1] + rot[j * 3 + 2] * v[2] }

    /** Compass reading with the Earth's part removed (only valid when calibrated + earthValid). */
    fun foldField(m: FloatArray): FloatArray {
        val e = earthInDevice()
        return floatArrayOf(m[0] - e[0], m[1] - e[1], m[2] - e[2])
    }

    /**
     * While the phone sits still fully open (or fully closed) we know what its fold field
     * should be, so we can keep the Earth estimate up to date (it drifts slowly).
     */
    fun learnEarth(m: FloatArray, coarse: Float) {
        val p = points ?: return
        if (!rotValid) return
        val ref = when {
            coarse >= 170f -> p[0]
            coarse <= 10f -> p[4]
            else -> return
        }
        val w = toWorld(floatArrayOf(m[0] - ref[0], m[1] - ref[1], m[2] - ref[2]))
        if (!earthValid) { w.copyInto(earth); earthValid = true; return }
        for (k in 0..2) earth[k] += 0.05f * (w[k] - earth[k])
    }

    /** Fold angle (0..180) from a compass reading, or NaN if not ready. */
    fun angle(m: FloatArray): Float {
        val p = points ?: return Float.NaN
        if (!rotValid || !earthValid) return Float.NaN
        val c = foldField(m)
        var best = Float.MAX_VALUE
        var ang = Float.NaN
        for (k in 0 until p.size - 1) {
            val a = p[k]; val b = p[k + 1]
            val ab = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
            val ac = floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
            val len2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2]
            val t = if (len2 < 1e-6f) 0f else ((ac[0] * ab[0] + ac[1] * ab[1] + ac[2] * ab[2]) / len2).coerceIn(0f, 1f)
            val dx = ac[0] - t * ab[0]; val dy = ac[1] - t * ab[1]; val dz = ac[2] - t * ab[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < best) { best = d; ang = angles[k] + (angles[k + 1] - angles[k]) * t }
        }
        return ang
    }

    // ---------- calibration math ----------

    /**
     * Step 1: phone fully open, turned around in many directions. Solves for the open fold
     * field f and the Earth field E from   m = f + R^T E   (least squares, 6 unknowns).
     * Returns null if the phone wasn't turned around enough.
     */
    fun solveOpen(ms: List<FloatArray>, rs: List<FloatArray>): Pair<FloatArray, FloatArray>? {
        val n = ms.size
        if (n < 30) return null
        val N = Array(6) { DoubleArray(6) }
        val v = DoubleArray(6)
        val a = DoubleArray(6)
        for (s in 0 until n) {
            val m = ms[s]; val r = rs[s]
            for (i in 0..2) {
                a.fill(0.0)
                a[i] = 1.0
                for (j in 0..2) a[3 + j] = r[j * 3 + i].toDouble()
                for (p in 0..5) { v[p] += a[p] * m[i]; for (q in 0..5) N[p][q] += a[p] * a[q] }
            }
        }
        // Gaussian elimination (matrix is symmetric positive semi-definite)
        for (col in 0..5) {
            val piv = N[col][col]
            // small pivot on the Earth part = not enough orientation variety
            if (piv < (if (col >= 3) 0.12 * n else 1e-6)) return null
            for (row in col + 1..5) {
                val f = N[row][col] / piv
                for (k in col..5) N[row][k] -= f * N[col][k]
                v[row] -= f * v[col]
            }
        }
        val x = DoubleArray(6)
        for (row in 5 downTo 0) {
            var s = v[row]
            for (k in row + 1..5) s -= N[row][k] * x[k]
            x[row] = s / N[row][row]
        }
        val f = FloatArray(3) { x[it].toFloat() }
        val e = FloatArray(3) { x[3 + it].toFloat() }
        return f to e
    }

    fun setEarth(e: FloatArray) { e.copyInto(earth); earthValid = true }
    fun resetEarth() { earthValid = false }

    fun save(pts: Array<FloatArray>) {
        points = pts
        val s = pts.joinToString(";") { p -> p.joinToString(",") }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("cal_points", s).apply()
    }

    fun clear() {
        points = null
        earthValid = false
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().remove("cal_points").apply()
    }

    private fun load(): Array<FloatArray>? {
        val s = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("cal_points", null) ?: return null
        return runCatching {
            val pts = s.split(";").map { p -> p.split(",").map { it.toFloat() }.toFloatArray() }.toTypedArray()
            if (pts.size == 5 && pts.all { it.size == 3 }) pts else null
        }.getOrNull()
    }
}
