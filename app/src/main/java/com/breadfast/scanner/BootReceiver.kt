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
                
                // جلب المواعيد المحفوظة لكلا التطبيقين
                val bfTimes = prefs.getString("BREADFAST_RUN_TIMES", "") ?: ""
                val rbTimes = prefs.getString("RABBIT_RUN_TIMES", "") ?: ""
                
                // جدولة كلا التطبيقين
                AlarmScheduler.scheduleAllForApp(context, "BREADFAST", bfTimes)
                AlarmScheduler.scheduleAllForApp(context, "RABBIT", rbTimes)
            }
        }
    }
}
