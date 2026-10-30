package dev.foldblur

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * Works out why Samsung's precise hinge sensors refuse us, and whether there's
 * any other route to the fold angle. Read-only: it changes nothing.
 */
object Discovery {

    private val keys = listOf("hinge", "fold", "angle", "hall", "posture", "lid")

    fun run(c: Context): String = buildString {
        val sm = c.getSystemService(SensorManager::class.java)
        val pm = c.packageManager

        appendLine("== Sensors ==")
        sm.getSensorList(Sensor.TYPE_ALL)
            .filter { s -> keys.any { s.name.contains(it, true) || s.stringType.contains(it, true) } }
            .forEach { s ->
                val perm = requiredPermission(s)
                appendLine("${s.name} [${s.stringType}] type=${s.type} vendor=${s.vendor} mode=${s.reportingMode} perm=${perm ?: "(hidden)"}")
                if (!perm.isNullOrEmpty()) {
                    val info = runCatching { pm.getPermissionInfo(perm, 0) }.getOrNull()
                    val granted = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                    appendLine("   level=${info?.let { level(it) } ?: "unknown"} owner=${info?.packageName} granted=$granted")
                }
            }

        appendLine("== Sensor-type permissions on this phone ==")
        @Suppress("DEPRECATION")
        val pkgs = runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())
        val seen = mutableSetOf<String>()
        for (p in pkgs) for (perm in p.permissions ?: emptyArray()) {
            val n = perm.name
            if (n in seen) continue
            if (listOf("SENSOR", "HINGE", "FOLD", "ANGLE", "POSTURE", "DEVICE_STATE").any { n.contains(it, true) }) {
                seen += n
                appendLine("$n = ${level(perm)} (${p.packageName})")
            }
        }
        if (seen.isEmpty()) appendLine("(none visible)")

        appendLine("== Samsung / system fold APIs ==")
        for (cn in listOf(
            "com.samsung.android.hardware.sensormanager.SemSensorManager",
            "com.samsung.android.hardware.context.SemContextManager",
            "com.samsung.android.view.SemWindowManager",
            "android.hardware.devicestate.DeviceStateManager",
            "com.samsung.android.hardware.devicestate.SemDeviceStateManager",
            "com.samsung.android.feature.SemFloatingFeature",
            "com.samsung.android.util.SemFoldingManager",
        )) {
            val cls = runCatching { Class.forName(cn) }.getOrNull()
            if (cls == null) { appendLine("${cn.substringAfterLast('.')}: not found"); continue }
            val ms = runCatching {
                (cls.methods.toList() + cls.declaredMethods.toList()).map { it.name }
                    .filter { m -> keys.any { m.contains(it, true) } || m.contains("state", true) && m.contains("fold", true) }
                    .distinct()
            }.getOrDefault(emptyList())
            appendLine("${cn.substringAfterLast('.')}: found; ${if (ms.isEmpty()) "no fold/angle methods visible" else ms.joinToString(",")}")
        }
    }

    private fun requiredPermission(s: Sensor): String? =
        runCatching { Sensor::class.java.getMethod("getRequiredPermission").invoke(s) as String? }.getOrNull()
            ?: runCatching {
                Sensor::class.java.getDeclaredField("mRequiredPermission").apply { isAccessible = true }.get(s) as String?
            }.getOrNull()

    private fun level(p: PermissionInfo): String {
        val base = when (p.protection) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            else -> "other(${p.protection})"
        }
        val f = p.protectionFlags
        val flags = buildList {
            if (f and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) add("privileged")
            if (f and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0) add("development")
            if (f and PermissionInfo.PROTECTION_FLAG_APPOP != 0) add("appop")
        }
        return if (flags.isEmpty()) base else "$base|${flags.joinToString("|")}"
    }
}
