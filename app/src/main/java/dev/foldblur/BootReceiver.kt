package dev.foldblur

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED &&
            i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.isEnabled(c) && Settings.canDrawOverlays(c)) {
            try { FoldBlurService.start(c) } catch (_: Exception) {}
        }
    }
}
