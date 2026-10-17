package com.foldsheet

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Single source of truth for the sheet. Every edit is written to disk immediately
 * and pushed through a StateFlow, so the widget and the app both see it live.
 */
object SheetStore {
    const val ROWS = 50
    const val COLS = 12

    private const val PREFS = "foldsheet"
    private const val KEY_CELLS = "cells"

    @Volatile
    private var state: MutableStateFlow<Map<Int, String>>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun flow(context: Context): StateFlow<Map<Int, String>> = ensure(context)

    fun get(context: Context, row: Int, col: Int): String =
        ensure(context).value[key(row, col)] ?: ""

    fun set(context: Context, row: Int, col: Int, value: String) {
        val flow = ensure(context)
        val k = key(row, col)
        if ((flow.value[k] ?: "") == value) return
        val next = flow.value.toMutableMap()
        if (value.isEmpty()) next.remove(k) else next[k] = value
        flow.value = next
        save(context, next)
        scheduleWidgetRefresh(context)
    }

    private var pendingRefresh: Job? = null

    /** Redraws the widget shortly after each keystroke, so it updates behind the pop-up as you type. */
    private fun scheduleWidgetRefresh(context: Context) {
        val app = context.applicationContext
        pendingRefresh?.cancel()
        pendingRefresh = scope.launch {
            delay(250)
            SheetWidget().updateAll(app)
        }
    }

    /** Re-renders every placed widget (needed when no widget session is active). */
    fun refreshWidgets(context: Context) {
        val app = context.applicationContext
        scope.launch { SheetWidget().updateAll(app) }
    }

    fun colName(col: Int): String {
        var n = col
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + (n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    fun cellName(row: Int, col: Int) = colName(col) + (row + 1)

    private fun key(row: Int, col: Int) = row * COLS + col

    private fun ensure(context: Context): MutableStateFlow<Map<Int, String>> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(load(context.applicationContext)).also { state = it }
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): Map<Int, String> {
        val raw = prefs(context).getString(KEY_CELLS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k -> put(k.toInt(), obj.getString(k)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun save(context: Context, cells: Map<Int, String>) {
        val obj = JSONObject()
        cells.forEach { (k, v) -> obj.put(k.toString(), v) }
        prefs(context).edit().putString(KEY_CELLS, obj.toString()).apply()
    }
}
