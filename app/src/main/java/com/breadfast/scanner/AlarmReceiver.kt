package com.breadfast.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("IS_ACTIVE", false)
        
        // إذا كان البوت متوقفاً، لا تفعل شيئاً
        if (!isActive) return

        Log.d("DealScanner", "حان وقت الفحص! جاري فتح بريدفاست...")

        // أمر فتح تطبيق بريدفاست برمجياً
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.breadfast.application")
        
        if (launchIntent != null) {
            // إضافة هذه الأعلام (Flags) لفتح التطبيق كشاشة جديدة وإيقاظ الهاتف
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(launchIntent)
        } else {
            Log.e("DealScanner", "تطبيق بريدفاست غير مثبت على هذا الهاتف!")
        }
    }
}
