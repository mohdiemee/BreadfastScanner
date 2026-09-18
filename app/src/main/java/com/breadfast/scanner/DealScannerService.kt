package com.breadfast.scanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.Context
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

        val targetApp = prefs.getString("TARGET_PACKAGE", "com.breadfast.application") ?: ""
        if (event.packageName?.toString() == targetApp) {
            val currentTime = System.currentTimeMillis()
            if (isScanning || currentTime - lastScanTime < 30000) return 
            
            isScanning = true
            lastScanTime = currentTime
            addedItemsCount = 0
            
            thread {
                try {
                    runAutomation(prefs)
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
                        addLog("🏠 تم الإغلاق ومسح التطبيق من الذاكرة بنجاح.")
                    } catch (e: Exception) {
                        addLog("⚠️ تم الرجوع للرئيسية (تعذر مسح الذاكرة).")
                    }
                }
            }
        }
    }

    private fun runAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح التطبيق.. ننتظر 15 ثانية للتحميل...")
        Thread.sleep(15000) 

        val initialCartNode = findNodeByText(rootInActiveWindow, "السلة") ?: findNodeByText(rootInActiveWindow, "Cart")
        if (initialCartNode != null) {
            addLog("🗑️ جاري فتح السلة لمسح المنتجات القديمة...")
            clickNodeSafely(initialCartNode)
            Thread.sleep(5000)
            
            val clearAllNode = findNodeByText(rootInActiveWindow, "مسح الكل") ?: findNodeByText(rootInActiveWindow, "Clear All")
            if (clearAllNode != null) {
                addLog("🗑️ تم العثور على زر مسح الكل الرئيسي.")
                val clickedMainClear = clickNodeSafely(clearAllNode)
                
                if (!clickedMainClear) {
                    addLog("❌ تعذر النقر على زر مسح الكل الرئيسي.")
                } else {
                    addLog("⏳ ننتظر زر التأكيد داخل Bottom Sheet...")
                    val confirmButton = waitForBottomSheetClearButton(6000)
                    
                    if (confirmButton == null) {
                        addLog("❌ لم يتم العثور على زر تأكيد مسح السلة السفلي.")
                    } else {
                        val confirmClicked = clickConfirmClearButton(confirmButton)
                        if (!confirmClicked) {
                            addLog("❌ لم تنجح محاولة النقر على تأكيد مسح السلة.")
                        } else {
                            addLog("⏳ تم إرسال أمر التأكيد، نتحقق من تغيّر السلة...")
                            var cartCleared = false
                            repeat(12) {
                                Thread.sleep(400)
                                if (isCartEmpty()) {
                                    cartCleared = true
                                    return@repeat
                                }
                            }
                            if (cartCleared) {
                                addLog("✅ تم التحقق من تفريغ السلة بنجاح.")
                            } else {
                                addLog("⚠️ لم يتم التحقق من تفريغ السلة؛ الزر الرئيسي لا يزال موجوداً.")
                            }
                        }
                    }
                }
            } else {
                addLog("ℹ️ لم يظهر زر مسح الكل الرئيسي؛ السلة قد تكون فارغة بالفعل.")
            }
            
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(3000)
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            addLog("❌ الشاشة غير مقروءة.")
            return
        }

        var dealsNode = findNodeByText(rootInActiveWindow, "Deals") ?: findNodeByText(rootInActiveWindow, "عروض")
        var scrollAttempts = 0
        
        while (dealsNode == null && scrollAttempts < 4) {
            swipeUp(0.8f, 0.5f, 400L)
            Thread.sleep(1500)
            dealsNode = findNodeByText(rootInActiveWindow, "Deals") ?: findNodeByText(rootInActiveWindow, "عروض")
            scrollAttempts++
        }

        if (dealsNode == null) {
            addLog("⚠️ أيقونة العروض غير موجودة.")
            return
        }

        addLog("🎯 تم الدخول لصفحة العروض، جاري المسح والإضافة...")
        clickNodeSafely(dealsNode) 
        Thread.sleep(6000)

        val cooldownHours = prefs.getInt("COOLDOWN_HOURS", 24)
        val cooldownMillis = cooldownHours * 60 * 60 * 1000L
        val historyStr = prefs.getString("PRODUCTS_HISTORY", "") ?: ""
        val historyMap = mutableMapOf<String, Long>()
        val currentTime = System.currentTimeMillis()

        if (historyStr.isNotEmpty()) {
            historyStr.split("||").forEach { entry ->
                val parts = entry.split("::")
                if (parts.size == 2) {
                    val name = parts[0]
                    val time = parts[1].toLongOrNull() ?: 0L
                    if (currentTime - time < cooldownMillis) {
                        historyMap[name] = time
                    }
                }
            }
        }

        val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
        val foundDeals = mutableListOf<String>()
        val processedProducts = mutableSetOf<String>()
        
        var previousScreenContent = ""
        var emptyScrolls = 0
        var totalScrolls = 0
        
        // حساب أبعاد المنطقة الآمنة للشاشة
        val displayMetrics = resources.displayMetrics
        val safeTop = displayMetrics.heightPixels * 0.15f
        val safeBottom = displayMetrics.heightPixels * 0.85f
        
        while (totalScrolls < 1000) {
            val visibleNodes = mutableListOf<NodeData>()
            // تمرير إحداثيات المنطقة الآمنة للدالة
            extractNodes(rootInActiveWindow, visibleNodes, safeTop, safeBottom)
            
            val currentScreenContent = visibleNodes.map { it.text }.distinct().sorted().joinToString("|")
            
            analyzeAndAddToCart(visibleNodes, minDiscount, foundDeals, processedProducts, historyMap, cooldownMillis, prefs)
            
            // التأخير الأول (حسب التعديل الجديد)
            Thread.sleep(900)

            if (currentScreenContent == previousScreenContent) {
                emptyScrolls++
                if (emptyScrolls >= 3) {
                    addLog("🏁 نهاية قائمة العروض الفعلية.")
                    break 
                }
            } else {
                emptyScrolls = 0
            }
            
            previousScreenContent = currentScreenContent
            totalScrolls++
            
            // السحب بالتوقيتات الجديدة
            swipeUp(0.8f, 0.5f, 400L)
            
            // التأخير الثاني (حسب التعديل الجديد)
            Thread.sleep(1100) 
        }

        val token = prefs.getString("BOT_TOKEN", "") ?: ""
        val chatId = prefs.getString("CHAT_ID", "") ?: ""

        if (foundDeals.isNotEmpty()) {
            addLog("🔥 تم العثور على ${foundDeals.size} منتجات جديدة وتم تنفيذ محاولة نقر عليها.")
            
            if (Build.VERSION.SDK_INT >= 30) {
                openCartAndSendReport(token, chatId, foundDeals)
            } else {
                sendChunksAsText(token, chatId, foundDeals.chunked(5))
            }
        } else {
            addLog("📉 لم يتم العثور على عروض مناسبة أو جميع العروض تم إرسالها خلال فترة $cooldownHours ساعات الماضية.")
        }
    }

    // === الدالة المحدثة مع فلتر "المنطقة الآمنة" ===
    private fun extractNodes(node: AccessibilityNodeInfo?, nodesList: MutableList<NodeData>, safeTop: Float, safeBottom: Float) {
        if (node == null) return
        
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        // التأكد من أن العنصر بأكمله يقع داخل المنطقة الآمنة (يستبعد العناصر المقطوعة)
        val isSafe = rect.top >= safeTop && rect.bottom <= safeBottom
        
        if (isSafe) {
            val text = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                if (nodesList.none { it.text == text && it.node == node }) {
                    nodesList.add(NodeData(text, node))
                }
            }
        }
        
        for (i in 0 until node.childCount) {
            extractNodes(node.getChild(i), nodesList, safeTop, safeBottom)
        }
    }

    private fun analyzeAndAddToCart(
        nodesList: List<NodeData>, 
        minDiscount: Int, 
        deals: MutableList<String>, 
        processedProducts: MutableSet<String>,
        historyMap: MutableMap<String, Long>,
        cooldownMillis: Long,
        prefs: SharedPreferences
    ) {
        val numRegex = Regex("^[0-9]{1,6}(?:\\.[0-9]{1,2})?$")
        val uniqueNodes = nodesList.distinctBy { it.text }

        for (i in uniqueNodes.indices) {
            val current = uniqueNodes[i]
            
            val dualPriceMatch = Regex("^([0-9]{1,6}(?:\\.[0-9]{1,2})?)\\s+([0-9]{1,6}(?:\\.[0-9]{1,2})?)$").find(current.text)
            if (dualPriceMatch != null) {
                val p1 = dualPriceMatch.groupValues[1].toDoubleOrNull() ?: continue
                val p2 = dualPriceMatch.groupValues[2].toDoubleOrNull() ?: continue
                processDealNode(p1, p2, i + 1, uniqueNodes, minDiscount, deals, processedProducts, current.node, historyMap, cooldownMillis, prefs)
                continue
            }

            if (numRegex.matches(current.text) && i + 1 < uniqueNodes.size && numRegex.matches(uniqueNodes[i+1].text)) {
                val p1 = current.text.toDoubleOrNull() ?: continue
                val p2 = uniqueNodes[i+1].text.toDoubleOrNull() ?: continue
                processDealNode(p1, p2, i + 2, uniqueNodes, minDiscount, deals, processedProducts, current.node, historyMap, cooldownMillis, prefs)
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
            
            val looksLikeProductCard = rect.width() > screenWidth * 0.18 && rect.width() < screenWidth * 0.48 && rect.height() > 150
            if (looksLikeProductCard) {
                return node
            }
            current = node.parent
        }
        return priceNode.parent
    }

    private fun processDealNode(
        p1: Double, 
        p2: Double, 
        nameIdx: Int, 
        uniqueNodes: List<NodeData>, 
        minDiscount: Int, 
        deals: MutableList<String>, 
        processed: MutableSet<String>, 
        priceNode: AccessibilityNodeInfo,
        historyMap: MutableMap<String, Long>,
        cooldownMillis: Long,
        prefs: SharedPreferences
    ) {
        val oldPrice = maxOf(p1, p2)
        val newPrice = minOf(p1, p2)

        if (oldPrice > 0 && oldPrice != newPrice) {
            val discountPercent = (((oldPrice - newPrice) / oldPrice) * 100).toInt()
            
            if (discountPercent >= minDiscount) {
                val productName = if (nameIdx < uniqueNodes.size && !uniqueNodes[nameIdx].text.matches(Regex("^[0-9\\s.]+$"))) {
                    uniqueNodes[nameIdx].text
                } else "منتج مميز"

                if (processed.contains(productName)) return
                
                val lastSentTime = historyMap[productName]
                if (lastSentTime != null && (System.currentTimeMillis() - lastSentTime) < cooldownMillis) {
                    return 
                }
                
                try {
                    val productCard = findProductCard(priceNode)
                    val clickSuccess = if (productCard != null) {
                        forceClickAddButton(productCard)
                    } else {
                        false
                    }

                    if (clickSuccess) {
                        addedItemsCount++
                    }
                    
                    var cleanName = productName.replace("\n", " ").trim()
                    cleanName = cleanName.replace(Regex("(?i)(\\d+)\\s*x\\s*"), "$1 قطع ")
                    cleanName = cleanName.replace(Regex("\\s+"), " ").trim()
                    
                    val dealText = "$cleanName ب $newPrice جنيه بخصم $discountPercent%"
                    deals.add(dealText)
                    
                    processed.add(productName)
                    
                    historyMap[productName] = System.currentTimeMillis()
                    val newHistoryStr = historyMap.map { "${it.key}::${it.value}" }.joinToString("||")
                    prefs.edit().putString("PRODUCTS_HISTORY", newHistoryStr).apply()
                    
                } catch (e: Exception) {
                    addLog("❌ خطأ إضافة $productName: ${e.message}")
                }
            }
        }
    }

    private fun forceClickAddButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val id = node.viewIdResourceName ?: ""
        val combined = "$text $desc $id".lowercase(java.util.Locale.ROOT)

        val isFavoriteButton = combined.contains("favorite") || combined.contains("favourite") ||
                combined.contains("wishlist") || combined.contains("wish_list") ||
                combined.contains("saved") || combined.contains("مفضلة") ||
                combined.contains("المفضلة") || combined.contains("رغبات") || combined.contains("حفظ")

        if (isFavoriteButton) {
            return false
        }

        val isCartAddButton = text == "+" || desc == "+" ||
                id.contains("add_to_cart", ignoreCase = true) || id.contains("cart_add", ignoreCase = true) ||
                id.contains("add_cart", ignoreCase = true) || id.contains("increase_quantity", ignoreCase = true) ||
                id.contains("quantity_increase", ignoreCase = true) || id.contains("increment", ignoreCase = true) ||
                combined.contains("add to cart") || combined.contains("add_to_cart") ||
                combined.contains("increase quantity") || combined.contains("أضف إلى السلة") ||
                combined.contains("اضف الى السلة") || combined.contains("زيادة الكمية")

        if (isCartAddButton) {
            return clickNodeSafely(node)
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val isSmallClickable = node.isClickable && combined.isBlank() && rect.width() in 40..250 && rect.height() in 40..250
        
        if (isSmallClickable) {
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
            if (!node.refresh()) {
                return false
            }
            if (node.isClickable) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    Thread.sleep(500)
                    return true
                }
            }
            val rect = getRect(node)
            if (rect.isEmpty || rect.width() < 10 || rect.height() < 10) {
                return false
            }
            
            return tapScreenPoint(rect.centerX().toFloat(), rect.centerY().toFloat())
        } catch (e: Exception) {
            return false
        }
    }

    @TargetApi(30)
    private fun openCartAndSendReport(token: String, chatId: String, deals: List<String>) {
        addLog("🛒 جاري فتح السلة لتصوير التقرير...")
        val cartNode = findNodeByText(rootInActiveWindow, "Cart") ?: findNodeByText(rootInActiveWindow, "السلة")
        
        val chunks = deals.chunked(5)
        val shotsCount = chunks.size
        
        if (cartNode != null) {
            clickNodeSafely(cartNode)
            Thread.sleep(5000) 
            
            val screenshots = mutableListOf<ByteArray>()
            
            for (i in 0 until shotsCount) {
                val bitmap = takeScreenshotSync()
                if (bitmap != null) {
                    val topCrop = (bitmap.height * 0.21).toInt()
                    val bottomCrop = (bitmap.height * 0.19).toInt()
                    val croppedHeight = bitmap.height - topCrop - bottomCrop
                    
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, topCrop, bitmap.width, croppedHeight)
                    val stream = ByteArrayOutputStream()
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    screenshots.add(stream.toByteArray())
                } else {
                    addLog("⚠️ فشل التقاط الصورة رقم ${i+1}")
                }
                
                if (i < shotsCount - 1) {
                    swipeUp(0.78f, 0.22f, 1200L)
                    Thread.sleep(2000)
                }
            }
            
            if (screenshots.isNotEmpty()) {
                addLog("📸 تم التصوير. جاري الرفع...")
                for (i in chunks.indices) {
                    val chunk = chunks[i]
                    val prefix = if (i == 0) "عروض ممتازة علي بريدفاست\n" else "ودول كمان\n"
                    val caption = prefix + chunk.joinToString("\n\n")
                    
                    val finalCaption = if (caption.length > 1024) caption.substring(0, 1020) + "..." else caption
                    
                    val imageBytes = screenshots.getOrNull(i)
                    if (imageBytes != null) {
                        sendTelegramPhotoMultipart(token, chatId, imageBytes, finalCaption)
                    } else {
                        sendTelegramMessage(token, chatId, finalCaption)
                    }
                }
            } else {
                sendChunksAsText(token, chatId, chunks)
            }
            
        } else {
            addLog("❌ زر السلة غير موجود.")
            sendChunksAsText(token, chatId, chunks)
        }
    }

    private fun sendChunksAsText(token: String, chatId: String, chunks: List<List<String>>) {
        for (i in chunks.indices) {
            val chunk = chunks[i]
            val prefix = if (i == 0) "عروض ممتازة علي بريدفاست\n" else "ودول كمان\n"
            val text = prefix + chunk.joinToString("\n\n")
            val finalText = if (text.length > 1024) text.substring(0, 1020) + "..." else text
            sendTelegramMessage(token, chatId, finalText)
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
                    val colorSpace = screenshot.colorSpace
                    bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    hwBuffer.close()
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) {
                    addLog("❌ خطأ التقاط الشاشة: $errorCode")
                    latch.countDown()
                }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            addLog("❌ فشل عملية التصوير: ${e.message}")
        } finally {
            executor.shutdown()
        }
        return bitmap
    }

    private fun sendTelegramPhotoMultipart(token: String, chatId: String, imageBytes: ByteArray, caption: String) {
        try {
            val url = URL("https://api.telegram.org/bot$token/sendPhoto")
            val connection = url.openConnection() as HttpURLConnection
            val boundary = "Boundary-${System.currentTimeMillis()}"
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true

            val outputStream = DataOutputStream(connection.outputStream)
            
            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
            outputStream.write((chatId + "\r\n").toByteArray(Charsets.UTF_8))

            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
            outputStream.write((caption + "\r\n").toByteArray(Charsets.UTF_8))

            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"reply_markup\"\r\n\r\n")
            outputStream.write((replyMarkup + "\r\n").toByteArray(Charsets.UTF_8))

            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"cart.jpg\"\r\n")
            outputStream.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            outputStream.write(imageBytes)
            outputStream.writeBytes("\r\n")

            outputStream.writeBytes("--$boundary--\r\n")
            outputStream.flush()
            outputStream.close()
            
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                addLog("❌ فشل الرفع: $responseCode")
            } else {
                addLog("✅ تم رفع الصورة والرسالة بنجاح.")
            }
            connection.disconnect()
        } catch (e: Exception) {
            addLog("❌ خطأ رفع الصورة: ${e.message}")
        }
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val encodedMarkup = URLEncoder.encode(replyMarkup, "UTF-8")
            val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encodedText&reply_markup=$encodedMarkup")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.inputStream.reader().readText()
            connection.disconnect()
        } catch (e: Exception) {}
    }

    private fun swipeUp(startFactor: Float, endFactor: Float, durationMs: Long) {
        val displayMetrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * startFactor)
            lineTo(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * endFactor)
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

    private fun getRect(node: AccessibilityNodeInfo): Rect {
        return Rect().also { node.getBoundsInScreen(it) }
    }

    private fun findClickableParent(startNode: AccessibilityNodeInfo, maxLevels: Int = 5): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = startNode
        repeat(maxLevels) {
            val current = node ?: return null
            if (current.isVisibleToUser && current.isEnabled && current.isClickable) {
                return current
            }
            node = current.parent
        }
        return null
    }

    private fun collectNodesByExactText(node: AccessibilityNodeInfo?, target: String, result: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val value = normalizedText(node)
        if (value.equals(target, ignoreCase = true)) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            collectNodesByExactText(node.getChild(i), target, result)
        }
    }

    private fun findBottomSheetClearConfirmButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val screenHeight = resources.displayMetrics.heightPixels
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        
        collectNodesByExactText(root, "مسح الكل", candidates)
        collectNodesByExactText(root, "Clear All", candidates)
        
        val buttonCandidates = candidates.mapNotNull { textNode ->
            findClickableParent(textNode) ?: textNode.takeIf { it.isClickable && it.isEnabled && it.isVisibleToUser }
        }.filter { buttonNode ->
            val rect = getRect(buttonNode)
            rect.centerY() > screenHeight * 0.58 &&
            rect.width() > resources.displayMetrics.widthPixels * 0.45 &&
            buttonNode.isVisibleToUser &&
            buttonNode.isEnabled
        }
        
        return buttonCandidates.maxByOrNull { getRect(it).centerY() }
    }

    private fun waitForBottomSheetClearButton(timeoutMs: Long = 6000L): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val button = findBottomSheetClearConfirmButton()
            if (button != null) {
                val rect = getRect(button)
                addLog("🔎 تم إيجاد زر تأكيد: bounds=$rect, clickable=${button.isClickable}")
                return button
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun clickConfirmClearButton(button: AccessibilityNodeInfo): Boolean {
        try {
            if (!button.refresh()) {
                addLog("⚠️ عقدة زر التأكيد أصبحت قديمة.")
                return false
            }
            if (button.isClickable) {
                val actionAccepted = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (actionAccepted) {
                    return true
                }
            }
            val rect = getRect(button)
            if (rect.isEmpty || rect.width() < 50 || rect.height() < 40) {
                return false
            }
            return tapScreenPoint(rect.centerX().toFloat(), rect.centerY().toFloat())
        } catch (e: Exception) {
            addLog("❌ فشل النقر على زر تأكيد المسح: ${e.message}")
            return false
        }
    }

    private fun tapScreenPoint(x: Float, y: Float): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
            
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed = false
                latch.countDown()
            }
        }, null)
        
        if (!dispatched) return false
        
        latch.await(2, TimeUnit.SECONDS)
        Thread.sleep(500)
        return completed
    }

    private fun isCartEmpty(): Boolean {
        val root = rootInActiveWindow ?: return false
        val emptyText = findNodeByText(root, "السلة فارغة")
                ?: findNodeByText(root, "سلتك فارغة")
                ?: findNodeByText(root, "Your cart is empty")
                ?: findNodeByText(root, "فارغة")
        
        if (emptyText != null) return true
        
        val clearBtn = findNodeByText(root, "مسح الكل") ?: findNodeByText(root, "Clear All")
        return clearBtn == null
    }
}
