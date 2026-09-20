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

data class DealData(val originalName: String, val dealText: String, var isAssigned: Boolean = false)
data class NodeData(val text: String, val node: AccessibilityNodeInfo)
private data class RabbitProductInfo(val name: String, val unit: String?, val salePrice: String?)
private data class PositionedDeal(val deal: DealData, val top: Int, val score: Int)
private data class CartProduct(val textDescription: String, val top: Int)

class DealScannerService : AccessibilityService() {

    private var isScanning = false
    private var lastScanTime = 0L
    private var addedItemsCount = 0
    private val rabbitAddAttempts = mutableMapOf<String, Int>()

    private val replyMarkup = """{"inline_keyboard":[[{"text":"✈️ تليجرام","callback_data":"publish_tg"},{"text":"🟢 واتس اب","callback_data":"publish_wa"}],[{"text":"📘 جروب فيسبوك","callback_data":"publish_fb"},{"text":"📄 صفحة فيسبوك","callback_data":"publish_fb_page"}],[{"text":"🗑️ حذف العرض","callback_data":"delete_deal"}]]}"""

    private fun addLog(message: String) {
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val currentLogs = prefs.getString("APP_LOGS", "") ?: ""
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        // التعديل: زيادة عدد الأسطر إلى 1000 بدلاً من 50
        val newLog = "[$time] $message\n$currentLogs".lines().take(1000).joinToString("\n")
        prefs.edit().putString("APP_LOGS", newLog).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_ACTIVE", false) || !prefs.getBoolean("IS_AUTO_RUNNING", false)) return

        val currentTask = prefs.getString("CURRENT_TASK", "BREADFAST") ?: "BREADFAST"
        
        val targetApp = if (currentTask == "RABBIT") {
            prefs.getString("RABBIT_PACKAGE", "com.rabbit.grocery") ?: "com.rabbit.grocery"
        } else {
            prefs.getString("TARGET_PACKAGE", "com.breadfast.application") ?: "com.breadfast.application"
        }

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



    private fun parseRabbitCartProducts(nodes: List<NodeData>): List<CartProduct> {
    val products = mutableListOf<CartProduct>()
    var startIndex = 0
    while (startIndex < nodes.size) {
        // تخطي أي أزرار كمية متبقية من المنتج السابق
        while (startIndex < nodes.size && (nodes[startIndex].text.trim() in listOf("1", "-", "+", "2", "3"))) {
            startIndex++
        }
        if (startIndex >= nodes.size) break

        var currencyIndex = -1
        for (i in startIndex until nodes.size) {
            val t = nodes[i].text.replace(Regex("[\\u202A-\\u202C\\u200E\\u200F]"), "").trim()
            if (t == "جنيه" || t.equals("egp", ignoreCase = true)) {
                currencyIndex = i
                break
            }
        }

        if (currencyIndex != -1) {
            val chunk = nodes.subList(startIndex, currencyIndex)
            if (chunk.isNotEmpty()) {
                val topY = getRect(chunk[0].node).top
                val texts = chunk.map { it.text.replace(Regex("[\\u202A-\\u202C\\u200E\\u200F]"), "").trim() }
                
                // استخراج آخر 4 أرقام قبل كلمة جنيه (والتي تمثل أسعار المنتج)
                val numbers = texts.filter { it.matches(Regex("\\d+")) }
                if (numbers.size >= 4) {
                    val last4 = numbers.takeLast(4)
                    val newPrice = "${last4[1]}.${last4[0]}".toDoubleOrNull() ?: 0.0
                    val oldPrice = "${last4[3]}.${last4[2]}".toDoubleOrNull() ?: 0.0

                    if (oldPrice > 0 && newPrice > 0 && oldPrice > newPrice) {
                        val discount = (((oldPrice - newPrice) / oldPrice) * 100).toInt()
                        
                        var name = texts[0]
                        if (texts.size > 1 && texts[1].matches(Regex(".*(قطعة|جم|كجم|مل|لتر|ق|علكة|pcs|pc|g|gm|kg|ml|l).*"))) {
                            name += " (" + texts[1] + ")"
                        }
                        
                        val formattedPrice = if (newPrice % 1.0 == 0.0) newPrice.toInt().toString() else newPrice.toString()
                        products.add(CartProduct("$name ب $formattedPrice جنيه بخصم $discount%", topY))
                    }
                }
            }
            startIndex = currencyIndex + 1
        } else {
            break
        }
    }
    return products
}

private fun smartScrollRabbitCart(parsedProducts: List<CartProduct>, visibleNodes: List<NodeData>, previousSignature: String): Boolean {
    val metrics = resources.displayMetrics
    val cropTopBoundary = metrics.heightPixels * 0.20f // النقطة التي يبدأ منها قص السكرين شوت

    var nextItemTop: Float? = null
    val lastCurrencyIndex = visibleNodes.indexOfLast { 
        it.text.replace(Regex("[\\u202A-\\u202C\\u200E\\u200F]"), "").trim().let { t -> t == "جنيه" || t.equals("egp", ignoreCase = true) } 
    }
    
    if (lastCurrencyIndex != -1 && lastCurrencyIndex + 1 < visibleNodes.size) {
        var tempIndex = lastCurrencyIndex + 1
        while (tempIndex < visibleNodes.size && visibleNodes[tempIndex].text.trim() in listOf("1", "-", "+", "2", "3")) {
            tempIndex++
        }
        if (tempIndex < visibleNodes.size) {
            nextItemTop = getRect(visibleNodes[tempIndex].node).top.toFloat()
        }
    }

    // سحب العنصر غير المكتمل (أو الأخير) ليكون بالضبط عند حافة قص الصورة
    val startY = nextItemTop ?: (if (parsedProducts.isNotEmpty()) parsedProducts.last().top.toFloat() else metrics.heightPixels * 0.75f)
    
    if (startY > cropTopBoundary + 50f) {
        val startFactor = (startY / metrics.heightPixels).coerceAtMost(0.85f)
        val endFactor = cropTopBoundary / metrics.heightPixels
        swipeUp(startFactor, endFactor, 1600L)
    } else {
        swipeUp(0.75f, 0.20f, 1600L)
    }

    repeat(10) {
        Thread.sleep(300)
        val newSignature = cartScreenSignature(cartVisibleNodes())
        if (newSignature.isNotBlank() && newSignature != previousSignature) return true
    }
    return false
}


    private fun isRabbitInArabic(): Boolean {
        val root = rootInActiveWindow ?: return resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        
        // فحص فعلي للنصوص الموجودة على الشاشة لتحديد لغة التطبيق الداخلية
        val isArabic = findNodeByText(root, "سوبرماركت") != null || 
                       findNodeByText(root, "الكيس") != null || 
                       findNodeByText(root, "عروض") != null || 
                       findNodeByText(root, "إيه الصحراء") != null ||
                       findNodeByText(root, "فضي الكيس") != null

        if (isArabic) return true
        
        val isEnglish = findNodeByText(root, "Supermarket") != null || 
                        findNodeByText(root, "Cart") != null || 
                        findNodeByText(root, "Promotions") != null ||
                        findNodeByText(root, "Deserted cart") != null ||
                        findNodeByText(root, "Clear all") != null

        if (isEnglish) return false

        // الخطة البديلة: الاعتماد على لغة النظام إذا كانت الشاشة خالية من النصوص السابقة
        return resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
    }
    
    // ==========================================
    // دوال مساعدة خاصة بتنقل واستخراج بيانات رابيت
    // ==========================================
    private enum class RabbitBottomTab { CART, PROMOTIONS }

    private fun tapRabbitBottomTab(tab: RabbitBottomTab): Boolean {
        val metrics = resources.displayMetrics
        
        // استخدام الدالة الذكية لمعرفة لغة واجهة التطبيق فعلياً بدلاً من لغة الهاتف
        val isRtl = isRabbitInArabic() 
        
        val x = when (tab) {
            RabbitBottomTab.CART -> metrics.widthPixels * 0.50f
            RabbitBottomTab.PROMOTIONS -> {
                if (isRtl) metrics.widthPixels * 0.30f else metrics.widthPixels * 0.70f
            }
        }
        val y = metrics.heightPixels * 0.955f
        
        addLog("🎯 Rabbit Nav: tab=$tab, isArabic=$isRtl, x=${x.toInt()}, y=${y.toInt()}")
        return tapScreenPoint(x, y)
    }

    private fun waitForAnyText(vararg texts: String, timeoutMs: Long = 6000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && texts.any { text -> findNodeByText(root, text) != null }) {
                return true
            }
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

    private fun collectTextsFromNode(node: AccessibilityNodeInfo?, output: MutableList<String>) {
        if (node == null) return
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
        if (text.isNotEmpty()) output.add(text)
        for (i in 0 until node.childCount) {
            collectTextsFromNode(node.getChild(i), output)
        }
    }

    private fun normalizeRabbitText(text: String): String {
        return text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun extractRabbitSalePrice(rawText: String): String? {
        val normalized = normalizeRabbitText(rawText)
        if (!normalized.contains(Regex("""(?i)\bEGP\b|جنيه"""))) return null
        
        // استخراج جميع الأرقام التي تبدو كأسعار
        val priceTokens = Regex("""\d+(?:[.,]\d{1,2})?""").findAll(normalized)
            .map { it.value.replace(",", ".") }.toList()
        
        if (priceTokens.isEmpty()) return null
        
        val combinedPrices = mutableListOf<Double>()
        var i = 0
        while(i < priceTokens.size) {
            // معالجة حالة رابيت التي تفصل القروش بمسافة (مثال: 100 00)
            if (i + 1 < priceTokens.size && priceTokens[i+1].length == 2 && !priceTokens[i].contains(".")) {
                val combined = "${priceTokens[i]}.${priceTokens[i+1]}".toDoubleOrNull()
                if (combined != null) combinedPrices.add(combined)
                i += 2
            } else {
                val single = priceTokens[i].toDoubleOrNull()
                if (single != null) combinedPrices.add(single)
                i++
            }
        }
        
        // السعر الحالي (الخصم) هو دائماً الرقم الأصغر
        val minPrice = combinedPrices.minOrNull()
        return minPrice?.toString()
    }

    private fun extractRabbitProductInfo(cardNode: AccessibilityNodeInfo): RabbitProductInfo? {
        val texts = mutableListOf<String>()
        collectTextsFromNode(cardNode, texts)
        val rawText = normalizeRabbitText(texts.distinct().joinToString(" "))
        if (rawText.isBlank()) return null
        
        // دعم شامل للوحدات الإنجليزية والعربية
        val unitRegex = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:kg|gm|g|ml|l|pcs?|pc|جم|مل|لتر|قطعة|علكة)\b""")
        val unit = unitRegex.find(rawText)?.value?.replace(",", ".")?.trim()
        
        val beforeCurrency = Regex("""(?i)\bEGP\b|جنيه""").find(rawText)
            ?.let { rawText.substring(0, it.range.first) } ?: rawText
            
        var name = beforeCurrency
            .replace(Regex("""[-]?\s*\d{1,2}\s*[%٪]-?"""), "") 
            .replace(Regex("""(?i)\badd\b|أضف|اضف"""), "")
            .replace(Regex("""(?i)\bfavorite\b|مفضلة"""), "")
            .replace(Regex("""\+"""), "")
            .replace(unitRegex, "")
            .replace(Regex("""\b\d+(?:[.,]\d+)?\b"""), "") 
            .replace(Regex("\\s+"), " ")
            .trim()
            
        name = name.replace("EGP", "", ignoreCase = true)
            .replace("جنيه", "", ignoreCase = true)
            .replace("Add", "", ignoreCase = true)
            .replace("Favorite", "", ignoreCase = true).trim()
            
        if (name.length < 3) return null
        return RabbitProductInfo(name = name, unit = unit, salePrice = extractRabbitSalePrice(rawText))
    }

    private fun findRabbitProductCard(badgeNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = badgeNode.parent
        val screenWidth = resources.displayMetrics.widthPixels
        for (level in 0..6) {
            val node = current ?: break
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val widthRatio = rect.width().toFloat() / screenWidth.toFloat()
            if (widthRatio in 0.22f..0.45f && rect.height() > 180) {
                return node
            }
            current = node.parent
        }
        return null
    }

    private fun findRabbitAddButton(productCard: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cardRect = getRect(productCard)
        if (cardRect.isEmpty || cardRect.width() < 100 || cardRect.height() < 150) {
            return null
        }

        // تحديد لغة التطبيق لضبط الإحداثيات المتوقعة
        val isArabic = isRabbitInArabic()
        val expectedX = if (isArabic) cardRect.left + (cardRect.width() * 0.20f) else cardRect.left + (cardRect.width() * 0.80f)
        val expectedY = cardRect.top + (cardRect.height() * 0.43f)
        
        var bestCandidate: AccessibilityNodeInfo? = null
        var bestScore = Float.NEGATIVE_INFINITY

        fun scanNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName ?: ""
            val combined = "$text $desc $id".trim().lowercase(java.util.Locale.ROOT)
            val rect = getRect(node)

            val isInsideCard = !rect.isEmpty && rect.left >= cardRect.left - 5 && rect.right <= cardRect.right + 5 && 
                               rect.top >= cardRect.top - 5 && rect.bottom <= cardRect.bottom + 5

            if (isInsideCard && rect.width() >= 30 && rect.height() >= 30) {
                val width = rect.width().toFloat()
                val height = rect.height().toFloat()
                val aspectRatio = width / height
                val centerXRatio = (rect.centerX() - cardRect.left).toFloat() / cardRect.width().toFloat()
                val centerYRatio = (rect.centerY() - cardRect.top).toFloat() / cardRect.height().toFloat()

                // دعم الكلمات العربية (أضف/اضف) بجانب العلامات
                val isTextAddButton = text == "+" || desc == "+" || combined == "add" || combined.contains("add to cart") || combined.contains("أضف") || combined.contains("اضف")
                
                // تحديد نطاق البحث الهندسي: (0.02 إلى 0.42 لليسار) أو (0.58 إلى 0.98 لليمين)
                val isValidXRatio = if (isArabic) centerXRatio in 0.02f..0.42f else centerXRatio in 0.58f..0.98f
                
                val isGeometryAddButton = node.isClickable && combined.isBlank() && width in 40f..250f && height in 40f..250f &&
                                          aspectRatio in 0.65f..1.45f && isValidXRatio && centerYRatio in 0.18f..0.72f

                if (isTextAddButton || isGeometryAddButton) {
                    val distance = kotlin.math.abs(rect.centerX() - expectedX) + kotlin.math.abs(rect.centerY() - expectedY)
                    val score = (if (isTextAddButton) 10000f else 0f) - distance
                    if (score > bestScore) {
                        bestScore = score
                        bestCandidate = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                scanNode(node.getChild(i))
            }
        }
        scanNode(productCard)
        return bestCandidate
    }

    private fun extractAllTextsFromCard(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        val t = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
        if (t.isNotEmpty()) texts.add(t)
        for (i in 0 until node.childCount) {
            extractAllTextsFromCard(node.getChild(i), texts)
        }
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

        addLog("🗑️ Rabbit: جاري فتح السلة لم مسح المنتجات القديمة...")
        if (!tapRabbitBottomTab(RabbitBottomTab.CART)) {
            addLog("❌ Rabbit: فشل إرسال نقرة السلة.")
            return
        }
        
        val cartOpened = waitForAnyText("Deserted cart?", "إيه الصحراء دي؟", "Clear all", "فضي الكيس", "My Cart", "الكيس", timeoutMs = 6000L)
        if (!cartOpened) {
            addLog("❌ Rabbit: لم يتم فتح صفحة السلة؛ إيقاف العملية.")
            return
        }

        val emptyCart = findNodeByText(rootInActiveWindow, "Deserted cart?") ?: findNodeByText(rootInActiveWindow, "إيه الصحراء دي؟")
        if (emptyCart != null) {
            addLog("🛒 السلة فارغة بالفعل. جاري الرجوع...")
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
        if (!tapRabbitBottomTab(RabbitBottomTab.PROMOTIONS)) {
            addLog("❌ Rabbit: فشل إرسال نقرة صفحة العروض.")
            return
        }
        
        val promotionsOpened = waitForAnyText("Promotions", "عروض", "Promo Codes", "أكواد الخصم", timeoutMs = 7000L)
        if (!promotionsOpened) {
            addLog("❌ Rabbit: لم يتم الوصول إلى Promotions.")
            return
        }
        addLog("✅ Rabbit: تم فتح صفحة العروض بنجاح.")
        Thread.sleep(1500)

        rabbitAddAttempts.clear() 
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
        
        var noAddPasses = 0
        while (totalScrolls < 500) {
            val visibleNodes = mutableListOf<NodeData>()
            extractNodes(rootInActiveWindow, visibleNodes, safeTop, safeBottom)
            val currentScreenContent = visibleNodes.map { it.text }.distinct().sorted().joinToString("|")
            
            val productAdded = analyzeRabbitDeals(visibleNodes, minDiscount, foundDeals, processedProducts, historyMap, cooldownMillis, prefs)
            
            if (productAdded) {
                noAddPasses = 0
                addLog("🔄 Rabbit: تمت الإضافة، ننتظر استقرار الشاشة...")
                // تمت زيادة وقت الانتظار هنا لـ 2.5 ثانية لضمان انتهاء حركة شريط (Recommended for you) تماماً
                Thread.sleep(2500)
                continue
            }
            
            Thread.sleep(700)
            if (currentScreenContent == previousScreenContent) {
                emptyScrolls++
                if (emptyScrolls >= 3) {
                    addLog("🏁 Rabbit: انتهى محتوى العروض.")
                    break 
                }
            } else {
                emptyScrolls = 0
            }
            previousScreenContent = currentScreenContent
            totalScrolls++
            swipeUp(0.75f, 0.60f, 1000L) 
            Thread.sleep(1300) 
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
                openCartAndSendReport(token, chatId, foundDeals, isCartAlreadyOpen = true, appType = "RABBIT")
            } else {
                sendChunksAsText(token, chatId, foundDeals.map { it.dealText }.chunked(5), appType = "RABBIT")
            }
        } else {
            addLog("📉 لم يتم العثور على عروض مناسبة في رابيت حالياً.")
        }
    }
    
    private fun analyzeRabbitDeals(
        nodesList: List<NodeData>, minDiscount: Int, deals: MutableList<DealData>, 
        processed: MutableSet<String>, historyMap: MutableMap<String, Long>, 
        cooldownMillis: Long, prefs: SharedPreferences
    ): Boolean {
        val processedCards = mutableSetOf<String>()
        
        for (current in nodesList) {
            val badgeText = current.text.trim()
            val discountMatch = Regex("""[-]?\s*(\d{1,2})\s*[%٪]-?""").find(badgeText) ?: continue
            val discountPercent = discountMatch.groupValues[1].toIntOrNull() ?: continue
            if (discountPercent < minDiscount) continue
            
            val productCard = findRabbitProductCard(current.node)
            if (productCard == null) continue
            
            val cardRect = getRect(productCard)
            val cardKey = "${cardRect.left}:${cardRect.top}:${cardRect.right}:${cardRect.bottom}"
            if (!processedCards.add(cardKey)) continue
            
            val productInfo = extractRabbitProductInfo(productCard)
            if (productInfo == null || productInfo.name.length < 3) continue
            
            val productKey = normalizeRabbitText(productInfo.name).lowercase(java.util.Locale.ROOT)
            
            // التعديل: تم نقل اللوج بعد هذه السطور حتى لا يتم تسجيل المنتج إلا إذا كان جديداً ولم يتم تسجيله من قبل
            if (processed.contains(productKey)) continue
            val lastSent = historyMap[productKey]
            if (lastSent != null && System.currentTimeMillis() - lastSent < cooldownMillis) continue
            
            val attempts = rabbitAddAttempts[productKey] ?: 0
            if (attempts >= 3) continue

            // مكان اللوج الصحيح: يظهر مرة واحدة فقط لكل منتج جديد يكتشفه البوت
            addLog("🔎 يحقق شرط الخصم ($discountPercent%): ${productInfo.name} - السعر: ${productInfo.salePrice ?: "غير متاح"}")

            var plusClicked = false
            
            val addButton = findRabbitAddButton(productCard)
            if (addButton != null) {
                plusClicked = clickNodeCenter(addButton)
            }

            if (!plusClicked) {
                rabbitAddAttempts[productKey] = attempts + 1
                addLog("⚠️ Rabbit: تعذر النقر الآمن لـ ${productInfo.name} (محاولة ${attempts + 1}/3)")
                continue
            }
            
            rabbitAddAttempts.remove(productKey)
            
            val displayName = buildString {
                append(productInfo.name)
                if (!productInfo.unit.isNullOrBlank()) {
                    append(" (").append(productInfo.unit).append(")")
                }
            }
            
            val dealText = buildString {
                append(displayName)
                if (!productInfo.salePrice.isNullOrBlank()) {
                    append(" ب ").append(productInfo.salePrice).append(" جنيه")
                }
                append(" بخصم ").append(discountPercent).append("%")
            }
            
            deals.add(DealData(originalName = productInfo.name, dealText = dealText))
            processed.add(productKey)
            historyMap[productKey] = System.currentTimeMillis()
            saveHistoryMap(prefs, historyMap)
            addLog("✅ Rabbit: تمت إضافة عرض: $dealText")
            
            return true 
        }
        return false
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
                openCartAndSendReport(token, chatId, foundDeals, isCartAlreadyOpen = false, appType = "BREADFAST")
            } else {
                sendChunksAsText(token, chatId, foundDeals.filter { it.originalName != "منتج مميز" }.map { it.dealText }.chunked(5), appType = "BREADFAST")
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
                    val productCard = findProductCardBreadfast(priceNode)
                    if (productCard != null && forceClickAddButtonBreadfast(productCard)) addedItemsCount++
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

    private fun findProductCardBreadfast(priceNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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

    private fun forceClickAddButtonBreadfast(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val id = node.viewIdResourceName ?: ""
        val combined = "$text $desc $id".lowercase(java.util.Locale.ROOT)

        if (combined.contains("favorite") || combined.contains("مفضلة")) return false

        val isCartAddButton = text == "+" || desc == "+" || combined.contains("add to cart") || combined.contains("أضف إلى السلة")
        if (isCartAddButton) return clickNodeCenter(node)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (node.isClickable && combined.isBlank() && rect.width() in 40..250 && rect.height() in 40..250) {
            return clickNodeCenter(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (forceClickAddButtonBreadfast(child)) return true
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
            return clickNodeCenter(node)
        } catch (e: Exception) { return false }
    }

    private fun clickNodeCenter(node: AccessibilityNodeInfo): Boolean {
        try {
            val rect = getRect(node)
            if (rect.isEmpty || rect.width() < 10 || rect.height() < 10) return false
            val success = tapScreenPoint(rect.centerX().toFloat(), rect.centerY().toFloat())
            if (success) Thread.sleep(800) 
            return success
        } catch (e: Exception) { return false }
    }

    
    private fun normalizeForCartMatch(text: String): String {
        return text
            .lowercase(java.util.Locale.ROOT)
            .replace("\n", " ")
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findDealPositionInCart(deal: DealData, visibleNodes: List<NodeData>): PositionedDeal? {
        val words = normalizeForCartMatch(deal.originalName)
            .split(Regex("\\s+"))
            .filter {
                it.length >= 3 &&
                !it.matches(Regex("\\d+")) &&
                it !in setOf("جم", "قطعه", "قطعة", "pcs", "pc", "g", "gm", "kg", "ml", "egp", "جنيه")
            }
            .distinct()
            .take(6)

        if (words.isEmpty()) return null

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestArea = Int.MAX_VALUE

        for (nodeData in visibleNodes) {
            val nodeText = normalizeForCartMatch(nodeData.text)
            if (nodeText.isBlank()) continue

            val score = words.count { nodeText.contains(it) }
            val rect = getRect(nodeData.node)
            val area = rect.width() * rect.height()

            if (score > bestScore || (score == bestScore && score > 0 && area < bestArea)) {
                bestScore = score
                bestArea = area
                bestNode = nodeData.node
            }
        }

        val requiredScore = when {
            words.size >= 3 -> 2
            words.size == 2 -> 2
            else -> 1
        }

        if (bestNode == null || bestScore < requiredScore) return null

        return PositionedDeal(deal, getRect(bestNode).top, bestScore)
    }

    private fun cartVisibleNodes(): MutableList<NodeData> {
        val nodes = mutableListOf<NodeData>()
        val metrics = resources.displayMetrics
        extractNodes(
            rootInActiveWindow,
            nodes,
            metrics.heightPixels * 0.20f,
            metrics.heightPixels * 0.82f
        )
        return nodes
    }

    private fun cartScreenSignature(nodes: List<NodeData>): String {
        return nodes
            .map {
                val rect = getRect(it.node)
                "${normalizeForCartMatch(it.text)}@${rect.top}"
            }
            .filter { !it.startsWith("@") }
            .distinct()
            .joinToString("|")
    }

    private fun moveCartAndWait(previousSignature: String): Boolean {
        // يبدأ السحب في قائمة المنتجات وليس فوق بانر التوصيل السفلي.
        swipeUp(0.72f, 0.30f, 1100L)

        repeat(10) {
            Thread.sleep(250)
            val newSignature = cartScreenSignature(cartVisibleNodes())
            if (newSignature.isNotBlank() && newSignature != previousSignature) {
                return true
            }
        }

        return false
    }

    @TargetApi(30)
private fun openCartAndSendReport(token: String, chatId: String, deals: List<DealData>, isCartAlreadyOpen: Boolean = false, appType: String = "BREADFAST") {
    addLog("🚀 تجهيز تقرير لـ ${deals.size} منتجات...")
    
    val dealsListText = deals.joinToString(" | ") { it.originalName }
    addLog("📋 المنتجات المحولة للسلة: $dealsListText")

    if (!isCartAlreadyOpen) {
        val cartNode = findNodeByText(rootInActiveWindow, "Cart")
            ?: findNodeByText(rootInActiveWindow, "السلة")
            ?: findNodeByText(rootInActiveWindow, "الكيس")

        if (cartNode != null) {
            clickNodeSafely(cartNode)
            Thread.sleep(5000)
        }
    }

    if (deals.isEmpty()) return

    if (appType == "RABBIT") {
        var loopCount = 0
        var lastSignature = ""
        var unchangedScreens = 0
        val sentProducts = mutableSetOf<String>()

        while (loopCount < 20) {
            loopCount++
            val visibleNodes = cartVisibleNodes()
            val signature = cartScreenSignature(visibleNodes)

            if (signature.isBlank()) {
                addLog("⚠️ تعذر قراءة أي عناصر في السلة (الدورة $loopCount).")
                break
            }

            if (signature == lastSignature) {
                unchangedScreens++
                if (unchangedScreens >= 2 || !smartScrollRabbitCart(parseRabbitCartProducts(visibleNodes), visibleNodes, signature)) {
                    addLog("🏁 لم تتغير شاشة السلة بعد السحب، سيتم إنهاء التقرير.")
                    break
                }
                continue
            }
            unchangedScreens = 0

            val parsedProducts = parseRabbitCartProducts(visibleNodes)
            val currentBatchText = mutableListOf<String>()

            for (product in parsedProducts) {
                val uniqueKey = product.textDescription.substringBefore(" ب ").trim()
                if (!sentProducts.contains(uniqueKey)) {
                    currentBatchText.add(product.textDescription)
                    sentProducts.add(uniqueKey)
                    addLog("🛒 نجح استخراج المنتج من السلة: ${product.textDescription}")
                }
            }

            if (currentBatchText.isEmpty()) {
                addLog("⚠️ لم يتم العثور على منتجات جديدة هنا. جاري السحب للأسفل...")
                lastSignature = signature
                if (!smartScrollRabbitCart(parsedProducts, visibleNodes, signature)) {
                    addLog("🏁 السلة انتهت، خروج.")
                    break
                }
                continue
            }

            val bitmap = takeScreenshotSync()
            var imageBytes: ByteArray? = null

            if (bitmap != null) {
                try {
                    val topCrop = (bitmap.height * 0.20).toInt()
                    val bottomCrop = (bitmap.height * 0.18).toInt()
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, topCrop, bitmap.width, bitmap.height - topCrop - bottomCrop)
                    val stream = ByteArrayOutputStream()
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    imageBytes = stream.toByteArray()
                    croppedBitmap.recycle()
                    bitmap.recycle()
                } catch (e: Exception) {
                    addLog("❌ خطأ قص الصورة: ${e.message}")
                }
            }

            // صيغة الوصف الجديدة المطابقة لطلبك
            val prefix = if (loopCount == 1) "عروض ممتازة علي ابلكيشن رابيت\n\n" else "عروض ممتازة علي ابلكيشن رابيت\n\n"
            val caption = (prefix + currentBatchText.joinToString("\n\n")).take(1020)

            if (imageBytes != null) {
                addLog("📸 تم التقاط صورة السلة. جاري الإرسال...")
                sendTelegramPhotoMultipart(token, chatId, imageBytes, caption)
            } else {
                addLog("⚠️ فشل التقاط الصورة، جاري إرسال تقرير نصي...")
                sendTelegramMessage(token, chatId, caption)
            }

            lastSignature = signature
            if (!smartScrollRabbitCart(parsedProducts, visibleNodes, signature)) break
        }
        return
    }

    // ==========================================
    // مسار تطبيق بريدفاست (بدون أي تعديل)
    // ==========================================
    deals.forEach { it.isAssigned = false }
    var loopCountBF = 0
    var lastSignatureBF = ""
    var unchangedScreensBF = 0

    while (deals.any { !it.isAssigned } && loopCountBF < 20) {
        loopCountBF++
        val visibleNodes = cartVisibleNodes()
        val signature = cartScreenSignature(visibleNodes)

        if (signature.isBlank()) break
        if (signature == lastSignatureBF) {
            unchangedScreensBF++
            if (unchangedScreensBF >= 2 || !moveCartAndWait(signature)) break
            continue
        }
        unchangedScreensBF = 0

        val currentBatch = deals
            .asSequence()
            .filter { !it.isAssigned }
            .mapNotNull { findDealPositionInCart(it, visibleNodes) }
            .sortedBy { it.top }
            .take(5)
            .map { it.deal }
            .toList()

        if (currentBatch.isEmpty()) {
            lastSignatureBF = signature
            if (!moveCartAndWait(signature)) break
            continue
        }

        val bitmap = takeScreenshotSync()
        var imageBytes: ByteArray? = null

        if (bitmap != null) {
            try {
                val topCrop = (bitmap.height * 0.20).toInt()
                val bottomCrop = (bitmap.height * 0.18).toInt()
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, topCrop, bitmap.width, bitmap.height - topCrop - bottomCrop)
                val stream = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                imageBytes = stream.toByteArray()
                croppedBitmap.recycle()
                bitmap.recycle()
            } catch (e: Exception) { }
        }

        val prefix = if (loopCountBF == 1) "عروض ممتازة\n\n" else "ودول كمان\n\n"
        val caption = (prefix + currentBatch.joinToString("\n\n") { it.dealText }).take(1020)

        if (imageBytes != null) {
            addLog("📸 تم التقاط صورة بريدفاست. جاري الإرسال...")
            sendTelegramPhotoMultipart(token, chatId, imageBytes, caption)
        } else {
            sendTelegramMessage(token, chatId, caption)
        }

        currentBatch.forEach { it.isAssigned = true }
        lastSignatureBF = signature

        if (deals.any { !it.isAssigned }) {
            if (!moveCartAndWait(signature)) break
        }
    }
}


    private fun sendChunksAsText(token: String, chatId: String, chunks: List<List<String>>, appType: String = "BREADFAST") {
        for (i in chunks.indices) {
            val prefix = if (appType == "RABBIT") {
                if (i == 0) "عروض ممتازة على Rabbit 🐰\n\n" else "وعروض Rabbit إضافية 🐰\n\n"
            } else {
                if (i == 0) "عروض ممتازة\n" else "ودول كمان\n"
            }
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
            // استدعاء دالة التصوير مباشرة بدون إسنادها لمتغير لأنها Void (Unit)
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            if (bitmap == null) {
                                addLog("❌ Screenshot: تم الاستلام لكن فشل تحويل الصورة إلى Bitmap.")
                            } else {
                                addLog("✅ Screenshot: تم الالتقاط ${bitmap!!.width}x${bitmap!!.height}")
                            }
                        } catch (e: Exception) {
                            addLog("❌ Screenshot conversion error: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        addLog("❌ Screenshot failed. Error code: $errorCode")
                        latch.countDown()
                    }
                }
            )
            
            val completed = latch.await(7, TimeUnit.SECONDS)
            if (!completed) {
                addLog("❌ Screenshot: انتهت المهلة بدون استجابة.")
            }
        } catch (e: Exception) {
            addLog("❌ Screenshot exception: ${e.message}")
        } finally {
            executor.shutdown()
        }
        return bitmap
    }

    private fun sendTelegramPhotoMultipart(token: String, chatId: String, imageBytes: ByteArray, caption: String) {
        var connection: HttpURLConnection? = null
        try {
            if (token.isBlank() || chatId.isBlank()) {
                addLog("❌ Telegram: التوكن أو Chat ID فارغ، راجع الإعدادات.")
                return
            }
            
            addLog("📤 Telegram: جاري رفع صورة السلة للتليجرام (حجم التقرير: ${caption.length} حرف)...")

            val boundary = "Boundary-${System.currentTimeMillis()}"
            val url = URL("https://api.telegram.org/bot${token.trim()}/sendPhoto")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            
            val outputStream = DataOutputStream(connection.outputStream)
            
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
            outputStream.write((chatId.trim() + "\r\n").toByteArray(Charsets.UTF_8))
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n")
            outputStream.write((caption + "\r\n").toByteArray(Charsets.UTF_8))
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"reply_markup\"\r\n\r\n")
            outputStream.write((replyMarkup + "\r\n").toByteArray(Charsets.UTF_8))
            outputStream.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"cart.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
            outputStream.write(imageBytes)
            outputStream.writeBytes("\r\n--$boundary--\r\n")
            outputStream.flush()
            outputStream.close()
            
            val responseCode = connection.responseCode
            val responseText = try {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "No response text" }
            
            if (responseCode in 200..299) {
                addLog("✅ Telegram: تم النشر بنجاح! (HTTP $responseCode)")
            } else {
                addLog("❌ Telegram Error ($responseCode): ${responseText.take(200)}")
            }
        } catch (e: Exception) { 
            addLog("❌ Telegram Exception: ${e.message}") 
        } finally {
            connection?.disconnect()
        }
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        var connection: HttpURLConnection? = null
        try {
            addLog("📤 Telegram: جاري إرسال تقرير نصي بديل (بدون صورة)...")
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            
            val body = buildString {
                append("chat_id=").append(URLEncoder.encode(chatId, "UTF-8"))
                append("&text=").append(URLEncoder.encode(text, "UTF-8"))
                append("&reply_markup=").append(URLEncoder.encode(replyMarkup, "UTF-8"))
            }
            
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            
            val responseCode = connection.responseCode
            val responseText = try {
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            
            if (responseCode in 200..299) {
                addLog("✅ Telegram: تم إرسال التقرير النصي بنجاح (HTTP $responseCode).")
            } else {
                addLog("❌ Telegram text failed: HTTP $responseCode - ${responseText.take(250)}")
            }
        } catch (e: Exception) {
            addLog("❌ Telegram text exception: ${e.message}")
        } finally {
            connection?.disconnect()
        }
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

    private fun normalizedText(node: AccessibilityNodeInfo): String {
        return (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
    }

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
