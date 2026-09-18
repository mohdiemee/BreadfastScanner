package com.breadfast.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        
        // 1. التحقق من أن البوت مفعل بشكل عام
        if (!prefs.getBoolean("IS_ACTIVE", false)) return

        // === التعديل الجديد: منع التداخل ===
        // 2. التحقق مما إذا كانت هناك دورة سابقة ما زالت تعمل
        if (prefs.getBoolean("IS_AUTO_RUNNING", false)) {
            addLog(context, "⚠️ المنبه رن، لكن هناك دورة مسح ما زالت تعمل. سيتم تجاهل الموعد الحالي.")
            return
        }
        // ===================================

        val targetApp = prefs.getString("TARGET_PACKAGE", "com.breadfast.application") ?: "com.breadfast.application"
        addLog(context, "⏰ المنبه رن! محاولة فتح: $targetApp")

        // إعطاء تأشيرة الدخول للبوت ليعمل هذه المرة
        prefs.edit().putBoolean("IS_AUTO_RUNNING", true).apply()

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetApp)
        
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "BreadfastBot::WakeLock"
                )
                wakeLock.acquire(10000)

                context.startActivity(launchIntent)
                addLog(context, "🚀 تم إرسال أمر الفتح بنجاح (وضع الأتمتة مفعل).")
            } catch (e: Exception) {
                addLog(context, "❌ منع النظام الفتح: ${e.message}")
                prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
            }
        } else {
            addLog(context, "❌ لم يتم العثور على التطبيق.")
            prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
        }
    }

    private fun addLog(context: Context, message: String) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val currentLogs = prefs.getString("APP_LOGS", "") ?: ""
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "[$time] $message\n$currentLogs".lines().take(50).joinToString("\n")
        prefs.edit().putString("APP_LOGS", newLog).apply()
    }
}
