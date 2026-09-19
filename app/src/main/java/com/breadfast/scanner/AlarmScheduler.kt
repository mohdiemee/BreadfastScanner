package com.breadfast.scanner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    fun scheduleAllForApp(context: Context, appType: String, times: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            addLog(context, "⚠️ صلاحية Alarms & reminders غير مفعلة؛ لا يمكن ضمان التشغيل.")
            return
        }

        val validTimes = times.split(",").map { it.trim() }.filter { Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(it) }
        
        validTimes.forEachIndexed { index, timeText ->
            val parts = timeText.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // إذا مر موعد اليوم، اضبطه للغد مباشرة
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                // جعل الـ Action مميزاً لكل تطبيق لتجنب التداخل
                action = "com.breadfast.scanner.ALARM_${appType}_$index"
                putExtra("APP_TYPE", appType)
            }
            
            // استخدام Request Code مختلف لتطبيق رابيت (بإضافة 1000) لمنع استبدال منبهات بريدفاست
            val requestCode = if (appType == "RABBIT") 1000 + index else index
            
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            addLog(context, "🗓️ تم ضبط موعد [$appType] الساعة $timeText لـ ${sdf.format(calendar.time)}")
        }
    }

    fun cancelAll(context: Context, maxAlarms: Int = 20) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val appTypes = listOf("BREADFAST", "RABBIT")
        
        for (appType in appTypes) {
            repeat(maxAlarms) { index ->
                val requestCode = if (appType == "RABBIT") 1000 + index else index
                val intent = Intent(context, AlarmReceiver::class.java).apply { 
                    action = "com.breadfast.scanner.ALARM_${appType}_$index" 
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
            }
        }
    }

    private fun addLog(context: Context, message: String) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val oldLogs = prefs.getString("APP_LOGS", "") ?: ""
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logs = "[$time] $message\n$oldLogs".lines().take(50).joinToString("\n")
        prefs.edit().putString("APP_LOGS", logs).apply()
    }
}
