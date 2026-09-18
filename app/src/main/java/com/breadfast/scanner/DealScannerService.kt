package com.breadfast.scanner

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class DealScannerService : AccessibilityService() {

    private var isScanning = false
    private var lastScanTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_ACTIVE", false)) return

        // 1. التأكد أن التطبيق المفتوح هو Breadfast
        if (event.packageName?.toString() != "com.breadfast.application") return

        // 2. منع التكرار السريع للفحص (ننتظر 5 ثواني حتى تحمل شاشة بريدفاست)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScanTime < 5000 || isScanning) return
        
        isScanning = true
        lastScanTime = currentTime

        Log.d("DealScanner", "تم رصد تطبيق بريدفاست.. جاري سحب البيانات")

        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val token = prefs.getString("BOT_TOKEN", "") ?: ""
            val chatId = prefs.getString("CHAT_ID", "") ?: ""
            val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
            
            // هنا سيتم إضافة خوارزمية استخراج النصوص الدقيقة (سنضيفها في التحديث القادم بعد اختبار الإغلاق)
            val testMessage = "🤖 البوت يعمل بنجاح! تم فتح بريدفاست وإغلاقه آلياً."
            
            if (token.isNotEmpty() && chatId.isNotEmpty()) {
                sendTelegramMessage(token, chatId, testMessage)
            }

            // 3. الإغلاق الآلي: الضغط على زر الهوم للعودة وطوي بريدفاست في الخلفية
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        isScanning = false
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        thread {
            try {
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encodedText")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.inputStream.reader().readText()
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("DealScanner", "خطأ تليجرام: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {}
}
