package dev.foldblur

import android.view.View

/**
 * Samsung One UI has its own real-time blur (SemBlurInfo / View.semSetBlurInfo) that it
 * uses for its own apps, separate from the standard Android window blur that One UI
 * reports as unavailable. This is not a public API, so everything goes through
 * reflection and fails safely if Samsung blocks it.
 */
object SamsungBlur {

    private val intT = Int::class.javaPrimitiveType

    /** Human-readable report of what's reachable on this phone. */
    fun probe(): String = buildString {
        val info = try { Class.forName("android.view.SemBlurInfo") } catch (e: Throwable) { null }
        append("SemBlurInfo: ${if (info != null) "found" else "not found"}")
        if (info != null) {
            val modes = info.fields.filter { it.name.startsWith("BLUR_MODE") }
                .joinToString { "${it.name}=${runCatching { it.getInt(null) }.getOrNull()}" }
            if (modes.isNotEmpty()) append(" [$modes]")
            val b = try { Class.forName("android.view.SemBlurInfo\$Builder") } catch (e: Throwable) { null }
            append("; Builder: ${if (b != null) b.methods.filter { it.declaringClass == b }.joinToString(",") { it.name } else "none"}")
        }
        val viewBlur = View::class.java.methods.filter { it.name.contains("blur", true) }.map { it.name }.distinct()
        append("; View blur methods: ${if (viewBlur.isEmpty()) "none" else viewBlur.joinToString(",")}")
    }

    /** Try to turn on Samsung window blur for [view]. Returns null on success, or the error. */
    fun apply(view: View, radius: Int, bgColor: Int): String? = try {
        val info = Class.forName("android.view.SemBlurInfo")
        val builderCls = Class.forName("android.view.SemBlurInfo\$Builder")
        val mode = runCatching { info.getField("BLUR_MODE_WINDOW").getInt(null) }.getOrDefault(0)
        val builder = builderCls.getConstructor(intT).newInstance(mode)
        builderCls.getMethod("setRadius", intT).invoke(builder, radius)
        runCatching { builderCls.getMethod("setBackgroundColor", intT).invoke(builder, bgColor) }
        val built = builderCls.getMethod("build").invoke(builder)
        View::class.java.getMethod("semSetBlurInfo", info).invoke(view, built)
        null
    } catch (e: Throwable) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
