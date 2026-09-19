package com.breadfast.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_ACTIVE", false)) return
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                AlarmScheduler.scheduleAll(context, prefs.getString("RUN_TIMES", "") ?: "")
            }
        }
    }
}
