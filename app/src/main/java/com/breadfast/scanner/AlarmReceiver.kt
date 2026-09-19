package com.breadfast.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        
        if (!prefs.getBoolean("IS_ACTIVE", false)) {
            addLog(context, "ℹ️ المنبه رن لكن البوت متوقف.")
            return
        }

        // إعادة جدولة هذا الموعد ليوم الغد لضمان استمرار البوت للعمل للأبد
        AlarmScheduler.scheduleAll(context, prefs.getString("RUN_TIMES", "") ?: "")

        if (prefs.getBoolean("IS_AUTO_RUNNING", false)) {
            addLog(context, "⚠️ المنبه رن لكن دورة مسح سابقة ما زالت تعمل؛ تم تجاهل الموعد.")
            return
        }

        prefs.edit().putBoolean("IS_AUTO_RUNNING", true).apply()
        addLog(context, "⏰ المنبه رن؛ جاري استدعاء شاشة الإيقاظ لطرد القفل...")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // استخدام Partial WakeLock لعدم استنزاف البطارية (الشاشة الوهمية ستتولى أمر الإضاءة)
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BreadfastScanner::AlarmCpuWakeLock")
        
        try {
            wakeLock.acquire(15000L)
            val wakeIntent = Intent(context, WakeAndLaunchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(wakeIntent)
        } catch (e: Exception) {
            prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
            addLog(context, "❌ فشل بدء شاشة الإيقاظ: ${e.message}")
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
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
