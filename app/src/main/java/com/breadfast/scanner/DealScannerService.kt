package com.breadfast.scanner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class NodeData(val text: String, val node: AccessibilityNodeInfo)

class DealScannerService : AccessibilityService() {

    private var isScanning = false
    private var lastScanTime = 0L
    private var addedItemsCount = 0

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
                    addLog("❌ خطأ غير متوقع: ${e.message}")
                    e.printStackTrace()
                } finally {
                    isScanning = false
                    prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
                }
            }
        }
    }

    private fun runAutomation(prefs: SharedPreferences) {
        addLog("⏳ تم فتح التطبيق.. ننتظر 15 ثانية للتحميل...")
        Thread.sleep(15000) 

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            addLog("❌ لم نتمكن من قراءة الشاشة، قد يكون التطبيق معلقاً.")
            performGlobalAction(GLOBAL_ACTION_HOME)
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
            addLog("⚠️ لم يتم العثور على أيقونة العروض.")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        addLog("🎯 تم الدخول لصفحة العروض، جاري المسح...")
        clickNode(dealsNode)
        Thread.sleep(6000)

        val allNodes = mutableListOf<NodeData>()
        var previousTextCount = 0
        var emptyScrolls = 0
        var totalScrolls = 0
        
        while (totalScrolls < 100) {
            extractNodes(rootInActiveWindow, allNodes)
            val currentTextCount = allNodes.map { it.text }.distinct().size
            
            if (currentTextCount == previousTextCount) {
                emptyScrolls++
                if (emptyScrolls >= 3) {
                    addLog("🏁 تم الوصول لنهاية الصفحة.")
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

        val minDiscount = prefs.getInt("MIN_DISCOUNT", 40)
        val foundDeals = analyzeAndAddToCart(allNodes, minDiscount)

        val token = prefs.getString("BOT_TOKEN", "") ?: ""
        val chatId = prefs.getString("CHAT_ID", "") ?: ""

        if (foundDeals.isNotEmpty()) {
            addLog("🔥 تم العثور على ${foundDeals.size} عروض وإضافتها للسلة.")
            val message = "🛒 **تمت الإضافة للسلة بنجاح:**\n\n" + foundDeals.joinToString("\n---\n")
            
            if (Build.VERSION.SDK_INT >= 30) {
                openCartAndSendReport(token, chatId, message)
            } else {
                addLog("⚠️ إصدار الأندرويد لديك لا يدعم تصوير الشاشة البرمجي. جاري إرسال النص فقط.")
                sendTelegramMessage(token, chatId, message)
            }
        } else {
            addLog("📉 لم يتم العثور على خصومات مطابقة.")
        }

        Thread.sleep(2000)
        addLog("🏠 جاري العودة للشاشة الرئيسية.")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun extractNodes(node: AccessibilityNodeInfo?, nodesList: MutableList<NodeData>) {
        if (node == null) return
        val text = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            if (nodesList.none { it.text == text && it.node == node }) {
                nodesList.add(NodeData(text, node))
            }
        }
        for (i in 0 until node.childCount) {
            extractNodes(node.getChild(i), nodesList)
        }
    }

    private fun analyzeAndAddToCart(nodesList: List<NodeData>, minDiscount: Int): List<String> {
        val deals = mutableListOf<String>()
        val processedProducts = mutableSetOf<String>()
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
        return deals
    }

    private fun processDealNode(p1: Double, p2: Double, nameIdx: Int, uniqueNodes: List<NodeData>, minDiscount: Int, deals: MutableList<String>, processed: MutableSetOf<String>, priceNode: AccessibilityNodeInfo) {
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
                    var parent = priceNode.parent
                    var clickSuccess = false
                    for (level in 0..3) { 
                        if (parent == null) break
                        if (clickAddButtonInParent(parent)) {
                            clickSuccess = true
                            addedItemsCount++
                            break
                        }
                        parent = parent.parent
                    }
                    
                    val status = if (clickSuccess) "✅ (تمت الإضافة)" else "⚠️ (فشل الضغط)"
                    val dealText = "$status **$productName**\n📉 الخصم: $discountPercent%\n💰 $newPrice بدلاً من $oldPrice"
                    deals.add(dealText)
                    processed.add(productName)
                    
                } catch (e: Exception) {
                    addLog("❌ خطأ أثناء إضافة $productName: ${e.message}")
                }
            }
        }
    }

    private fun clickAddButtonInParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (text.contains("+") || text.contains("Add", true) || text.contains("أضف", true) || text.isEmpty()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && clickAddButtonInParent(child)) return true
        }
        return false
    }

    @TargetApi(30)
    private fun openCartAndSendReport(token: String, chatId: String, message: String) {
        addLog("🛒 جاري البحث عن السلة لفتحها...")
        val cartNode = findNodeByText(rootInActiveWindow, "Cart") ?: findNodeByText(rootInActiveWindow, "السلة")
        
        if (cartNode != null) {
            clickNode(cartNode)
            Thread.sleep(4000) 
            
            val screenshots = mutableListOf<ByteArray>()
            val shotsCount = when {
                addedItemsCount <= 7 -> 1
                addedItemsCount in 8..12 -> 2
                else -> 3
            }
            
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
                }
                
                if (i < shotsCount - 1) {
                    swipeUp()
                    Thread.sleep(1500)
                }
            }
            
            if (screenshots.isNotEmpty()) {
                addLog("📸 تم التقاط ${screenshots.size} صور للسلة. جاري الرفع لتليجرام...")
                for ((index, imageBytes) in screenshots.withIndex()) {
                    val caption = if (index == 0) message else "تابع صور السلة..."
                    sendTelegramPhotoMultipart(token, chatId, imageBytes, caption)
                }
            } else {
                sendTelegramMessage(token, chatId, message)
            }
            
        } else {
            addLog("❌ لم يتم العثور على أيقونة السلة.")
            sendTelegramMessage(token, chatId, message)
        }
    }

    @TargetApi(30)
    private fun takeScreenshotSync(): Bitmap? {
        var bitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        
        takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val hwBuffer = screenshot.hardwareBuffer
                bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hwBuffer.close()
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) {
                addLog("❌ فشل التقاط الشاشة، كود الخطأ: $errorCode")
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
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
            outputStream.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"cart.jpg\"\r\n")
            outputStream.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            outputStream.write(imageBytes)
            outputStream.writeBytes("\r\n")

            outputStream.writeBytes("--$boundary--\r\n")
            outputStream.flush()
            outputStream.close()
            
            val responseCode = connection.responseCode
            if (responseCode != 200) addLog("❌ فشل رفع الصورة. كود: $responseCode")
            connection.disconnect()
        } catch (e: Exception) {
            addLog("❌ خطأ أثناء رفع الصورة: ${e.message}")
        }
    }

    private fun sendTelegramMessage(token: String, chatId: String, text: String) {
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://api.telegram.org/bot$token/sendMessage?chat_id=$chatId&text=$encodedText&parse_mode=Markdown")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.inputStream.reader().readText()
            connection.disconnect()
        } catch (e: Exception) {
            addLog("❌ خطأ رسالة التليجرام: ${e.message}")
        }
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

    override fun onInterrupt() {}
}
