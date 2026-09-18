package com.breadfast.scanner

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class DealScannerService : AccessibilityService() {

    private var isScanning = false
    private var lastScanTime = 0L

    private fun addLog(message: String) {
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val currentLogs = prefs.getString("APP_LOGS", "") ?: ""
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "[$time] $message\n$currentLogs".lines().take(50).joinToString("\n")
        prefs.edit().putString("APP_LOGS", newLog).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_ACTIVE", false)) return

        val targetApp = prefs.getString("TARGET_PACKAGE", "com.breadfast.application") ?: ""
        if (event.packageName?.toString() == targetApp) {
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTime < 8000 || isScanning) return 
            
            isScanning = true
            lastScanTime = currentTime
            
            addLog("📱 تم التعرّف على التطبيق! جاري قراءة العروض...")

            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // استخراج جميع النصوص من الشاشة
                val allTexts = mutableListOf<String>()
                extractTextFromNodes(rootNode, allTexts)
                
                val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
                val foundDeals = analyzeTextsForDeals(allTexts, minDiscount)

                if (foundDeals.isNotEmpty()) {
                    addLog("🔥 تم العثور على ${foundDeals.size} عروض! جاري الإرسال...")
                    val token = prefs.getString("BOT_TOKEN", "") ?: ""
                    val chatId = prefs.getString("CHAT_ID", "") ?: ""
                    
                    val message = "🛒 **عروض جديدة مطابقة لشرطك:**\n\n" + foundDeals.joinToString("\n---\n")
                    sendTelegramMessage(token, chatId, message)
                } else {
                    addLog("📉 لم يتم العثور على عروض تتجاوز نسبة $minDiscount%.")
                }

                // العودة للشاشة الرئيسية
                addLog("🏠 جاري إغلاق التطبيق والعودة...")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            
            thread {
                Thread.sleep(8000)
                isScanning = false
            }
        }
    }

    // خوارزمية استخراج النصوص بالكامل
    private fun extractTextFromNodes(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        if (node.text != null) {
            texts.add(node.text.toString())
        }
        if (node.contentDescription != null) {
            texts.add(node.contentDescription.toString())
        }
        for (i in 0 until node.childCount) {
            extractTextFromNodes(node.getChild(i), texts)
        }
    }

    // خوارزمية البحث وتحليل الخصومات
    private fun analyzeTextsForDeals(texts: List<String>, minDiscount: Int): List<String> {
        val deals = mutableListOf<String>()
        val priceRegex = Regex("(\\d+(\\.\\d+)?)\\s*(EGP|جنيه)", RegexOption.IGNORE_CASE)
        val discountRegex = Regex("(\\d+)%\\s*(OFF|خصم)", RegexOption.IGNORE_CASE)

        for (text in texts) {
            // البحث عن النصوص التي تحتوي على علامة الخصم %
            val discountMatch = discountRegex.find(text)
            if (discountMatch != null) {
                val percent = discountMatch.groupValues[1].toIntOrNull() ?: 0
                if (percent >= minDiscount) {
                    deals.add("✅ خصم بقيمة $percent%\n التفاصيل: $text")
                }
            }
        }
        // يمكن تطوير هذه الخوارزمية لاحقاً لربط السعر بالاسم بدقة بناءً على ترتيب الـ Nodes
        return deals.distinct()
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        thread {
            try {
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encodedText&parse_mode=Markdown")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.inputStream.reader().readText()
                connection.disconnect()
            } catch (e: Exception) {
                addLog("❌ خطأ إرسال: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {}
}
