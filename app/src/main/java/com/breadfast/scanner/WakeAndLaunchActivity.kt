package com.breadfast.scanner

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

class WakeAndLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var appTypeToLaunch: String = "BREADFAST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // قراءة نوع التطبيق من الـ Intent
        appTypeToLaunch = intent.getStringExtra("APP_TYPE") ?: "BREADFAST"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        dismissKeyguardThenLaunch()
    }

    private fun dismissKeyguardThenLaunch() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        if (!keyguardManager.isKeyguardLocked) {
            launchTargetApp()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    launchTargetApp()
                }
                override fun onDismissCancelled() {
                    log("⚠️ تم إلغاء إزالة شاشة القفل؛ لن يبدأ المسح.")
                    finish()
                }
                override fun onDismissError() {
                    log("❌ تعذر إزالة شاشة القفل. تأكد أنه لا يوجد PIN أو بصمة.")
                    finish()
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val keyguardLock = keyguardManager.newKeyguardLock("BreadfastScanner::DismissLock")
            keyguardLock.disableKeyguard()
            launchTargetApp()
        }
    }

    private fun launchTargetApp() {
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        
        // تحديد الحزمة الصحيحة بناءً على نوع التطبيق
        val targetPackage = if (appTypeToLaunch == "RABBIT") {
            prefs.getString("RABBIT_PACKAGE", "com.rabbit.grocery") ?: "com.rabbit.grocery"
        } else {
            prefs.getString("TARGET_PACKAGE", "com.breadfast.application") ?: "com.breadfast.application"
        }
        
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        
        if (launchIntent == null) {
            log("❌ لم يتم العثور على تطبيق [$appTypeToLaunch] (الحزمة: $targetPackage).")
            finish()
            return
        }
        
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        
        try {
            startActivity(launchIntent)
            log("🚀 تم إضاءة الشاشة وإزالة القفل وفتح تطبيق [$appTypeToLaunch] بنجاح.")
        } catch (e: Exception) {
            log("❌ فشل فتح تطبيق [$appTypeToLaunch]: ${e.message}")
            prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
        } finally {
            handler.postDelayed({ finish() }, 1500)
        }
    }

    private fun log(message: String) {
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val oldLogs = prefs.getString("APP_LOGS", "") ?: ""
        val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logs = "[$now] $message\n$oldLogs".lines().take(50).joinToString("\n")
        prefs.edit().putString("APP_LOGS", logs).apply()
    }
}
