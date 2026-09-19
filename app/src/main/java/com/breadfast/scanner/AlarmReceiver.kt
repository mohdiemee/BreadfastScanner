package com.breadfast.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        
        // جلب اسم التطبيق المراد فحصه من الـ Intent، وإذا كان فارغاً نفترض أنه بريدفاست (للتوافق القديم)
        val appType = intent.getStringExtra("APP_TYPE") ?: "BREADFAST"

        if (!prefs.getBoolean("IS_ACTIVE", false)) {
            addLog(context, "ℹ️ المنبه رن لتطبيق [$appType] لكن البوت متوقف.")
            return
        }

        // قراءة المواعيد بناءً على التطبيق المطلوب لإعادة جدولتها ليوم الغد
        val runTimesKey = if (appType == "RABBIT") "RABBIT_RUN_TIMES" else "BREADFAST_RUN_TIMES"
        val runTimes = prefs.getString(runTimesKey, "") ?: ""
        
        // استدعاء المجدول (ستحتاج لاحقاً لتعديل AlarmScheduler ليقبل appType إذا أردت فصل المواعيد تماماً)
        AlarmScheduler.scheduleAll(context, runTimes)

        // ⬇️ نظام الحماية من التقاطع (Anti-Collision System) ⬇️
        if (prefs.getBoolean("IS_AUTO_RUNNING", false)) {
            addLog(context, "⚠️ هناك فحص يعمل حالياً. تمت إضافة [$appType] لقائمة الانتظار.")
            // حفظ المهمة في الطابور ليتم تنفيذها لاحقاً
            prefs.edit().putString("PENDING_TASK", appType).apply()
            return
        }

        // ⬇️ بدء الفحص إذا لم يكن هناك فحص يعمل ⬇️
        prefs.edit()
            .putBoolean("IS_AUTO_RUNNING", true)
            .putString("CURRENT_TASK", appType) // حفظ التطبيق الحالي المفتوح
            .apply()

        addLog(context, "⏰ بدء فحص [$appType]؛ جاري استدعاء شاشة الإيقاظ...")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // استخدام Partial WakeLock لعدم استنزاف البطارية (الشاشة الوهمية ستتولى أمر الإضاءة)
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BreadfastScanner::AlarmCpuWakeLock")
        
        try {
            wakeLock.acquire(15000L)
            val wakeIntent = Intent(context, WakeAndLaunchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // تمرير نوع التطبيق لشاشة الإيقاظ لتفتح التطبيق الصحيح (بريدفاست أو رابيت)
                putExtra("APP_TYPE", appType)
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
