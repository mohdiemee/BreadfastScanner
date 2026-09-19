package com.breadfast.scanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// === الكلاس لربط اسم المنتج الأصلي مع النص التسويقي وحالة الإرسال ===
data class DealData(val originalName: String, val dealText: String, var isAssigned: Boolean = false)
data class NodeData(val text: String, val node: AccessibilityNodeInfo)

class DealScannerService : AccessibilityService() {

    private var isScanning = false
    private var lastScanTime = 0L
    private var addedItemsCount = 0

    private val replyMarkup = """{"inline_keyboard":[[{"text":"✈️ تليجرام","callback_data":"publish_tg"},{"text":"🟢 واتس اب","callback_data":"publish_wa"}],[{"text":"📘 جروب فيسبوك","callback_data":"publish_fb"},{"text":"📄 صفحة فيسبوك","callback_data":"publish_fb_page"}],[{"text":"🗑️ حذف العرض","callback_data":"delete_deal"}]]}"""

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
        if (!prefs.getBoolean("IS_ACTIVE", false) || !prefs.getBoolean("IS_AUTO_RUNNING", false)) return

        val currentTask = prefs.getString("CURRENT_TASK", "BREADFAST") ?: "BREADFAST"
        val targetApp = if (currentTask == "RABBIT") 
            prefs.getString("RABBIT_PACKAGE", "com.rabbit.grocery") ?: "com.rabbit.grocery"
        else 
            prefs.getString("TARGET_PACKAGE", "com.breadfast.application") ?: "com.breadfast.application"

