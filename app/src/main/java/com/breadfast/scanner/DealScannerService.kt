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
                    addLog("🏠 تم الإغلاق والعودة للشاشة الرئيسية بأمان.")
                }
            }
        }
    }

    private fun runAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح التطبيق.. ننتظر 15 ثانية للتحميل...")
        Thread.sleep(15000) 

        // === 1. مسح السلة أولاً قبل البدء ===
        val initialCartNode = findNodeByText(rootInActiveWindow, "السلة") ?: findNodeByText(rootInActiveWindow, "Cart")
        if (initialCartNode != null) {
            addLog("🗑️ جاري فتح السلة لمسح المنتجات القديمة...")
            clickNodeSafely(initialCartNode)
            Thread.sleep(5000)
            
            val clearAllNode = findNodeByText(rootInActiveWindow, "مسح الكل") ?: findNodeByText(rootInActiveWindow, "Clear All")
            if (clearAllNode != null) {
                clickNodeSafely(clearAllNode)
                Thread.sleep(2000) // انتظار ظهور النافذة المنبثقة للتأكيد
                
                // === التعديل لحل مشكلة الزر السفلي ===
                // تجميع كل الأزرار التي تحمل نفس النص لاختيار الزر الموجود بأسفل الشاشة
                val allClearNodes = mutableListOf<AccessibilityNodeInfo>()
                rootInActiveWindow?.findAccessibilityNodeInfosByText("مسح الكل")?.let { allClearNodes.addAll(it) }
                rootInActiveWindow?.findAccessibilityNodeInfosByText("Clear All")?.let { allClearNodes.addAll(it) }
                
                // ترتيب الأزرار وتحديد الزر صاحب أكبر إحداثي رأسي (الموجود في القاع)
                val bottomConfirmNode = allClearNodes.maxByOrNull { node ->
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    rect.bottom
                }
                
                if (bottomConfirmNode != null) {
                    clickNodeSafely(bottomConfirmNode)
                    Thread.sleep(2000)
                    addLog("✅ تم تأكيد مسح السلة بنجاح (النقر على الزر السفلي).")
                } else {
                    addLog("⚠️ لم يظهر زر تأكيد المسح السفلي.")
                }
            }
            
            // محاكاة زر الرجوع للخلف للعودة للصفحة الرئيسية
            performGlobalAction(GLOBAL_ACTION_BACK)
            Thread.sleep(3000)
        }
        // ===================================

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            addLog("❌ الشاشة غير مقروءة.")
            return
        }

        var dealsNode = findNodeByText(rootInActiveWindow, "Deals") ?: findNodeByText(rootInActiveWindow, "عروض")
        var scrollAttempts = 0
        
        while (dealsNode == null && scrollAttempts < 4) {
            swipeUp()
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

        val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
        val foundDeals = mutableListOf<String>()
        val processedProducts = mutableSetOf<String>()
        var previousTextCount = 0
        var emptyScrolls = 0
        var totalScrolls = 0
        
        while (totalScrolls < 100) {
            val visibleNodes = mutableListOf<NodeData>()
            extractNodes(rootInActiveWindow, visibleNodes)
            
            val currentTextCount = visibleNodes.map { it.text }.distinct().size
            
            analyzeAndAddToCart(visibleNodes, minDiscount, foundDeals, processedProducts)
            Thread.sleep(1200)

            if (currentTextCount == previousTextCount) {
                emptyScrolls++
                if (emptyScrolls >= 3) {
                    addLog("🏁 نهاية قائمة العروض.")
                    break 
                }
            } else {
                emptyScrolls = 0
            }
            previousTextCount = currentTextCount
            totalScrolls++
            
            swipeUp()
            Thread.sleep(1500) 
        }

        val token = prefs.getString("BOT_TOKEN", "") ?: ""
        val chatId = prefs.getString("CHAT_ID", "") ?: ""

        if (foundDeals.isNotEmpty()) {
            addLog("🔥 تم العثور على ${foundDeals.size} منتجات وتم تنفيذ محاولة نقر عليها.")
            
            if (Build.VERSION.SDK_INT >= 30) {
                openCartAndSendReport(token, chatId, foundDeals)
            } else {
                sendChunksAsText(token, chatId, foundDeals.chunked(6))
            }
        } else {
            addLog("📉 لم يتم العثور على عروض مناسبة.")
        }
    }

    private fun extractNodes(node: AccessibilityNodeInfo?, nodesList: MutableList<NodeData>) {
        if (node == null) return
        val text = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: ""
        
        if (text.isNotEmpty()) {
            if (nodesList.none { it.text == text && it.node == node }) {
                nodesList.add(NodeData(text, node))
            }
        }
        for (i in 0 until node.childCount) {
            extractNodes(node.getChild(i), nodesList)
        }
    }

    private fun analyzeAndAddToCart(
        nodesList: List<NodeData>, 
        minDiscount: Int, 
        deals: MutableList<String>, 
        processedProducts: MutableSet<String>
    ) {
        val numRegex = Regex("^[0-9]{1,6}(?:\\.[0-9]{1,2})?$")
        val uniqueNodes = nodesList.distinctBy { it.text }

        for (i in uniqueNodes.indices) {
            val current = uniqueNodes[i]
            
            val dualPriceMatch = Regex("^([0-9]{1,6}(?:\\.[0-9]{1,2})?)\\s+([0-9]{1,6}(?:\\.[0-9]{1,2})?)$").find(current.text)
            if (dualPriceMatch != null) {
                val p1 = dualPriceMatch.groupValues[1].toDoubleOrNull() ?: continue
                val p2 = dualPriceMatch.groupValues[2].toDoubleOrNull() ?: continue
                processDealNode(p1, p2, i + 1, uniqueNodes, minDiscount, deals, processedProducts, current.node)
                continue
            }

            if (numRegex.matches(current.text) && i + 1 < uniqueNodes.size && numRegex.matches(uniqueNodes[i+1].text)) {
                val p1 = current.text.toDoubleOrNull() ?: continue
                val p2 = uniqueNodes[i+1].text.toDoubleOrNull() ?: continue
                processDealNode(p1, p2, i + 2, uniqueNodes, minDiscount, deals, processedProducts, current.node)
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
        priceNode: AccessibilityNodeInfo
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
                    
                    val cleanName = productName.replace("\n", " ").trim()
                    val dealText = "$cleanName ب $newPrice جنيه بخصم $discountPercent%"
                    deals.add(dealText)
                    processed.add(productName)
                    
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
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty || rect.width() < 10 || rect.height() < 10) {
                return false
            }
            
            val latch = CountDownLatch(1)
            var completed = false
            val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
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
            
            latch.await(1500, TimeUnit.MILLISECONDS)
            Thread.sleep(500)
            return completed
        } catch (e: Exception) {
            return false
        }
    }

    @TargetApi(30)
    private fun openCartAndSendReport(token: String, chatId: String, deals: List<String>) {
        addLog("🛒 جاري فتح السلة لتصوير التقرير...")
        val cartNode = findNodeByText(rootInActiveWindow, "Cart") ?: findNodeByText(rootInActiveWindow, "السلة")
        
        val chunks = deals.chunked(6)
        val shotsCount = chunks.size
        
        if (cartNode != null) {
            clickNodeSafely(cartNode)
            Thread.sleep(5000) 
            
            val screenshots = mutableListOf<ByteArray>()
            
            for (i in 0 until shotsCount) {
                val bitmap = takeScreenshotSync()
                if (bitmap != null) {
                    val topCrop = (bitmap.height * 0.15).toInt()
                    val bottomCrop = (bitmap.height * 0.15).toInt()
                    val croppedHeight = bitmap.height - topCrop - bottomCrop
                    
                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, topCrop, bitmap.width, croppedHeight)
                    val stream = ByteArrayOutputStream()
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    screenshots.add(stream.toByteArray())
                } else {
                    addLog("⚠️ فشل التقاط الصورة رقم ${i+1}")
                }
                
                if (i < shotsCount - 1) {
                    swipeUp()
                    Thread.sleep(1500)
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

    private fun swipeUp() {
        val displayMetrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * 0.8f)
            lineTo(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels * 0.2f)
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build(), null, null)
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
}
