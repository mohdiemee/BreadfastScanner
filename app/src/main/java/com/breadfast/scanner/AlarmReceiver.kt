package com.breadfast.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("IS_ACTIVE", false)
        
        // تسجيل أن المنبه قد رن
        addLog(context, "⏰ المنبه رن الآن! محاولة فتح تطبيق بريدفاست...")

        if (!isActive) {
            addLog(context, "⚠️ البوت متوقف من الإعدادات، تم تجاهل المنبه.")
            return
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.breadfast.application")
        
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                context.startActivity(launchIntent)
                addLog(context, "🚀 تم إرسال أمر فتح بريدفاست للنظام بنجاح.")
            } catch (e: Exception) {
                addLog(context, "❌ نظام الأندرويد منع الفتح التلقائي (Background Start Restriction): ${e.message}")
            }
        } else {
            addLog(context, "❌ لم يتم العثور على تطبيق بريدفاست (تأكد من أنه مثبت وأن اسم الحزمة صحيح).")
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