        if (event.packageName?.toString() == targetApp) {
            val currentTime = System.currentTimeMillis()
            if (isScanning || currentTime - lastScanTime < 30000) return 
            
            isScanning = true
            lastScanTime = currentTime
            addedItemsCount = 0
            
            thread {
                try {
                    if (currentTask == "RABBIT") {
                        runRabbitAutomation(prefs)
                    } else {
                        runBreadfastAutomation(prefs)
                    }
                } catch (e: Exception) {
                    addLog("❌ خطأ جسيم أوقف العملية: ${e.message}")
                } finally {
                    isScanning = false
                    prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
                    Thread.sleep(2000)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    Thread.sleep(1500)
                    
                    try {
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.killBackgroundProcesses(targetApp)
                        addLog("🏠 تم الإغلاق ومسح تطبيق [$currentTask] من الذاكرة بنجاح.")
                    } catch (e: Exception) {
                        addLog("⚠️ تم الرجوع للرئيسية (تعذر مسح الذاكرة).")
                    }

                    val pendingTask = prefs.getString("PENDING_TASK", "")
                    if (!pendingTask.isNullOrEmpty()) {
                        prefs.edit().remove("PENDING_TASK").apply() 
                        addLog("⏳ الفحص اكتمل. جاري تشغيل فحص [$pendingTask] المنتظر بعد 20 ثانية...")
                        Thread.sleep(20000) 
                        val retryIntent = Intent(this@DealScannerService, AlarmReceiver::class.java).apply {
                            putExtra("APP_TYPE", pendingTask)
                        }
                        sendBroadcast(retryIntent)
                    }
                }
            }
        }
    }

    // ==========================================
    // دوال مساعدة خاصة بتنقل رابيت (Rabbit Navigation Helpers)
    // ==========================================
    private enum class RabbitBottomTab { CART, PROMOTIONS }

    private fun tapRabbitBottomTab(tab: RabbitBottomTab): Boolean {
        val metrics = resources.displayMetrics
        val isRtl = resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val x = when (tab) {
            RabbitBottomTab.CART -> metrics.widthPixels * 0.50f
            RabbitBottomTab.PROMOTIONS -> {
                if (isRtl) metrics.widthPixels * 0.30f else metrics.widthPixels * 0.70f
            }
        }
        val y = metrics.heightPixels * 0.955f
        addLog("🎯 Rabbit Nav: tab=$tab, x=${x.toInt()}, y=${y.toInt()}")
        return tapScreenPoint(x, y)
    }

    private fun waitForAnyText(vararg texts: String, timeoutMs: Long = 6000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && texts.any { text -> findNodeByText(root, text) != null }) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun findExactTextNode(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
        if (text.equals(target, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val result = findExactTextNode(node.getChild(i), target)
            if (result != null) return result
        }
        return null
    }

    // ==========================================
    // منطق تطبيق رابيت (Rabbit Automation)
    // ==========================================
    private fun runRabbitAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح رابيت.. ننتظر 35 ثانية للتحميل...")
        Thread.sleep(35000)

        val supermarketNode = findNodeByText(rootInActiveWindow, "Supermarket+") ?: findNodeByText(rootInActiveWindow, "+سوبرماركت")
        if (supermarketNode != null) {
            addLog("🛒 تم العثور على سوبر ماركت+، جاري الدخول...")
            val clickableParent = findClickableParent(supermarketNode, 5) ?: supermarketNode
            clickNodeSafely(clickableParent)
            Thread.sleep(6000)
        }

        addLog("🗑️ Rabbit: جاري فتح السلة لمسح المنتجات القديمة...")
        if (!tapRabbitBottomTab(RabbitBottomTab.CART)) return
        
        val cartOpened = waitForAnyText("Deserted cart?", "إيه الصحراء دي؟", "Clear all", "فضي الكيس", "My Cart", "الكيس", timeoutMs = 6000L)
        if (!cartOpened) return

        val emptyCart = findNodeByText(rootInActiveWindow, "Deserted cart?") ?: findNodeByText(rootInActiveWindow, "إيه الصحراء دي؟")
        if (emptyCart != null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(3000)
        } else {
            val clearBtn = findNodeByText(rootInActiveWindow, "Clear all") ?: findNodeByText(rootInActiveWindow, "فضي الكيس")
            if (clearBtn != null) {
                clickNodeSafely(clearBtn)
                Thread.sleep(1500)
                val confirmBtn = findExactTextNode(rootInActiveWindow, "Clear") ?: findExactTextNode(rootInActiveWindow, "تمام")
                if (confirmBtn != null) {
                    clickNodeSafely(confirmBtn)
                    addLog("✅ تم تفريغ سلة Rabbit بنجاح.")
                }
                Thread.sleep(3000)
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(3000)
        }

        addLog("🎯 Rabbit: جاري فتح صفحة العروض...")
        if (!tapRabbitBottomTab(RabbitBottomTab.PROMOTIONS)) return
        
        val promotionsOpened = waitForAnyText("Promotions", "عروض", "Promo Codes", "أكواد الخصم", timeoutMs = 7000L)
        if (!promotionsOpened) return
        Thread.sleep(1500)

        val minDiscount = prefs.getInt("RABBIT_MIN_DISCOUNT", 30) 
        val cooldownHours = prefs.getInt("COOLDOWN_HOURS", 24)
        val cooldownMillis = cooldownHours * 60 * 60 * 1000L
        val historyMap = loadHistoryMap(prefs)
        val foundDeals = mutableListOf<DealData>() 
        val processedProducts = mutableSetOf<String>()
        
        var previousScreenContent = ""
        var emptyScrolls = 0
        var totalScrolls = 0
        val metrics = resources.displayMetrics
        val safeTop = metrics.heightPixels * 0.15f
        val safeBottom = metrics.heightPixels * 0.82f 
        
        while (totalScrolls < 500) {
            val visibleNodes = mutableListOf<NodeData>()
            extractNodes(rootInActiveWindow, visibleNodes, safeTop, safeBottom)
            val currentScreenContent = visibleNodes.map { it.text }.distinct().sorted().joinToString("|")
            
            analyzeRabbitDeals(visibleNodes, minDiscount, foundDeals, processedProducts, historyMap, cooldownMillis, prefs)
            
            Thread.sleep(900)
            if (currentScreenContent == previousScreenContent) {
                emptyScrolls++
                if (emptyScrolls >= 3) break 
            } else {
                emptyScrolls = 0
            }
            previousScreenContent = currentScreenContent
            totalScrolls++
            swipeUp(0.75f, 0.50f, 800L) 
            Thread.sleep(1200) 
        }

        val token = prefs.getString("BOT_TOKEN", "") ?: ""
        val chatId = prefs.getString("CHAT_ID", "") ?: ""

        if (foundDeals.isNotEmpty()) {
            addLog("🛒 Rabbit: جاري فتح السلة النهائية لإرسال التقرير...")
            if (!tapRabbitBottomTab(RabbitBottomTab.CART)) return
            
            val finalCartOpened = waitForAnyText("My Cart", "الكيس", "Clear all", "فضي الكيس", "Deserted cart?", "إيه الصحراء دي؟", timeoutMs = 6000L)
            if (!finalCartOpened) return
            Thread.sleep(4000)

            if (Build.VERSION.SDK_INT >= 30) {
                // إخبار الدالة أن السلة مفتوحة بالفعل لتجنب نقر زر الرجوع بالخطأ
                openCartAndSendReport(token, chatId, foundDeals, isCartAlreadyOpen = true)
            } else {
                sendChunksAsText(token, chatId, foundDeals.map { it.dealText }.chunked(5))
            }
        } else {
            addLog("📉 لم يتم العثور على عروض مناسبة في رابيت حالياً.")
        }
    }

    private fun analyzeRabbitDeals(
        nodesList: List<NodeData>, minDiscount: Int, deals: MutableList<DealData>, 
        processed: MutableSet<String>, historyMap: MutableMap<String, Long>, 
        cooldownMillis: Long, prefs: SharedPreferences
    ) {
        val uniqueNodes = nodesList.distinctBy { it.node }

        for (i in uniqueNodes.indices) {
            val current = uniqueNodes[i]
            val text = current.text.trim()
            
            if (text.contains("%")) {
                val match = Regex("(\\d{1,2})").find(text)
                if (match != null && text.length <= 8) {
                    val discountPercent = match.value.toIntOrNull() ?: continue
                    
                    if (discountPercent >= minDiscount) {
                        var productName = "منتج رابيت مميز"
                        for (k in i + 1..minOf(i + 4, uniqueNodes.size - 1)) {
                            val candidate = uniqueNodes[k].text.trim()
                            if (candidate.length > 4 && !candidate.contains("%")) {
                                productName = candidate
                                break
                            }
                        }

                        if (processed.contains(productName)) continue
                        val lastSent = historyMap[productName]
                        if (lastSent != null && (System.currentTimeMillis() - lastSent) < cooldownMillis) continue

                        var plusClicked = false
                        for (j in i..minOf(i + 8, uniqueNodes.size - 1)) {
                            val pNode = uniqueNodes[j].node
                            val pText = pNode.text?.toString()?.trim() ?: ""
                            val pDesc = pNode.contentDescription?.toString()?.trim() ?: ""
                            if (pText == "+" || pDesc == "+" || pText.equals("Add", true) || pDesc.equals("Add", true)) {
                                plusClicked = clickNodeSafely(pNode)
                                if (plusClicked) break
                            }
                        }
                        
                        if (!plusClicked) {
                            val productCard = findRabbitProductCard(current.node)
                            if (productCard != null) plusClicked = forceClickAddButton(productCard)
                        }

                        if (!plusClicked) {
                            for (j in i + 1..minOf(i + 8, uniqueNodes.size - 1)) {
                                val pNode = uniqueNodes[j].node
                                val rect = Rect()
                                pNode.getBoundsInScreen(rect)
                                val combined = ("${pNode.text} ${pNode.contentDescription}").trim()
                                if (pNode.isClickable && combined.isBlank() && rect.width() in 40..200 && rect.height() in 40..200) {
                                    plusClicked = clickNodeSafely(pNode)
                                    if (plusClicked) break
                                }
                            }
                        }

                        if (plusClicked) {
                            val cleanName = productName.replace("\n", " ").trim()
                            deals.add(DealData(originalName = cleanName, dealText = "$cleanName بخصم $discountPercent%"))
                            processed.add(productName)
                            historyMap[productName] = System.currentTimeMillis()
                            saveHistoryMap(prefs, historyMap)
                        }
                    }
                }
            }
        }
    }

    private fun findRabbitProductCard(badgeNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = badgeNode.parent
        val screenWidth = resources.displayMetrics.widthPixels
        for (level in 0..6) {
            val node = current ?: break
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > screenWidth * 0.25 && rect.width() < screenWidth * 0.60 && rect.height() > 200) {
                return node
            }
            current = node.parent
        }
        return badgeNode.parent
    }

    // ==========================================
    // منطق تطبيق بريدفاست القديم (Breadfast Automation)
    // ==========================================
    private fun runBreadfastAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح بريدفاست.. ننتظر 15 ثانية للتحميل...")
        Thread.sleep(15000) 

        val initialCartNode = findNodeByText(rootInActiveWindow, "السلة") ?: findNodeByText(rootInActiveWindow, "Cart")
        if (initialCartNode != null) {
            addLog("🗑️ جاري فتح السلة لمسح المنتجات القديمة...")
            clickNodeSafely(initialCartNode)
            Thread.sleep(5000)
            
            val clearAllNode = findNodeByText(rootInActiveWindow, "مسح الكل") ?: findNodeByText(rootInActiveWindow, "Clear All")
            if (clearAllNode != null) {
                if (clickNodeSafely(clearAllNode)) {
                    val confirmButton = waitForBottomSheetClearButton(6000)
                    if (confirmButton != null) clickConfirmClearButton(confirmButton)
                    Thread.sleep(4000)
                }
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(3000)
        }

        var dealsNode = findNodeByText(rootInActiveWindow, "Deals") ?: findNodeByText(rootInActiveWindow, "عروض")
        var scrollAttempts = 0
        while (dealsNode == null && scrollAttempts < 4) {
            swipeUp(0.8f, 0.5f, 400L)
            Thread.sleep(1500)
            dealsNode = findNodeByText(rootInActiveWindow, "Deals") ?: findNodeByText(rootInActiveWindow, "عروض")
            scrollAttempts++
        }

        if (dealsNode == null) return
        clickNodeSafely(dealsNode) 
        Thread.sleep(6000)

        val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
        val cooldownHours = prefs.getInt("COOLDOWN_HOURS", 24)
        val cooldownMillis = cooldownHours * 60 * 60 * 1000L
        val historyMap = loadHistoryMap(prefs)
        
        val foundDeals = mutableListOf<DealData>() 
        val processedProducts = mutableSetOf<String>()
        var previousScreenContent = ""
        var emptyScrolls = 0
        var totalScrolls = 0
        val metrics = resources.displayMetrics
        val safeTop = metrics.heightPixels * 0.15f
        val safeBottom = metrics.heightPixels * 0.85f
        
        while (totalScrolls < 1000) {
            val visibleNodes = mutableListOf<NodeData>()
            extractNodes(rootInActiveWindow, visibleNodes, safeTop, safeBottom)
            val currentScreenContent = visibleNodes.map { it.text }.distinct().sorted().joinToString("|")
            
            analyzeAndAddToCart(visibleNodes, minDiscount, foundDeals, processedProducts, historyMap, cooldownMillis, prefs)
            
            Thread.sleep(900)
            if (currentScreenContent == previousScreenContent) {
                emptyScrolls++
                if (emptyScrolls >= 3) break 
            } else {
                emptyScrolls = 0
            }
            previousScreenContent = currentScreenContent
            totalScrolls++
            swipeUp(0.8f, 0.5f, 400L)
            Thread.sleep(1100) 
        }

        val token = prefs.getString("BOT_TOKEN", "") ?: ""
        val chatId = prefs.getString("CHAT_ID", "") ?: ""

        if (foundDeals.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                // إخبار الدالة أن السلة غير مفتوحة ويجب الضغط على أيقونتها
                openCartAndSendReport(token, chatId, foundDeals, isCartAlreadyOpen = false)
            } else {
                sendChunksAsText(token, chatId, foundDeals.filter { it.originalName != "منتج مميز" }.map { it.dealText }.chunked(5))
            }
        }
    }

    // ==========================================
    // الدوال المشتركة (Shared Helpers)
    // ==========================================
    
    private fun loadHistoryMap(prefs: SharedPreferences): MutableMap<String, Long> {
        val historyStr = prefs.getString("PRODUCTS_HISTORY", "") ?: ""
        val historyMap = mutableMapOf<String, Long>()
        if (historyStr.isNotEmpty()) {
            historyStr.split("||").forEach { entry ->
                val parts = entry.split("::")
                if (parts.size == 2) historyMap[parts[0]] = parts[1].toLongOrNull() ?: 0L
            }
        }
        return historyMap
    }

    private fun saveHistoryMap(prefs: SharedPreferences, map: Map<String, Long>) {
        val newHistoryStr = map.map { "${it.key}::${it.value}" }.joinToString("||")
        prefs.edit().putString("PRODUCTS_HISTORY", newHistoryStr).apply()
    }

    private fun extractNodes(node: AccessibilityNodeInfo?, nodesList: MutableList<NodeData>, safeTop: Float, safeBottom: Float) {
        if (node == null) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val isSafe = rect.top >= safeTop && rect.bottom <= safeBottom
        
        if (isSafe) {
            val text = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: ""
            if (text.isNotEmpty() && nodesList.none { it.text == text && it.node == node }) {
                nodesList.add(NodeData(text, node))
            }
        }
        for (i in 0 until node.childCount) extractNodes(node.getChild(i), nodesList, safeTop, safeBottom)
    }

    private fun analyzeAndAddToCart(
        nodesList: List<NodeData>, minDiscount: Int, deals: MutableList<DealData>, 
        processed: MutableSet<String>, historyMap: MutableMap<String, Long>, 
        cooldownMillis: Long, prefs: SharedPreferences
    ) {
        val numRegex = Regex("^[0-9]{1,6}(?:\\.[0-9]{1,2})?$")
        val uniqueNodes = nodesList.distinctBy { it.text }

        for (i in uniqueNodes.indices) {
            val current = uniqueNodes[i]
            val dualPriceMatch = Regex("^([0-9]{1,6}(?:\\.[0-9]{1,2})?)\\s+([0-9]{1,6}(?:\\.[0-9]{1,2})?)$").find(current.text)
            if (dualPriceMatch != null) {
                val p1 = dualPriceMatch.groupValues[1].toDoubleOrNull() ?: continue
                val p2 = dualPriceMatch.groupValues[2].toDoubleOrNull() ?: continue
                processDealNode(p1, p2, i + 1, uniqueNodes, minDiscount, deals, processed, current.node, historyMap, cooldownMillis, prefs)
                continue
            }
            if (numRegex.matches(current.text) && i + 1 < uniqueNodes.size && numRegex.matches(uniqueNodes[i+1].text)) {
                val p1 = current.text.toDoubleOrNull() ?: continue
                val p2 = uniqueNodes[i+1].text.toDoubleOrNull() ?: continue
                processDealNode(p1, p2, i + 2, uniqueNodes, minDiscount, deals, processed, current.node, historyMap, cooldownMillis, prefs)
            }
        }
    }

    private fun processDealNode(
        p1: Double, p2: Double, nameIdx: Int, uniqueNodes: List<NodeData>, minDiscount: Int, 
        deals: MutableList<DealData>, processed: MutableSet<String>, priceNode: AccessibilityNodeInfo,
        historyMap: MutableMap<String, Long>, cooldownMillis: Long, prefs: SharedPreferences
    ) {
        val oldPrice = maxOf(p1, p2)
        val newPrice = minOf(p1, p2)

        if (oldPrice > 0 && oldPrice != newPrice) {
            val discountPercent = (((oldPrice - newPrice) / oldPrice) * 100).toInt()
            if (discountPercent >= minDiscount) {
                val productName = if (nameIdx < uniqueNodes.size && !uniqueNodes[nameIdx].text.matches(Regex("^[0-9\\s.]+$"))) 
                    uniqueNodes[nameIdx].text else "منتج مميز"

                if (processed.contains(productName)) return
                val lastSentTime = historyMap[productName]
                if (lastSentTime != null && (System.currentTimeMillis() - lastSentTime) < cooldownMillis) return 
                
                try {
                    val productCard = findProductCard(priceNode)
                    if (productCard != null && forceClickAddButton(productCard)) addedItemsCount++
                    var cleanName = productName.replace("\n", " ").trim()
                    cleanName = cleanName.replace(Regex("\\s+"), " ").trim()
                    deals.add(DealData(originalName = productName, dealText = "$cleanName ب $newPrice جنيه بخصم $discountPercent%"))
                    processed.add(productName)
                    historyMap[productName] = System.currentTimeMillis()
                    saveHistoryMap(prefs, historyMap)
                } catch (e: Exception) { }
            }
        }
    }

    private fun findProductCard(priceNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = priceNode.parent
        val screenWidth = resources.displayMetrics.widthPixels
        for (level in 0..5) {
            val node = current ?: break
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > screenWidth * 0.18 && rect.width() < screenWidth * 0.48 && rect.height() > 150) return node
            current = node.parent
        }
        return priceNode.parent
    }

    private fun forceClickAddButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val id = node.viewIdResourceName ?: ""
        val combined = "$text $desc $id".lowercase(java.util.Locale.ROOT)

        if (combined.contains("favorite") || combined.contains("مفضلة")) return false

        val isCartAddButton = text == "+" || desc == "+" || combined.contains("add to cart") || combined.contains("أضف إلى السلة")
        if (isCartAddButton) return clickNodeSafely(node)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (node.isClickable && combined.isBlank() && rect.width() in 40..250 && rect.height() in 40..250) {
            return clickNodeSafely(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (forceClickAddButton(child)) return true
        }
        return false
    }

    private fun clickNodeSafely(node: AccessibilityNodeInfo): Boolean {
        try {
            if (!node.refresh()) return false
            if (node.isClickable) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Thread.sleep(500)
                    return true
                }
            }
            val rect = getRect(node)
            if (rect.isEmpty || rect.width() < 10 || rect.height() < 10) return false
            return tapScreenPoint(rect.centerX().toFloat(), rect.centerY().toFloat())
        } catch (e: Exception) { return false }
    }

    @TargetApi(30)
    private fun openCartAndSendReport(token: String, chatId: String, deals: List<DealData>, isCartAlreadyOpen: Boolean = false) {
        
        // التحقق من فتح السلة فقط إذا لم تكن مفتوحة بالفعل (لتجنب الخروج منها في رابيت)
        if (!isCartAlreadyOpen) {
            val cartNode = findNodeByText(rootInActiveWindow, "Cart") ?: findNodeByText(rootInActiveWindow, "السلة") ?: findNodeByText(rootInActiveWindow, "الكيس")
            if (cartNode != null) {
                clickNodeSafely(cartNode)
                Thread.sleep(5000) 
            }
        }
        
        val shotsCount = Math.ceil(deals.size / 5.0).toInt()
        
        if (deals.isNotEmpty()) {
            val safeTopCart = resources.displayMetrics.heightPixels * 0.20f
            val safeBottomCart = resources.displayMetrics.heightPixels * 0.82f 
            
            for (i in 0 until shotsCount) {
                val bitmap = takeScreenshotSync()
                var currentChunk = mutableListOf<String>()
                var imageBytes: ByteArray? = null
                
                if (bitmap != null) {
                    val topCrop = (bitmap.height * 0.20).toInt()
                    val bottomCrop = (bitmap.height * 0.18).toInt()
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, topCrop, bitmap.width, bitmap.height - topCrop - bottomCrop)
                    val stream = ByteArrayOutputStream()
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    imageBytes = stream.toByteArray()
                    
                    val visibleNodes = mutableListOf<NodeData>()
                    extractNodes(rootInActiveWindow, visibleNodes, safeTopCart, safeBottomCart)
                    val screenText = visibleNodes.joinToString(" ") { it.text.replace("\n", " ") }
                    
                    for (deal in deals) {
                        if (!deal.isAssigned) {
                            val cleanName = deal.originalName.replace("\n", " ").trim()
                            // استخدام فلتر الكلمات بدلاً من الحروف المحددة لتفادي تشابه الأسماء الطويلة
                            val words = cleanName.split(" ").filter { it.length > 2 }.take(2)
                            val matchCount = words.count { screenText.contains(it, ignoreCase = true) }
                            
                            if (matchCount >= 1 || cleanName.contains("مميز")) {
                                currentChunk.add(deal.dealText)
                                deal.isAssigned = true
                            }
                        }
                    }
                }
                
                if (currentChunk.size > 5) {
                    val extras = currentChunk.drop(5)
                    currentChunk = currentChunk.take(5).toMutableList()
                    deals.filter { it.dealText in extras }.forEach { it.isAssigned = false }
                }

                // خطة بديلة: إذا فشل التطابق النصي لأي سبب، أرفق 5 منتجات غير مرسلة للصورة إجبارياً بدلاً من تخطيها
                if (currentChunk.isEmpty() && deals.any { !it.isAssigned }) {
                    val unassigned = deals.filter { !it.isAssigned }.take(5)
                    unassigned.forEach { 
                        currentChunk.add(it.dealText)
                        it.isAssigned = true
                    }
                    addLog("⚠️ تطابق نصي ضعيف، تم إرفاق ${currentChunk.size} منتجات احتياطياً.")
                }

                if (currentChunk.isNotEmpty() && imageBytes != null) {
                    val prefix = if (i == 0) "عروض ممتازة\n" else "ودول كمان\n"
                    val caption = prefix + currentChunk.joinToString("\n\n")
                    addLog("📸 إرسال صورة تحتوي على ${currentChunk.size} منتجات...")
                    sendTelegramPhotoMultipart(token, chatId, imageBytes, caption.take(1020))
                }
                
                if (i < shotsCount - 1) {
                    swipeUp(0.80f, 0.20f, 1000L) // سكرول لأسفل السلة لرؤية المنتجات الأخرى
                    Thread.sleep(2000)
                }
            }
        }
    }

    private fun sendChunksAsText(token: String, chatId: String, chunks: List<List<String>>) {
        for (i in chunks.indices) {
            val prefix = if (i == 0) "عروض ممتازة\n" else "ودول كمان\n"
            sendTelegramMessage(token, chatId, (prefix + chunks[i].joinToString("\n\n")).take(1020))
            Thread.sleep(700)
        }
    }

    @TargetApi(30)
    private fun takeScreenshotSync(): Bitmap? {
        var bitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hwBuffer = screenshot.hardwareBuffer
                    bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    hwBuffer.close()
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) { } finally { executor.shutdown() }
        return bitmap
    }

    private fun sendTelegramPhotoMultipart(token: String, chatId: String, imageBytes: ByteArray, caption: String) {
        try {
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val connection = URL("https://api.telegram.org/bot$token/sendPhoto").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true
            val outputStream = DataOutputStream(connection.outputStream)
            
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
            outputStream.write((chatId + "\r\n").toByteArray(Charsets.UTF_8))
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n")
            outputStream.write((caption + "\r\n").toByteArray(Charsets.UTF_8))
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"reply_markup\"\r\n\r\n")
            outputStream.write((replyMarkup + "\r\n").toByteArray(Charsets.UTF_8))
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"cart.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
            outputStream.write(imageBytes)
            outputStream.writeBytes("\r\n--$boundary--\r\n")
            outputStream.flush()
            outputStream.close()
            
            if (connection.responseCode in 200..299) {
                addLog("✅ تم رفع الصورة بنجاح.")
            } else {
                addLog("❌ فشل الرفع: ${connection.responseCode}")
            }
            connection.disconnect()
        } catch (e: Exception) { addLog("❌ خطأ رفع الصورة: ${e.message}") }
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=${URLEncoder.encode(text, "UTF-8")}&reply_markup=${URLEncoder.encode(replyMarkup, "UTF-8")}")
            val connection = url.openConnection() as HttpURLConnection
            connection.inputStream.reader().readText()
            connection.disconnect()
        } catch (e: Exception) {}
    }

    private fun swipeUp(startFactor: Float, endFactor: Float, durationMs: Long) {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels / 2f, metrics.heightPixels * startFactor)
            lineTo(metrics.widthPixels / 2f, metrics.heightPixels * endFactor)
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(), null, null)
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val nodeText = node.text?.toString() ?: node.contentDescription?.toString()
        if (nodeText != null && nodeText.contains(targetText, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val result = findNodeByText(node.getChild(i), targetText)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() {}

    private fun normalizedText(node: AccessibilityNodeInfo): String = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
    private fun getRect(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun findClickableParent(startNode: AccessibilityNodeInfo, maxLevels: Int = 5): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = startNode
        repeat(maxLevels) {
            val current = node ?: return null
            if (current.isVisibleToUser && current.isEnabled && current.isClickable) return current
            node = current.parent
        }
        return null
    }

    private fun collectNodesByExactText(node: AccessibilityNodeInfo?, target: String, result: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (normalizedText(node).equals(target, ignoreCase = true)) result.add(node)
        for (i in 0 until node.childCount) collectNodesByExactText(node.getChild(i), target, result)
    }

    private fun findBottomSheetClearConfirmButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectNodesByExactText(root, "مسح الكل", candidates)
        collectNodesByExactText(root, "Clear All", candidates)
        return candidates.mapNotNull { findClickableParent(it) ?: it.takeIf { node -> node.isClickable && node.isEnabled } }
            .maxByOrNull { getRect(it).centerY() }
    }

    private fun waitForBottomSheetClearButton(timeoutMs: Long = 6000L): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val button = findBottomSheetClearConfirmButton()
            if (button != null) return button
            Thread.sleep(250)
        }
        return null
    }

    private fun clickConfirmClearButton(button: AccessibilityNodeInfo): Boolean {
        try {
            if (!button.refresh()) return false
            if (button.isClickable && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val rect = getRect(button)
            return tapScreenPoint(rect.centerX().toFloat(), rect.centerY().toFloat())
        } catch (e: Exception) { return false }
    }

    private fun tapScreenPoint(x: Float, y: Float): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 100)).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { completed = false; latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(2, TimeUnit.SECONDS)
        Thread.sleep(500)
        return completed
    }

    private fun isCartEmpty(): Boolean {
        val root = rootInActiveWindow ?: return false
        val emptyText = findNodeByText(root, "السلة فارغة") ?: findNodeByText(root, "فارغة")
        return emptyText != null || (findNodeByText(root, "مسح الكل") == null && findNodeByText(root, "Clear All") == null)
    }
}
