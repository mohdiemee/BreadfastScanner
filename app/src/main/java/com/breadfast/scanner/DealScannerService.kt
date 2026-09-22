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
private data class CartProduct(val textDescription: String, val topY: Int, val bottomY: Int)

private data class RabbitCartProduct(
    val name: String,
    val price: String,
    val oldPrice: String?,
    val discount: Int?,
    val topY: Int,
    val bottomY: Int
) {
    fun toDealText(): String {
        val discountText =
            discount?.let { value ->
                " بخصم $value%"
            } ?: ""

        // فصل اسم المنتج عن الوحدة (التي تكون دائماً بين قوسين في نهاية الاسم)
        val lastOpenParen = name.lastIndexOf("(")
        val lastCloseParen = name.lastIndexOf(")")
        
        val baseName: String
        val unitStr: String
        
        if (lastOpenParen != -1 && lastCloseParen != -1 && lastCloseParen > lastOpenParen) {
            baseName = name.substring(0, lastOpenParen).trim()
            unitStr = " " + name.substring(lastOpenParen, lastCloseParen + 1)
        } else {
            baseName = name
            unitStr = ""
        }

        // استبدال الرموز الخاصة لتجنب أعطال التنسيق (HTML Parse) لاسم المنتج فقط
        val safeName = baseName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        // وضع اسم المنتج فقط داخل كود الـ Monospace، والوحدة بالخارج
        return "<code>$safeName</code>$unitStr ب $price جنيه$discountText"
    }
}


private data class BreadfastCartProduct(
    val name: String,
    val price: String,
    val topY: Int,
    val bottomY: Int
)

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


    
    override fun onInterrupt() {
    addLog(
        "⚠️ تم إيقاف خدمة Accessibility."
    )
}

private fun parseBreadfastCartProductsFromAccessibility(): List<BreadfastCartProduct> {
    val nodes = cartVisibleNodes()
    val products = mutableListOf<BreadfastCartProduct>()

    val recommendedNode = nodes.find {
        it.text.contains("يعجب", ignoreCase = true) ||
            it.text.contains("الناس", ignoreCase = true)
    }

    val limitY = recommendedNode
        ?.node
        ?.let { getRect(it).top }
        ?: Int.MAX_VALUE

    val validNodes = nodes.filter {
        getRect(it.node).bottom < limitY
    }

    val priceNodes = validNodes.filter {
        val text = normalizeBreadfastText(it.text)

        text.contains("ج.م") &&
            !text.contains("باقي", ignoreCase = true) &&
            !text.contains("رسوم", ignoreCase = true) &&
            !text.contains("متابعة", ignoreCase = true)
    }

    for (priceNode in priceNodes) {
        val priceRect = getRect(priceNode.node)

        val nameCandidates = validNodes.filter {
            val nameRect = getRect(it.node)
            val text = normalizeBreadfastText(it.text)

            nameRect.bottom <= priceRect.bottom + 60 &&
                nameRect.top >= priceRect.top - 350 &&
                text.length > 3 &&
                !text.contains("ج.م", ignoreCase = true) &&
                !text.matches(Regex("""\d+""")) &&
                !text.contains("السلة", ignoreCase = true) &&
                !text.contains("نقطة", ignoreCase = true) &&
                !text.contains("مسح الكل", ignoreCase = true) &&
                !text.contains("product_", ignoreCase = true) &&
                !text.contains("_image", ignoreCase = true) &&
                !text.contains("cartitem_", ignoreCase = true) &&
                !text.contains("cart_item", ignoreCase = true) &&
                !text.contains("cart item", ignoreCase = true) &&
                !isInvalidBreadfastProductName(text)
        }

        val nameNode = nameCandidates.minByOrNull {
            kotlin.math.abs(getRect(it.node).bottom - priceRect.top)
        }

        if (nameNode == null) {
            addLog(
                "⚠️ Breadfast: لم يتم العثور على اسم صالح للسعر: " +
                    priceNode.text
            )
            continue
        }

        val name = normalizeBreadfastText(nameNode.text)

        if (isInvalidBreadfastProductName(name)) {
            addLog(
                "⚠️ Breadfast: تم تجاهل اسم برمجي: $name"
            )
            continue
        }

        val rawPriceText = normalizeBreadfastText(priceNode.text)
        val price = normalizeBreadfastPrice(rawPriceText)

        if (price == null) {
            addLog(
                "⚠️ Breadfast: تعذر استخراج السعر من: " +
                    rawPriceText
            )
            continue
        }

        products.add(
            BreadfastCartProduct(
                name = name,
                price = price,
                topY = minOf(
                    getRect(nameNode.node).top,
                    priceRect.top
                ),
                bottomY = maxOf(
                    getRect(nameNode.node).bottom,
                    priceRect.bottom
                )
            )
        )
    }

    return products
        .filterNot {
            isInvalidBreadfastProductName(it.name)
        }
        .distinctBy {
            normalizeProductKey(it.name)
        }
        .sortedBy {
            it.topY
        }
}

private fun normalizeBreadfastText(text: String): String {
    return text
        .replace("\u200E", "")
        .replace("\u200F", "")
        .replace("\u202A", "")
        .replace("\u202B", "")
        .replace("\u202C", "")
        .replace("\u2060", "")
        .replace("\u00A0", " ")
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun isInvalidBreadfastProductName(text: String): Boolean {
    val normalized = normalizeBreadfastText(text)
        .lowercase(java.util.Locale.ROOT)

    return normalized.contains("cartitem_") ||
        normalized.contains("cart_item") ||
        normalized.contains("cart item") ||
        normalized.contains("product_") ||
        normalized.contains("_image") ||
        normalized.matches(Regex("""(?:cartitem|cart_item|product)[_\s-]?\d+"""))
}

private fun formatBreadfastPrice(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        String.format(
            java.util.Locale.US,
            "%.2f",
            value
        )
    }
}

