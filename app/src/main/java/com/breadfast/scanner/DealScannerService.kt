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

        // الشرط السحري: هل البوت يعمل بأمر المنبه أم بتدخل يدوي؟
        if (!prefs.getBoolean("IS_AUTO_RUNNING", false)) return

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
                    // سحب التأشيرة بعد انتهاء العمل ليعود البوت للنوم
                    prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
                }
            }
        }
    }

    private fun runAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح التطبيق.. ننتظر 20 ثانية للتحميل...")
        Thread.sleep(20000) 

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            addLog("❌ لم نتمكن من قراءة الشاشة.")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // دعم اللغتين: البحث عن Deals أو عروض
        var dealsNode = findNodeByText(rootInActiveWindow, "Deals") ?: findNodeByText(rootInActiveWindow, "عروض")
        var scrollAttempts = 0
        
        while (dealsNode == null && scrollAttempts < 4) {
            swipeUp()
            Thread.sleep(3000)
            dealsNode = findNodeByText(rootInActiveWindow, "Deals") ?: findNodeByText(rootInActiveWindow, "عروض")
            scrollAttempts++
        }

        if (dealsNode == null) {
            addLog("⚠️ لم يتم العثور على أيقونة العروض، جاري الإغلاق.")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        addLog("🎯 تم العثور على الأيقونة! جاري الدخول...")
        clickNode(dealsNode)
        Thread.sleep(8000)

        addLog("🔍 جاري مسح المنتجات وإضافتها للسلة إن طابقت الشروط...")
        val allTexts = mutableListOf<String>()
        var previousTextCount = 0
        var emptyScrolls = 0
        var totalScrolls = 0
        
        while (totalScrolls < 150) {
            // استخراج النصوص والعقد البرمجية لتمكين الضغط
            extractTextAndAttemptCartAdd(rootInActiveWindow, allTexts, prefs.getInt("MIN_DISCOUNT", 40))
            
            val currentTextCount = allTexts.distinct().size
            if (currentTextCount == previousTextCount) {
                emptyScrolls++
                if (emptyScrolls >= 3) {
                    addLog("🏁 تم الوصول لنهاية قائمة العروض بنجاح.")
                    break 
                }
            } else {
                emptyScrolls = 0
            }
            previousTextCount = currentTextCount
            totalScrolls++
            
            swipeUp()
            Thread.sleep(3000)
        }

        val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
        val foundDeals = analyzePricesAndCalculateDiscount(allTexts, minDiscount)

        if (foundDeals.isNotEmpty()) {
            addLog("🔥 تم العثور على ${foundDeals.size} عروض فعلية وتمت محاولة إضافتها.")
            val token = prefs.getString("BOT_TOKEN", "") ?: ""
            val chatId = prefs.getString("CHAT_ID", "") ?: ""
            
            val message = "🛒 **عروض بريدفاست المطابقة وتمت إضافتها:**\n\n" + foundDeals.joinToString("\n---\n")
            sendTelegramMessage(token, chatId, message)
        } else {
            addLog("📉 لم يتم العثور على خصومات تتخطى $minDiscount%.")
        }

        addLog("🏠 اكتملت العملية. جاري العودة للشاشة الرئيسية.")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun swipeUp() {
        val displayMetrics = resources.displayMetrics
        val middleX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.8f 
        val endY = displayMetrics.heightPixels * 0.2f   

        val path = Path().apply {
            moveTo(middleX, startY)
            lineTo(middleX, endY)
        }

        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
        gestureBuilder.addStroke(stroke)
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    // هذه الدالة المحدثة تجمع النصوص وتحاول الضغط على الأزرار القابلة للضغط المجاورة للسعر
    private fun extractTextAndAttemptCartAdd(node: AccessibilityNodeInfo?, texts: MutableList<String>, minDiscount: Int) {
        if (node == null) return
        
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            texts.add(text)
            // محاولة التقاط أزرار (+) بناءً على وجود أسعار في العقدة المجاورة 
            // (هذه خطوة تجريبية تعتمد على هيكل واجهة بريدفاست)
            if (node.isClickable && (text == "+" || text.contains("Add", true) || text.contains("أضف", true))) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        
        for (i in 0 until node.childCount) {
            extractTextAndAttemptCartAdd(node.getChild(i), texts, minDiscount)
        }
    }

    private fun analyzePricesAndCalculateDiscount(texts: List<String>, minDiscount: Int): List<String> {
        val deals = mutableListOf<String>()
        val uniqueTexts = texts.distinct() 

        for (i in uniqueTexts.indices) {
            val currentText = uniqueTexts[i]

            val dualPriceMatch = Regex("^([0-9]{1,6}(?:\\.[0-9]{1,2})?)\\s+([0-9]{1,6}(?:\\.[0-9]{1,2})?)$").find(currentText)
            if (dualPriceMatch != null) {
                val p1 = dualPriceMatch.groupValues[1].toDoubleOrNull() ?: continue
                val p2 = dualPriceMatch.groupValues[2].toDoubleOrNull() ?: continue
                processDeal(p1, p2, i + 1, uniqueTexts, minDiscount, deals)
                continue
            }

            val singlePriceRegex = Regex("^[0-9]{1,6}(?:\\.[0-9]{1,2})?$")
            if (singlePriceRegex.matches(currentText) && i + 1 < uniqueTexts.size && singlePriceRegex.matches(uniqueTexts[i+1])) {
                val p1 = currentText.toDoubleOrNull() ?: continue
                val p2 = uniqueTexts[i+1].toDoubleOrNull() ?: continue
                processDeal(p1, p2, i + 2, uniqueTexts, minDiscount, deals)
            }
        }
        return deals
    }

    private fun processDeal(p1: Double, p2: Double, nameIndex: Int, uniqueTexts: List<String>, minDiscount: Int, deals: MutableList<String>) {
        val oldPrice = maxOf(p1, p2)
        val newPrice = minOf(p1, p2)

        if (oldPrice > 0 && oldPrice != newPrice) {
            val discountPercent = (((oldPrice - newPrice) / oldPrice) * 100).toInt()
            
            if (discountPercent >= minDiscount) {
                val productName = if (nameIndex < uniqueTexts.size && !uniqueTexts[nameIndex].matches(Regex("^[0-9\\s.]+$"))) {
                    uniqueTexts[nameIndex]
                } else {
                    "منتج بعرض مميز"
                }
                
                val dealText = "✅ **$productName**\n📉 الخصم: $discountPercent%\n💰 السعر: $newPrice بدلاً من $oldPrice"
                if (!deals.contains(dealText)) deals.add(dealText)
            }
        }
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
            } catch (e: Exception) {}
        }
    }

    override fun onInterrupt() {}
}
