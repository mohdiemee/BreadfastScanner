package com.breadfast.scanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
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
            if (isScanning || currentTime - lastScanTime < 30000) return 
            
            isScanning = true
            lastScanTime = currentTime
            
            thread {
                try {
                    runAutomation(prefs)
                } catch (e: Exception) {
                    addLog("❌ خطأ أثناء الفحص: ${e.message}")
                } finally {
                    isScanning = false
                }
            }
        }
    }

    private fun runAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح التطبيق.. ننتظر 20 ثانية لاكتمال التحميل...")
        Thread.sleep(20000) 

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            addLog("❌ لم نتمكن من قراءة الشاشة.")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 1. البحث عن أيقونة Deals والتمرير للوصول إليها باستخدام Swipe
        var dealsNode = findNodeByText(rootInActiveWindow, "Deals")
        var scrollAttempts = 0
        
        while (dealsNode == null && scrollAttempts < 4) {
            addLog("🔄 جاري السحب لأسفل للبحث عن أيقونة العروض...")
            swipeUp()
            Thread.sleep(3000)
            dealsNode = findNodeByText(rootInActiveWindow, "Deals")
            scrollAttempts++
        }

        if (dealsNode == null) {
            addLog("⚠️ لم يتم العثور على أيقونة (Deals)، جاري الإغلاق.")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        addLog("🎯 تم العثور على أيقونة العروض! جاري الضغط...")
        clickNode(dealsNode)
        
        addLog("⏳ ننتظر 8 ثواني لتحميل صفحة العروض...")
        Thread.sleep(8000)

        // 2. قراءة صفحة العروض (مسح وسحب متكرر للمنتجات الفعلية)
        addLog("🔍 جاري مسح المنتجات وقراءة الأسعار...")
        val allTexts = mutableListOf<String>()
        
        for (i in 1..4) {
            extractTextFromNodes(rootInActiveWindow, allTexts)
            swipeUp() // السحب البشري لأسفل الشاشة
            Thread.sleep(3000)
        }

        // 3. تحليل النصوص وحساب النسب
        val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
        val foundDeals = analyzePricesAndCalculateDiscount(allTexts, minDiscount)

        // 4. الإرسال والإغلاق
        if (foundDeals.isNotEmpty()) {
            addLog("🔥 تم العثور على ${foundDeals.size} منتجات بخصم يتخطى $minDiscount%.")
            val token = prefs.getString("BOT_TOKEN", "") ?: ""
            val chatId = prefs.getString("CHAT_ID", "") ?: ""
            
            val message = "🛒 **عروض بريدفاست الجديدة:**\n\n" + foundDeals.joinToString("\n---\n")
            sendTelegramMessage(token, chatId, message)
        } else {
            addLog("📉 لم يتم العثور على أي خصومات تتخطى $minDiscount%.")
        }

        addLog("🏠 انتهت المهمة بنجاح، جاري إغلاق التطبيق.")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // --- محاكاة السحب البشري (Swipe Up) ---
    private fun swipeUp() {
        val displayMetrics = resources.displayMetrics
        val middleX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.8f // يبدأ السحب من أسفل الشاشة
        val endY = displayMetrics.heightPixels * 0.2f   // ينتهي في أعلى الشاشة

        val path = Path().apply {
            moveTo(middleX, startY)
            lineTo(middleX, endY)
        }

        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, 500) // مدة السحب نصف ثانية
        gestureBuilder.addStroke(stroke)

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun extractTextFromNodes(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            texts.add(text.trim())
        }
        for (i in 0 until node.childCount) {
            extractTextFromNodes(node.getChild(i), texts)
        }
    }

    private fun analyzePricesAndCalculateDiscount(texts: List<String>, minDiscount: Int): List<String> {
        val deals = mutableListOf<String>()
        val uniqueTexts = texts.distinct() 
        val numRegex = Regex("^\\d+(\\.\\d+)?$")

        for (i in 0 until uniqueTexts.size - 1) {
            val text1 = uniqueTexts[i].replace(Regex("[^0-9.]"), "")
            val text2 = uniqueTexts[i+1].replace(Regex("[^0-9.]"), "")
            
            if (text1.matches(numRegex) && text2.matches(numRegex) && text1.isNotEmpty() && text2.isNotEmpty()) {
                val p1 = text1.toDoubleOrNull()
                val p2 = text2.toDoubleOrNull()
                
                if (p1 != null && p2 != null && p1 != p2) {
                    val oldPrice = maxOf(p1, p2)
                    val newPrice = minOf(p1, p2)
                    val discountPercent = (((oldPrice - newPrice) / oldPrice) * 100).toInt()
                    
                    if (discountPercent >= minDiscount) {
                        val productName = if (i + 2 < uniqueTexts.size && !uniqueTexts[i+2].replace(Regex("[^0-9.]"), "").matches(numRegex)) {
                            uniqueTexts[i+2]
                        } else {
                            "منتج مميز"
                        }
                        
                        val dealText = "✅ **$productName**\n📉 الخصم: $discountPercent%\n💰 السعر: $newPrice بدلاً من $oldPrice"
                        if (!deals.contains(dealText)) deals.add(dealText)
                    }
                }
            }
        }
        return deals
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val nodeText = node.text?.toString() ?: node.contentDescription?.toString()
        if (nodeText != null && nodeText.contains(targetText, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val result = findNodeByText(node.getChild(i), targetText)
            if (result != null) return result
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            current = current.parent
        }
        return false
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
                addLog("❌ خطأ إرسال للتليجرام: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {}
}