private fun normalizeBreadfastPrice(rawText: String): String? {
    var cleaned = normalizeBreadfastText(rawText)
        .replace("ج.م", "", ignoreCase = true)
        .replace("ج م", "", ignoreCase = true)
        .replace("جنيه", "", ignoreCase = true)
        .replace("EGP", "", ignoreCase = true)
        .trim()

    cleaned = cleaned.replace(",", ".")

    val directValue = cleaned
        .replace(Regex("""[^\d.]"""), "")
        .toDoubleOrNull()

    if (directValue != null) {
        /*
         * Breadfast قد يعيد 59.20 بشكل صحيح،
         * أو يعيده 5920 بدون الفاصلة.
         */
        if (
            !cleaned.contains(".") &&
            directValue >= 1000.0 &&
            directValue <= 500000.0
        ) {
            return formatBreadfastPrice(directValue / 100.0)
        }

        return formatBreadfastPrice(directValue)
    }

    val digitsOnly = cleaned.replace(Regex("""[^\d]"""), "")

    if (digitsOnly.length >= 4) {
        val recovered = digitsOnly.toDoubleOrNull()?.div(100.0)

        if (recovered != null && recovered in 1.0..5000.0) {
            return formatBreadfastPrice(recovered)
        }
    }

    return null
}



