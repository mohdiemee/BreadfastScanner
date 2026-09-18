package com.breadfast.scanner

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class DealScannerService : AccessibilityService() {

    private var isScanning = false
    private var lastScanTime = 0L

    // دالة مخصصة لتسجيل الأحداث وعرضها في الواجهة
    private fun addLog(message: String) {
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val currentLogs = prefs.getString("APP_LOGS", "") ?: ""
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        // نحتفظ بآخر 50 سطراً فقط لعدم امتلاء الذاكرة
        val newLog = "[$time] $message\n$currentLogs".lines().take(50).joinToString("\n")
        prefs.edit().putString("APP_LOGS", newLog).apply()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addLog("✅ تم تشغيل الخدمة بنجاح وربطها بصلاحية Accessibility.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_ACTIVE", false)) return

        val packageName = event.packageName?.toString() ?: return

        // سنقوم بالتسجيل فقط إذا تم فتح تطبيق بريدفاست
        if (packageName == "com.breadfast.application") {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTime < 5000 || isScanning) return
            
            isScanning = true
            lastScanTime = currentTime
            
            addLog("📱 تم رصد تطبيق بريدفاست! جاري معالجة الشاشة...")

            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                addLog("🔍 تم العثور على محتوى الشاشة. جاري إعداد رسالة التليجرام...")
                val token = prefs.getString("BOT_TOKEN", "") ?: ""
                val chatId = prefs.getString("CHAT_ID", "") ?: ""
                
                if (token.isNotEmpty() && chatId.isNotEmpty()) {
                    sendTelegramMessage(token, chatId, "🤖 البوت يعمل بنجاح! تم التقاط بريدفاست.")
                } else {
                    addLog("❌ خطأ: لم يتم إدخال التوكن أو Chat ID بشكل صحيح في الإعدادات.")
                }

                addLog("🏠 جاري محاكاة زر (Home) لإغلاق التطبيق...")
                performGlobalAction(GLOBAL_ACTION_HOME)
            } else {
                addLog("⚠️ تحذير: لم يتمكن التطبيق من قراءة محتوى الشاشة (Root Node is null). قد تكون الشاشة قيد التحميل.")
            }
            
            // تحرير حالة القراءة بعد 5 ثوانٍ
            thread {
                Thread.sleep(5000)
                isScanning = false
            }
        }
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        thread {
            try {
                addLog("⏳ جاري الاتصال بسيرفر تليجرام...")
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encodedText")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode
                
                if (responseCode == 200) {
                    addLog("🚀 تم الإرسال لتليجرام بنجاح (كود 200).")
                } else {
                    addLog("❌ فشل الإرسال لتليجرام. كود الخطأ: $responseCode (تأكد من صحة التوكن).")
                }
                connection.disconnect()
            } catch (e: Exception) {
                addLog("❌ خطأ برمجي أثناء الإرسال: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {
        addLog("⚠️ تمت مقاطعة الخدمة (onInterrupt).")
    }
}