private fun calculateDiscount(
    oldPrice: Double?,
    newPrice: Double?
): Int? {
    if (oldPrice == null || newPrice == null) {
        return null
    }

    if (oldPrice <= newPrice || oldPrice <= 0.0) {
        return null
    }

    return (
        ((oldPrice - newPrice) / oldPrice) * 100.0
        ).toInt()
}


    
    private fun normalizeProductKey(text: String): String {
        return text
            .lowercase(java.util.Locale.ROOT)
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("\u202A", "")
            .replace("\u202B", "")
            .replace("\u202C", "")
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    

    private fun formatPrice(value: Double): String {
        // التعديل: إرجاع السعر كعدد صحيح دائماً لإلغاء القروش والكسور تماماً
        return value.toInt().toString()
    }




    private fun parseRabbitCartProductsFromAccessibility(): List<RabbitCartProduct> {
    val root = rootInActiveWindow

    if (root == null) {
        addLog(
            "❌ Rabbit Cart Accessibility: " +
                "rootInActiveWindow=null"
        )
        return emptyList()
    }

    val metrics = resources.displayMetrics

    data class CardCandidate(
        val text: String,
        val rect: Rect,
        val node: AccessibilityNodeInfo
    )

    val candidates =
        mutableListOf<CardCandidate>()

    fun collect(node: AccessibilityNodeInfo?) {
        if (node == null) {
            return
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val rawText = (
            node.text?.toString()
                ?: node.contentDescription?.toString()
                ?: ""
            )
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val isInProductArea =
            rect.top >= metrics.heightPixels * 0.16f &&
                rect.bottom <= metrics.heightPixels * 0.86f

        val looksLikeCard =
            node.isClickable &&
                rect.width() >= metrics.widthPixels * 0.80f &&
                rect.height() >= 170 &&
                rawText.length >= 10

        if (
            isInProductArea &&
            looksLikeCard
        ) {
            candidates.add(
                CardCandidate(
                    text = rawText,
                    rect = Rect(rect),
                    node = node
                )
            )
        }

        for (index in 0 until node.childCount) {
            collect(node.getChild(index))
        }
    }

    collect(root)

    fun normalizeCardText(
        text: String
    ): String {
        return text
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("\u202A", "")
            .replace("\u202B", "")
            .replace("\u202C", "")
            .replace("\u2060", "")
            .replace("\u00A0", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun extractUnit(
        text: String
    ): String? {
        return Regex(
            """\b\d+(?:[.,]\d+)?\s*(""" +
                """قطعة|قطعه|جم|كجم|مل|لتر|""" +
                """علكة|pcs?|pc|g|gm|kg|ml|l""" +
                """)\b"""
        )
            .find(
                normalizeCardText(text)
            )
            ?.value
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
    }

    fun removeNonPriceNumbers(
        text: String,
        unit: String?
    ): String {
        var result =
            normalizeCardText(text)

        if (!unit.isNullOrBlank()) {
            result =
                result.replace(
                    unit,
                    " ",
                    ignoreCase = true
                )
        }

        return result
            .replace("جنيه", " ", ignoreCase = true)
            .replace("EGP", " ", ignoreCase = true)
            .replace(Regex("""[+|=_<>~`]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun extractNumericTokens(
        text: String
    ): List<String> {
        return Regex(
            """\d+(?:[.,]\d{1,2})?"""
        )
            .findAll(text)
            .map { match ->
                match.value
            }
            .toList()
    }

    fun tokenToDouble(
        token: String
    ): Double? {
        return token
            .replace(",", ".")
            .toDoubleOrNull()
            ?.takeIf { value ->
                value >= 1.0 &&
                    value <= 5000.0
            }
    }

    fun mergePriceTokens(
        tokens: List<String>
    ): List<Double> {
        val result =
            mutableListOf<Double>()

        var index = 0

        while (index < tokens.size) {
            val current =
                tokens[index]

            val currentDigits =
                current.replace(
                    Regex("""\D"""),
                    ""
                )

            val next =
                tokens.getOrNull(index + 1)

            val nextDigits =
                next
                    ?.replace(
                        Regex("""\D"""),
                        ""
                    )
                    .orEmpty()

            if (
                nextDigits.length == 2 &&
                !current.contains(".") &&
                !current.contains(",") &&
                currentDigits.length <= 4
            ) {
                val merged =
                    "$currentDigits.$nextDigits"
                        .toDoubleOrNull()

                if (
                    merged != null &&
                    merged >= 1.0 &&
                    merged <= 5000.0
                ) {
                    result.add(merged)
                    index += 2
                    continue
                }
            }

            tokenToDouble(current)
                ?.let { value ->
                    result.add(value)
                }

            index++
        }

        return result
    }

    fun chooseCardPrices(
        text: String,
        unit: String?
    ): Pair<Double?, Double?> {
        val cleaned =
            removeNonPriceNumbers(
                text = text,
                unit = unit
            )

        val tokens =
            extractNumericTokens(cleaned)

        if (tokens.isEmpty()) {
            return null to null
        }

        val merged =
            mergePriceTokens(tokens)

        if (merged.isEmpty()) {
            return null to null
        }

        val candidates =
            merged
                .distinct()
                .filter { value ->
                    value >= 2.0 &&
                        value <= 5000.0
                }

        if (candidates.isEmpty()) {
            return null to null
        }

        val currentPrice =
            candidates.getOrNull(
                candidates.size - 2
            ) ?: candidates.last()

        val oldPrice =
            candidates.lastOrNull()
                ?.takeIf { value ->
                    value > currentPrice
                }

        return oldPrice to currentPrice
    }

    fun removeProductData(
        text: String,
        unit: String?
    ): String {
        var result =
            normalizeCardText(text)

        if (!unit.isNullOrBlank()) {
            result =
                result.replace(
                    unit,
                    " ",
                    ignoreCase = true
                )
        }

        return result
            .replace(
                Regex(
                    """\d+(?:[.,]\d{1,2})?"""
                ),
                " "
            )
            .replace("جنيه", " ", ignoreCase = true)
            .replace("EGP", " ", ignoreCase = true)
            .replace(Regex("""[+|=_<>~`]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    val ignoredPhrases =
        listOf(
            "اختيار جامد",
            "وفرت",
            "يلا ندفع",
            "يللا ندفع",
            "توصيل ببلاش",
            "واو ليك",
            "فضي الكيس",
            "السلة",
            "الكيس"
        )

    val rawCards =
        candidates
            .distinctBy { candidate ->
                "${candidate.rect.left}|" +
                    "${candidate.rect.top}|" +
                    "${candidate.rect.right}|" +
                    "${candidate.rect.bottom}"
            }
            .sortedBy { candidate ->
                candidate.rect.top
            }

    val products =
        mutableListOf<RabbitCartProduct>()

    rawCards.forEach { card ->
        val cardText =
            normalizeCardText(card.text)

        if (
            ignoredPhrases.any { phrase ->
                cardText.contains(
                    phrase,
                    ignoreCase = true
                )
            }
        ) {
            return@forEach
        }

        val unit =
            extractUnit(cardText)

        val prices =
            chooseCardPrices(
                text = cardText,
                unit = unit
            )

        val oldPrice =
            prices.first

        val newPrice =
            prices.second

        if (newPrice == null) {
            addLog(
                "⚠️ Rabbit Accessibility: " +
                    "تعذر استخراج السعر من: " +
                    cardText.take(180)
            )
            return@forEach
        }

        var productName =
            removeProductData(
                text = cardText,
                unit = unit
            )

        productName =
            productName
                .replace(Regex("""\b\d+\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

        if (
            productName.length < 4 ||
            productName.count { char ->
                char.isLetter()
            } < 3
        ) {
            return@forEach
        }

        if (!unit.isNullOrBlank()) {
            productName =
                "$productName ($unit)"
        }

        val discount =
            calculateDiscount(
                oldPrice = oldPrice,
                newPrice = newPrice
            )?.takeIf { value ->
                value in 1..95
            }

        val product =
            RabbitCartProduct(
                name = productName,
                price = formatPrice(newPrice),
                oldPrice = oldPrice?.let { value ->
                    formatPrice(value)
                },
                discount = discount,
                topY = card.rect.top,
                bottomY = card.rect.bottom
            )

        products.add(product)

        addLog(
            "✅ Rabbit Accessibility product: " +
                product.toDealText() +
                " | y=${card.rect.top}-${card.rect.bottom}"
        )
    }

    val finalProducts =
        products
            .distinctBy { product ->
                normalizeProductKey(
                    product.name
                )
            }
            .sortedBy { product ->
                product.topY
            }

    addLog(
        "📊 Rabbit Accessibility parser result: " +
            "${finalProducts.size} products"
    )

    return finalProducts
}
    
    private fun smartScrollRabbitCart(
    previousSignature: String
): Boolean {
    val metrics = resources.displayMetrics

    val startY = metrics.heightPixels * 0.82f
    val endY = metrics.heightPixels * 0.28f

    addLog(
        "↕️ Rabbit scroll: " +
            "start=${startY.toInt()}, " +
            "end=${endY.toInt()}"
    )

    val path = Path().apply {
        moveTo(
            metrics.widthPixels * 0.50f,
            startY
        )

        lineTo(
            metrics.widthPixels * 0.50f,
            endY
        )
    }

    val dispatched = dispatchGesture(
        GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    1800L
                )
            )
            .build(),
        null,
        null
    )

    if (!dispatched) {
        addLog("❌ dispatchGesture فشل.")
        return false
    }

    Thread.sleep(1800)

    repeat(20) {
        Thread.sleep(300)

        val newSignature = cartScreenSignature(
            cartVisibleNodes()
        )

        if (
            newSignature.isNotBlank() &&
            newSignature != previousSignature
        ) {
            addLog("✅ تغيرت شاشة السلة.")
            return true
        }
    }

    addLog(
        "⚠️ لم تتغير الشاشة بعد التمرير؛ " +
            "قد تكون وصلت إلى نهاية السلة."
    )

    return false
}

    
@TargetApi(30)
private fun openCartAndSendReport(
    token: String,
    chatId: String,
    deals: List<DealData>,
    isCartAlreadyOpen: Boolean = false,
    appType: String = "BREADFAST"
) {
    addLog(
        "🚀 تجهيز تقرير لـ ${deals.size} منتجات..."
    )

    if (token.isBlank()) {
        addLog("❌ BOT_TOKEN فارغ.")
        return
    }

    if (chatId.isBlank()) {
        addLog("❌ CHAT_ID فارغ.")
        return
    }

    if (deals.isEmpty()) {
        addLog("⚠️ لا توجد عروض لإرسالها.")
        return
    }

    if (!isCartAlreadyOpen) {
        val cartNode =
            findNodeByText(rootInActiveWindow, "Cart")
                ?: findNodeByText(rootInActiveWindow, "السلة")
                ?: findNodeByText(rootInActiveWindow, "الكيس")

        if (cartNode == null) {
            addLog("❌ لم يتم العثور على زر السلة.")
            return
        }

        if (!clickNodeSafely(cartNode)) {
            addLog("❌ فشل فتح السلة.")
            return
        }

        Thread.sleep(5000)
    }

    if (!appType.equals("RABBIT", ignoreCase = true)) {
        sendBreadfastCartReport(
            token = token,
            chatId = chatId,
            deals = deals
        )
        return
    }

    val sentProducts = mutableSetOf<String>()

    var lastSignature = ""
    var unchangedScreens = 0
    var loopCount = 0

    val maxLoops = 30

    while (loopCount < maxLoops) {
        loopCount++

        val visibleNodes =
            cartVisibleNodes()

        val signature =
            cartScreenSignature(
                visibleNodes
            )

        addLog(
            "🔍 Rabbit Cart Accessibility: " +
                "دورة=$loopCount, " +
                "nodes=${visibleNodes.size}, " +
                "signatureLength=${signature.length}, " +
                "sent=${sentProducts.size}"
        )

        if (signature.isBlank()) {
            addLog(
                "⚠️ توقيع السلة فارغ؛ " +
                    "لن يتم إرسال هذه الدورة."
            )

            if (loopCount >= 3) {
                addLog(
                    "🏁 توقيع السلة فارغ عدة مرات؛ " +
                        "إنهاء التقرير."
                )
                break
            }

            Thread.sleep(1500)
            continue
        }

        val screenChanged =
            signature != lastSignature ||
                loopCount == 1

        if (!screenChanged) {
            unchangedScreens++

            addLog(
                "⚠️ الشاشة لم تتغير: " +
                    "$unchangedScreens/3"
            )

            if (unchangedScreens >= 3) {
                addLog(
                    "🏁 وصلت إلى نهاية السلة."
                )
                break
            }
        } else {
            unchangedScreens = 0
        }

        if (screenChanged) {
            val allProducts = try {
                parseRabbitCartProductsFromAccessibility()
            } catch (e: Exception) {
                addLog(
                    "❌ خطأ Rabbit Accessibility parser: " +
                        "${e.javaClass.simpleName}: " +
                        e.message
                )
                emptyList()
            }

            val batch =
                allProducts
                    .filter { product ->
                        val key = buildString {
                            append(
                                normalizeProductKey(
                                    product.name
                                )
                            )
                            append("|")
                            append(product.price)
                            append("|")
                            append(product.oldPrice ?: "")
                        }

                        !sentProducts.contains(key)
                    }
                    .sortedBy { product ->
                        product.topY
                    }
                    .take(5)

            if (batch.isEmpty()) {
                addLog(
                    "⚠️ Rabbit Accessibility: " +
                        "لا توجد منتجات جديدة صالحة " +
                        "في الشاشة الحالية."
                )
            } else {
                addLog(
                    "📷 التقاط Screenshot للشاشة الحالية..."
                )

                val bitmap =
                    takeScreenshotSync()

                val captionPrefix =
                    if (sentProducts.isEmpty()) {
                        "عروض ممتازة علي ابلكيشن رابيت\n\n"
                    } else {
                        "ودي كمان\n\n"
                    }

                val caption = (
                    captionPrefix +
                        batch.joinToString("\n\n") { product ->
                            product.toDealText()
                        }
                    ).take(1020)

                var imageBytes: ByteArray? = null

                if (bitmap != null) {
                    try {
                        val topCrop =
                            (bitmap.height * 0.10f).toInt()

                        val bottomCrop =
                            (bitmap.height * 0.20f).toInt()

                        val cropHeight =
                            bitmap.height -
                                topCrop -
                                bottomCrop

                        if (
                            bitmap.width > 100 &&
                            cropHeight > 100
                        ) {
                            val croppedBitmap =
                                Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    topCrop,
                                    bitmap.width,
                                    cropHeight
                                )

                            val stream =
                                ByteArrayOutputStream()

                            croppedBitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                85,
                                stream
                            )

                            imageBytes =
                                stream.toByteArray()

                            croppedBitmap.recycle()

                            addLog(
                                "🖼️ تم تجهيز الصورة: " +
                                    "${imageBytes.size} bytes"
                            )
                        } else {
                            addLog(
                                "⚠️ أبعاد القص غير صالحة."
                            )
                        }
                    } catch (e: Exception) {
                        addLog(
                            "❌ خطأ تجهيز Screenshot: " +
                                "${e.javaClass.simpleName}: " +
                                e.message
                        )
                    } finally {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                } else {
                    addLog(
                        "⚠️ Screenshot رجع null؛ " +
                            "سيتم إرسال النص فقط."
                    )
                }

                val sentSuccessfully =
                    try {
                        if (
                            imageBytes != null &&
                            imageBytes.isNotEmpty()
                        ) {
                            addLog(
                                "📤 إرسال صورة Telegram..."
                            )

                            sendTelegramPhotoMultipart(
                                token = token,
                                chatId = chatId,
                                imageBytes = imageBytes,
                                caption = caption
                            )
                        } else {
                            addLog(
                                "📤 إرسال النص كبديل..."
                            )

                            sendTelegramMessage(
                                token = token,
                                chatId = chatId,
                                text = caption
                            )
                        }
                    } catch (e: Exception) {
                        addLog(
                            "❌ خطأ Telegram: " +
                                "${e.javaClass.simpleName}: " +
                                e.message
                        )
                        false
                    }

                if (!sentSuccessfully) {
                    addLog(
                        "❌ فشل إرسال الدفعة؛ " +
                            "إيقاف التقرير لمنع التكرار."
                    )
                    break
                }

                batch.forEach { product ->
                    val key = buildString {
                        append(
                            normalizeProductKey(
                                product.name
                            )
                        )
                        append("|")
                        append(product.price)
                        append("|")
                        append(product.oldPrice ?: "")
                    }

                    sentProducts.add(key)
                }

                addLog(
                    "✅ تم إرسال دفعة Rabbit Accessibility. " +
                        "الإجمالي=${sentProducts.size}"
                )
            }
        } else {
            addLog(
                "⚠️ الشاشة لم تتغير؛ " +
                    "لن يتم إرسال Screenshot مكرر."
            )
        }

        lastSignature = signature

        addLog(
            "↕️ محاولة تمرير السلة بعد الدورة " +
                "$loopCount..."
        )

        val moved = try {
            smartScrollRabbitCart(
                previousSignature = signature
            )
        } catch (e: Exception) {
            addLog(
                "❌ خطأ أثناء تمرير السلة: " +
                    "${e.javaClass.simpleName}: " +
                    e.message
            )
            false
        }

        if (!moved) {
            addLog(
                "🏁 تعذر تحريك السلة؛ " +
                    "غالبًا تم الوصول إلى نهايتها."
            )
            break
        }

        Thread.sleep(1200)
    }

    addLog(
        "📊 نتيجة تقرير Rabbit Accessibility: " +
            "${sentProducts.size} منتجات."
    )
}

@TargetApi(30)
private fun sendBreadfastCartReport(
    token: String,
    chatId: String,
    deals: List<DealData>
) {
    var loopCount = 0
    var lastSignature = ""
    var unchangedScreens = 0

    val maxLoops = 30
    val sentProducts = mutableSetOf<String>()

    while (loopCount < maxLoops) {
        loopCount++

        val visibleNodes = cartVisibleNodes()
        val signature = cartScreenSignature(visibleNodes)

        addLog(
            "🔍 Breadfast Cart: دورة=$loopCount, " +
                "nodes=${visibleNodes.size}, " +
                "signatureLength=${signature.length}"
        )

        if (signature.isBlank()) {
            addLog("⚠️ Breadfast: الشاشة فارغة.")

            if (loopCount >= 3) {
                break
            }

            Thread.sleep(1500)
            continue
        }

        val screenChanged =
            signature != lastSignature || loopCount == 1

        if (!screenChanged) {
            unchangedScreens++

            addLog(
                "⚠️ Breadfast: الشاشة لم تتغير: " +
                    "$unchangedScreens/3"
            )

            if (unchangedScreens >= 3) {
                addLog(
                    "🏁 Breadfast: الشاشة لم تعد تتغير."
                )
                break
            }
        } else {
            unchangedScreens = 0
        }

        if (screenChanged) {
            val allProducts = try {
                parseBreadfastCartProductsFromAccessibility()
            } catch (e: Exception) {
                addLog(
                    "❌ خطأ Breadfast Accessibility parser: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                emptyList()
            }

            val batch = allProducts
                .filter { product ->
                    val cleanName = normalizeBreadfastText(product.name)
                    val cleanPrice =
                        normalizeBreadfastPrice(product.price)
                            ?: product.price

                    val key =
                        "${normalizeProductKey(cleanName)}|$cleanPrice"

                    !sentProducts.contains(key) &&
                        !isInvalidBreadfastProductName(cleanName)
                }
                .sortedBy {
                    it.topY
                }
                .take(5)

            if (batch.isEmpty()) {
                addLog(
                    "⚠️ Breadfast: لم يتم العثور على دفعة صالحة."
                )
            } else {
                addLog(
                    "📷 Breadfast: التقاط Screenshot للدفعة " +
                        "${batch.size}..."
                )

                val captionPrefix =
                    if (sentProducts.isEmpty()) {
                        "عروض ممتازة\n\n"
                    } else {
                        "ودول كمان\n\n"
                    }

                val caption =
                    (
                        captionPrefix +
                            batch.joinToString("\n\n") { product ->
                                val matchedDeal = deals.find { deal ->
                                    val dealWords =
                                        normalizeForCartMatch(
                                            deal.originalName
                                        )
                                            .split(Regex("\\s+"))
                                            .filter {
                                                it.length > 2
                                            }

                                    val productWords =
                                        normalizeForCartMatch(
                                            product.name
                                        )
                                            .split(Regex("\\s+"))
                                            .filter {
                                                it.length > 2
                                            }

                                    dealWords
                                        .intersect(productWords.toSet())
                                        .size >= 2
                                }

                                val discountPart =
                                    if (matchedDeal != null) {
                                        Regex(
                                            """بخصم (\d+)%"""
                                        )
                                            .find(
                                                matchedDeal.dealText
                                            )
                                            ?.groupValues
                                            ?.getOrNull(1)
                                            ?.let {
                                                " بخصم $it%"
                                            }
                                            ?: ""
                                    } else {
                                        ""
                                    }

                                val cleanName =
                                    normalizeBreadfastText(
                                        product.name
                                    )

                                val cleanPrice =
                                    normalizeBreadfastPrice(
                                        product.price
                                    ) ?: product.price

                                val safeName =
                                    cleanName
                                        .replace(
                                            "&",
                                            "&amp;"
                                        )
                                        .replace(
                                            "<",
                                            "&lt;"
                                        )
                                        .replace(
                                            ">",
                                            "&gt;"
                                        )

                                "<code>$safeName</code> " +
                                    "ب $cleanPrice جنيه" +
                                    discountPart
                            }
                    ).take(1020)

                val bitmap = takeScreenshotSync()
                var imageBytes: ByteArray? = null

                if (bitmap != null) {
                    try {
                        addLog(
                            "📐 Breadfast Screenshot: " +
                                "${bitmap.width}x${bitmap.height}"
                        )

                        val topCrop =
                            (bitmap.height * 0.18f).toInt()

                        val bottomCrop =
                            (bitmap.height * 0.22f).toInt()

                        val cropHeight =
                            bitmap.height -
                                topCrop -
                                bottomCrop

                        if (
                            bitmap.width > 100 &&
                            cropHeight > 100
                        ) {
                            val croppedBitmap =
                                Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    topCrop,
                                    bitmap.width,
                                    cropHeight
                                )

                            val stream =
                                ByteArrayOutputStream()

                            croppedBitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                85,
                                stream
                            )

                            imageBytes =
                                stream.toByteArray()

                            croppedBitmap.recycle()

                            addLog(
                                "🖼️ Breadfast: تم تجهيز الصورة: " +
                                    "${imageBytes.size} bytes"
                            )
                        } else {
                            addLog(
                                "⚠️ Breadfast: أبعاد القص غير صالحة."
                            )
                        }
                    } catch (e: Exception) {
                        addLog(
                            "❌ Breadfast image error: " +
                                "${e.javaClass.simpleName}: " +
                                e.message
                        )
                    } finally {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                } else {
                    addLog(
                        "⚠️ Breadfast: Screenshot رجع null."
                    )
                }

                val sent = try {
                    if (
                        imageBytes != null &&
                        imageBytes.isNotEmpty()
                    ) {
                        addLog(
                            "📤 Breadfast: إرسال صورة Telegram..."
                        )

                        sendTelegramPhotoMultipart(
                            token,
                            chatId,
                            imageBytes,
                            caption
                        )
                    } else {
                        addLog(
                            "📤 Breadfast: إرسال النص كبديل..."
                        )

                        sendTelegramMessage(
                            token,
                            chatId,
                            caption
                        )
                    }
                } catch (e: Exception) {
                    addLog(
                        "❌ Breadfast Telegram error: " +
                            "${e.javaClass.simpleName}: " +
                            e.message
                    )
                    false
                }

                if (!sent) {
                    addLog(
                        "❌ Breadfast: فشل إرسال الدفعة."
                    )
                    break
                }

                batch.forEach { product ->
                    val cleanName =
                        normalizeBreadfastText(product.name)

                    val cleanPrice =
                        normalizeBreadfastPrice(product.price)
                            ?: product.price

                    val key =
                        "${normalizeProductKey(cleanName)}|$cleanPrice"

                    sentProducts.add(key)
                }

                addLog(
                    "✅ Breadfast: تم إرسال الدفعة: " +
                        "${batch.size} منتجات."
                )
            }
        }

        lastSignature = signature

        val moved = try {
            moveCartAndWait(signature)
        } catch (e: Exception) {
            addLog(
                "❌ Breadfast: خطأ أثناء التمرير: " +
                    "${e.javaClass.simpleName}: " +
                    e.message
            )
            false
        }

        if (!moved) {
            addLog(
                "🏁 Breadfast: تعذر تحريك السلة."
            )
            break
        }

        Thread.sleep(1200)
    }

    addLog(
        "📊 نتيجة تقرير Breadfast: " +
            "${sentProducts.size} منتجات."
    )
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
        return text
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("\u202A", "")
            .replace("\u202B", "")
            .replace("\u202C", "")
            .replace("\u2060", "")
            .replace("\u00A0", " ")
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractRabbitSalePrice(rawText: String): String? {
        val normalized = normalizeRabbitText(rawText)
        
        // قصر البحث على الأرقام التي تأتي مباشرة بعد العملة، لتجنب التقاط رقم الخصم المئوي
        val priceSectionMatch = Regex("""(?i)(?:EGP|جنيه)[^\d]*([\d\s.,]+)""").find(normalized)
        if (priceSectionMatch == null) return null
        
        val priceText = priceSectionMatch.groupValues[1]

        // استخراج جميع الأرقام التي تبدو كأسعار من قسم الأسعار فقط
        val priceTokens = Regex("""\d+(?:[.,]\d{1,2})?""").findAll(priceText)
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
        
        // تصفية الأرقام الصغيرة جداً (لتفادي الكميات)، واختيار السعر الأقل
        val validPrices = combinedPrices.filter { it > 2.0 }
        val minPrice = validPrices.minOrNull()
        
        // التعديل: تحويل السعر إلى عدد صحيح (Int) لإلغاء القروش والكسور تماماً
        return minPrice?.toInt()?.toString()
    }

    private fun extractRabbitProductInfo(cardNode: AccessibilityNodeInfo): RabbitProductInfo? {
        val texts = mutableListOf<String>()
        collectTextsFromNode(cardNode, texts)
        val rawText = normalizeRabbitText(texts.distinct().joinToString(" "))
        if (rawText.isBlank()) return null
        
        // دعم شامل للوحدات الإنجليزية والعربية
        val unitRegex = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:kg|gm|g|ml|l|pcs?|pc|جم|مل|لتر|قطعة|علكة)\b""")
        
        // التعديل: استخراج الجزء الخاص بالاسم والوحدة فقط (ما قبل العملة) لعزله تماماً عن الأسعار
        val beforeCurrency = Regex("""(?i)\bEGP\b|جنيه""").find(rawText)
            ?.let { rawText.substring(0, it.range.first) } ?: rawText
            
        // التعديل: البحث عن الوحدة داخل الجزء الذي يسبق العملة فقط
        val unit = unitRegex.find(beforeCurrency)?.value?.replace(",", ".")?.trim()
        
        var name = beforeCurrency
            .replace(Regex("""[-]?\s*\d{1,2}\s*[%٪]-?"""), " ") 
            .replace(Regex("""(?i)\badd\b|أضف|اضف"""), " ")
            .replace(Regex("""(?i)\bfavorite\b|مفضلة"""), " ")
            .replace(Regex("""[-+]"""), " ") // التعديل: إزالة علامات الجمع والطرح الخاصة بعداد الكمية
            .replace(unitRegex, " ")
            .replace(Regex("""\b\d+(?:[.,]\d+)?\b"""), " ") // التعديل: سيمسح الآن أي قروش أو أرقام كميات متبقية بكفاءة
            .replace(Regex("""\s+"""), " ")
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
        
        // التعديل: الزر يقع تقريباً في منتصف الكارت (45% للأسفل) مع تعديل الإزاحة الأفقية
        val expectedX = if (isArabic) cardRect.left + (cardRect.width() * 0.15f) else cardRect.left + (cardRect.width() * 0.85f)
        val expectedY = cardRect.top + (cardRect.height() * 0.45f) 
        
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
                
                // التعديل: تضييق نطاق البحث ليكون حول منتصف الكارت عمودياً (0.30 إلى 0.65) وأدق أفقياً
                val isValidXRatio = if (isArabic) centerXRatio in 0.02f..0.40f else centerXRatio in 0.60f..0.98f
                
                val isGeometryAddButton = node.isClickable && combined.isBlank() && width in 40f..250f && height in 40f..250f &&
                                          aspectRatio in 0.65f..1.45f && isValidXRatio && centerYRatio in 0.30f..0.65f

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



    // ==========================================
    // منطق تطبيق رابيت (Rabbit Automation)
    // ==========================================
    private fun runRabbitAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح رابيت.. ننتظر 20 ثانية لاحتمال ظهور إعلان...")
        Thread.sleep(20000)

        // محاولة البحث عن زر إغلاق الإعلان (X) وإغلاقه
        var adClosed = false
        fun scanForClose(node: AccessibilityNodeInfo?) {
            if (node == null || adClosed) return
            val text = (node.text?.toString() ?: "").trim()
            val desc = (node.contentDescription?.toString() ?: "").trim()
            if (text.equals("x", ignoreCase = true) || text.equals("✕") || desc.contains("close", ignoreCase = true) || desc.contains("إغلاق", ignoreCase = true)) {
                if (clickNodeSafely(node) || (node.parent != null && clickNodeSafely(node.parent!!))) {
                    adClosed = true
                } else {
                    clickNodeCenter(node)
                    adClosed = true
                }
                return
            }
            for (i in 0 until node.childCount) {
                scanForClose(node.getChild(i))
            }
        }
        scanForClose(rootInActiveWindow)

        if (adClosed) {
            addLog("✅ تم إغلاق البانر الإعلاني بنجاح.")
            Thread.sleep(3000)
        }

        addLog("⏳ ننتظر 10 ثانية إضافية لتحميل الصفحة الرئيسية...")
        Thread.sleep(10000)

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
            addLog("🛒 Rabbit: تم العثور على ${foundDeals.size} عروض، جاري فتح السلة.")

            if (!tapRabbitBottomTab(RabbitBottomTab.CART)) {
                addLog("❌ Rabbit: فشل الانتقال إلى السلة النهائية.")
                return
            }

            val finalCartOpened = waitForAnyText(
                "My Cart", "الكيس", "Clear all", "فضي الكيس", "Deserted cart?", "إيه الصحراء دي؟", timeoutMs = 10000L
            )

            if (!finalCartOpened) {
                addLog("❌ Rabbit: السلة لم تفتح خلال 10 ثوانٍ.")
                return
            }

            Thread.sleep(4500)

            if (Build.VERSION.SDK_INT >= 30) {
                openCartAndSendReport(token, chatId, deals = foundDeals, isCartAlreadyOpen = true, appType = "RABBIT")
            } else {
                sendChunksAsText(token, chatId, foundDeals.map { it.dealText }.chunked(5), appType = "RABBIT")
            }
        } else {
            addLog("📉 Rabbit: لم يتم العثور على عروض.")
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
            
            // تحويل الرموز لاسم المنتج الأساسي فقط لتجنب أخطاء تليجرام عند استخدام وضع HTML
            val safeName = productInfo.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

            // بناء النص النهائي بحيث يقتصر الـ Monospace على الاسم فقط
            val dealText = buildString {
                append("<code>").append(safeName).append("</code>") // اسم المنتج كـ Monospace
                
                if (!productInfo.unit.isNullOrBlank()) {
                    append(" (").append(productInfo.unit).append(")") // الوحدة بخط عادي خارج الكود
                }
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
        metrics.heightPixels * 0.10f,
        metrics.heightPixels * 0.94f
    )

    addLog("📱 cartVisibleNodes: تم العثور على ${nodes.size} عنصرًا")

    return nodes
}

    private fun cartScreenSignature(nodes: List<NodeData>): String {
    return nodes
        .map { item ->
            val rect = getRect(item.node)
            val text = normalizeRabbitText(item.text)

            "$text@${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }
        .filter { it.length > 5 }
        .distinct()
        .sorted()
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


    private fun sendChunksAsText(
    token: String,
    chatId: String,
    chunks: List<List<String>>,
    appType: String = "BREADFAST"
) {
    for (i in chunks.indices) {
        val prefix = if (appType == "RABBIT") {
            if (i == 0) {
                "عروض ممتازة علي ابلكيشن رابيت\n\n"
            } else {
                "ودول كمان\n\n"
            }
        } else {
            if (i == 0) {
                "عروض ممتازة\n\n"
            } else {
                "ودول كمان\n\n"
            }
        }

        val sent = sendTelegramMessage(
            token,
            chatId,
            (
                prefix +
                    chunks[i].joinToString("\n\n")
                ).take(1020)
        )

        if (!sent) {
            addLog(
                "❌ توقف إرسال الدفعات عند الدفعة ${i + 1}."
            )
            break
        }

        Thread.sleep(700)
    }
}

    @TargetApi(30)
private fun takeScreenshotSync(): Bitmap? {
    addLog("📷 بدء طلب Screenshot...")

    val latch = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()

    var resultBitmap: Bitmap? = null

    try {
        Thread.sleep(700)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {

                override fun onSuccess(
                    screenshot: AccessibilityService.ScreenshotResult
                ) {
                    try {
                        val hardwareBuffer =
                            screenshot.hardwareBuffer

                        val colorSpace =
                            screenshot.colorSpace

                        resultBitmap =
                            Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                colorSpace
                            )?.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )

                        hardwareBuffer.close()

                        if (resultBitmap == null) {
                            addLog(
                                "❌ Screenshot نجح، " +
                                    "لكن Bitmap = null."
                            )
                        } else {
                            addLog(
                                "✅ Screenshot تم التقاطه: " +
                                    "${resultBitmap!!.width}x" +
                                    "${resultBitmap!!.height}"
                            )
                        }
                    } catch (e: Exception) {
                        addLog(
                            "❌ Screenshot conversion error: " +
                                "${e.message}"
                        )
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    addLog(
                        "❌ Screenshot failed. " +
                            "errorCode=$errorCode"
                    )
                    latch.countDown()
                }
            }
        )

        val completed = latch.await(
            10,
            TimeUnit.SECONDS
        )

        if (!completed) {
            addLog(
                "❌ Screenshot timeout بعد 10 ثوانٍ."
            )
        }
    } catch (e: Exception) {
        addLog(
            "❌ Screenshot exception: ${e.message}"
        )
    } finally {
        executor.shutdown()
    }

    return resultBitmap
}

    private fun sendTelegramPhotoMultipart(
    token: String,
    chatId: String,
    imageBytes: ByteArray,
    caption: String
): Boolean {
    var connection: HttpURLConnection? = null

    return try {
        if (token.isBlank()) {
            addLog("❌ Telegram: BOT_TOKEN فارغ.")
            return false
        }

        if (chatId.isBlank()) {
            addLog("❌ Telegram: CHAT_ID فارغ.")
            return false
        }

        if (imageBytes.isEmpty()) {
            addLog("❌ Telegram: imageBytes فارغة.")
            return false
        }

        addLog(
            "📤 Telegram: رفع صورة، " +
                "size=${imageBytes.size}, " +
                "caption=${caption.length}"
        )

        val boundary =
            "Boundary-${System.currentTimeMillis()}"

        val url = URL(
            "https://api.telegram.org/bot" +
                "${token.trim()}/sendPhoto"
        )

        connection = url.openConnection()
            as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.useCaches = false

        connection.setRequestProperty(
            "Content-Type",
            "multipart/form-data; boundary=$boundary"
        )

        DataOutputStream(
            connection.outputStream
        ).use { output ->

            fun writeField(
                name: String,
                value: String
            ) {
                output.writeBytes(
                    "--$boundary\r\n"
                )
                output.writeBytes(
                    "Content-Disposition: form-data; " +
                        "name=\"$name\"\r\n\r\n"
                )
                output.write(
                    value.toByteArray(Charsets.UTF_8)
                )
                output.writeBytes("\r\n")
            }

            writeField("chat_id", chatId.trim())
            writeField("caption", caption)
            writeField("parse_mode", "HTML") // التعديل: تفعيل وضع HTML لدعم النصوص
            writeField("reply_markup", replyMarkup)

            output.writeBytes(
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; " +
                    "name=\"photo\"; " +
                    "filename=\"cart.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n"
            )

            output.write(imageBytes)
            output.writeBytes("\r\n")
            output.writeBytes("--$boundary--\r\n")
            output.flush()
        }

        val responseCode = connection.responseCode

        val responseText = try {
            val stream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""
        } catch (e: Exception) {
            "تعذر قراءة رد Telegram: ${e.message}"
        }

        if (responseCode in 200..299) {
            addLog(
                "✅ Telegram photo success: " +
                    "HTTP $responseCode"
            )
            true
        } else {
            addLog(
                "❌ Telegram photo error: " +
                    "HTTP $responseCode - " +
                    responseText.take(500)
            )
            false
        }
    } catch (e: Exception) {
        addLog(
            "❌ Telegram photo exception: " +
                "${e.javaClass.simpleName}: ${e.message}"
        )
        false
    } finally {
        connection?.disconnect()
    }
}

    private fun sendTelegramMessage(
    token: String,
    chatId: String,
    text: String
): Boolean {
    var connection: HttpURLConnection? = null

    return try {
        if (token.isBlank()) {
            addLog("❌ Telegram text: BOT_TOKEN فارغ.")
            return false
        }

        if (chatId.isBlank()) {
            addLog("❌ Telegram text: CHAT_ID فارغ.")
            return false
        }

        if (text.isBlank()) {
            addLog("❌ Telegram text: الرسالة فارغة.")
            return false
        }

        addLog(
            "📤 Telegram: إرسال نص " +
                "(${text.length} حرف)..."
        )

        val url = URL(
            "https://api.telegram.org/bot" +
                "${token.trim()}/sendMessage"
        )

        connection = url.openConnection()
            as HttpURLConnection

        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.useCaches = false

        connection.setRequestProperty(
            "Content-Type",
            "application/x-www-form-urlencoded; " +
                "charset=UTF-8"
        )

        val body = buildString {
            append("chat_id=")
            append(
                URLEncoder.encode(
                    chatId.trim(),
                    "UTF-8"
                )
            )

            append("&text=")
            append(
                URLEncoder.encode(
                    text,
                    "UTF-8"
                )
            )

            append("&reply_markup=")
            append(
                URLEncoder.encode(
                    replyMarkup,
                    "UTF-8"
                )
            )
            append("&parse_mode=")
append(
    URLEncoder.encode(
        "HTML",
        "UTF-8"
    )
)
        }

        connection.outputStream.use { output ->
            output.write(
                body.toByteArray(Charsets.UTF_8)
            )
            output.flush()
        }

        val responseCode = connection.responseCode

        val responseText = try {
            val stream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""
        } catch (e: Exception) {
            "تعذر قراءة رد Telegram: ${e.message}"
        }

        if (responseCode in 200..299) {
            addLog(
                "✅ Telegram text success: " +
                    "HTTP $responseCode"
            )
            true
        } else {
            addLog(
                "❌ Telegram text error: " +
                    "HTTP $responseCode - " +
                    responseText.take(500)
            )
            false
        }
    } catch (e: Exception) {
        addLog(
            "❌ Telegram text exception: " +
                "${e.javaClass.simpleName}: ${e.message}"
        )
        false
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

    
}
