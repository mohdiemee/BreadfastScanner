package com.breadfast.scanner

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import java.util.Calendar

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F5F5F5")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        // --- التوكن ---
        val savedToken = prefs.getString("BOT_TOKEN", "") ?: ""
        val maskedToken = if (savedToken.length > 4) savedToken.take(2) + "*".repeat(savedToken.length - 4) + savedToken.takeLast(2) else savedToken

        layout.addView(createLabel("Telegram Bot Token:"))
        val tokenInput = EditText(this).apply {
            setText(maskedToken)
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(tokenInput)

        // --- Chat ID ---
        layout.addView(createLabel("Telegram Chat ID:"))
        val chatInput = EditText(this).apply {
            setText(prefs.getString("CHAT_ID", ""))
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(chatInput)

        // --- الخصم ---
        layout.addView(createLabel("الحد الأدنى للخصم (%):"))
        val discountInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("MIN_DISCOUNT", 40).toString())
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(discountInput)

        // --- التكرار ---
        layout.addView(createLabel("عدم تكرار العرض قبل (ساعات):"))
        val cooldownInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("COOLDOWN_HOURS", 24).toString())
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(cooldownInput)

        // --- مواعيد التشغيل ---
        layout.addView(createLabel("مواعيد التشغيل (مثال: 23:50, 05:50):"))
        val timesInput = EditText(this).apply {
            setText(prefs.getString("RUN_TIMES", "23:50, 05:50, 11:50"))
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(timesInput)
        
        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        // --- اختيار التطبيق (App Picker) ---
        layout.addView(createLabel("التطبيق المستهدف للمراقبة:"))
        val selectAppBtn = Button(this).apply {
            val savedPkg = prefs.getString("TARGET_PACKAGE", "com.breadfast.application")
            text = "التطبيق المحدد: $savedPkg\n(اضغط لاختيار تطبيق آخر)"
            setBackgroundColor(Color.parseColor("#FF9800")) // برتقالي
            setTextColor(Color.WHITE)
            setPadding(20, 30, 20, 30)
            setOnClickListener {
                showAppPickerDialog(this, prefs)
            }
        }
        layout.addView(selectAppBtn)

        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // --- زر الحفظ ---
        val saveBtn = Button(this).apply {
            text = "حفظ الإعدادات وتفعيل الجدولة"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener {
                val inputToken = tokenInput.text.toString()
                prefs.edit().apply {
                    if (inputToken.isNotEmpty() && !inputToken.contains("*")) putString("BOT_TOKEN", inputToken)
                    putString("CHAT_ID", chatInput.text.toString())
                    putInt("MIN_DISCOUNT", discountInput.text.toString().toIntOrNull() ?: 40)
                    putInt("COOLDOWN_HOURS", cooldownInput.text.toString().toIntOrNull() ?: 24)
                    putString("RUN_TIMES", timesInput.text.toString())
                    apply()
                }
                
                scheduleAlarms(timesInput.text.toString())
                Toast.makeText(this@MainActivity, "تم الحفظ والجدولة بنجاح!", Toast.LENGTH_LONG).show()
                
                val newlySavedToken = prefs.getString("BOT_TOKEN", "") ?: ""
                val newMask = if (newlySavedToken.length > 4) newlySavedToken.take(2) + "*".repeat(newlySavedToken.length - 4) + newlySavedToken.takeLast(2) else newlySavedToken
                tokenInput.setText(newMask)
            }
        }
        layout.addView(saveBtn)

        // --- حالة البوت ---
        val statusText = TextView(this).apply {
            val isActive = prefs.getBoolean("IS_ACTIVE", false)
            text = if (isActive) "حالة البوت: يعمل 🟢" else "حالة البوت: متوقف 🔴"
            textSize = 18f
            setPadding(0, 40, 0, 20)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        layout.addView(statusText)

        // --- زر التشغيل/الإيقاف ---
        val toggleBtn = Button(this).apply {
            val isActive = prefs.getBoolean("IS_ACTIVE", false)
            text = if (isActive) "إيقاف البوت" else "تشغيل البوت"
            setBackgroundColor(if (isActive) Color.parseColor("#F44336") else Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener {
                val newState = !prefs.getBoolean("IS_ACTIVE", false)
                prefs.edit().putBoolean("IS_ACTIVE", newState).apply()
                if (newState) {
                    text = "إيقاف البوت"
                    setBackgroundColor(Color.parseColor("#F44336"))
                    statusText.text = "حالة البوت: يعمل 🟢"
                    scheduleAlarms(timesInput.text.toString())
                } else {
                    text = "تشغيل البوت"
                    setBackgroundColor(Color.parseColor("#4CAF50"))
                    statusText.text = "حالة البوت: متوقف 🔴"
                }
            }
        }
        layout.addView(toggleBtn)
        
        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // --- زر الصلاحيات ---
        val accessBtn = Button(this).apply {
            text = "فتح إعدادات الصلاحية (Accessibility)"
            setBackgroundColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        layout.addView(accessBtn)

        // --- قسم السجلات (Logs) ---
        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        layout.addView(createLabel("سجل الأحداث (Logs):"))
        
        val logText = TextView(this).apply {
            text = prefs.getString("APP_LOGS", "لا توجد سجلات حتى الآن.")
            textSize = 12f
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            setPadding(20, 20, 20, 20)
            setTextColor(Color.BLACK)
        }
        layout.addView(logText)

        val logButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 40)
        }
        
        val refreshLogBtn = Button(this).apply {
            text = "تحديث السجل"
            setBackgroundColor(Color.GRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                logText.text = prefs.getString("APP_LOGS", "لا توجد سجلات حتى الآن.")
            }
        }
        
        val clearLogBtn = Button(this).apply {
            text = "مسح السجل"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                prefs.edit().putString("APP_LOGS", "").apply()
                logText.text = "تم مسح السجلات."
            }
        }
        
        logButtonsLayout.addView(refreshLogBtn)
        logButtonsLayout.addView(clearLogBtn)
        layout.addView(logButtonsLayout)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 20, 0, 5)
        }
    }

    // --- دالة اختيار التطبيق (App Picker Dialog) ---
    private fun showAppPickerDialog(button: Button, prefs: android.content.SharedPreferences) {
        val pm = packageManager
        Toast.makeText(this, "جاري جلب التطبيقات...", Toast.LENGTH_SHORT).show()
        
        // استخراج التطبيقات المثبتة التي تمتلك واجهة للفتح
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val launchableApps = packages.filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val appNames = launchableApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("اختر التطبيق المطلوب مراقبته")
            .setItems(appNames) { _, which ->
                val selectedApp = launchableApps[which]
                val pkgName = selectedApp.packageName
                val appName = pm.getApplicationLabel(selectedApp).toString()
                
                // حفظ اسم الحزمة
                prefs.edit().putString("TARGET_PACKAGE", pkgName).apply()
                button.text = "التطبيق المحدد: $appName\n($pkgName)"
                Toast.makeText(this, "تم اختيار: $appName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun scheduleAlarms(times: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val timeList = times.split(",").map { it.trim() }

        timeList.forEachIndexed { index, timeStr ->
            val parts = timeStr.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull() ?: return@forEachIndexed
                val minute = parts[1].toIntOrNull() ?: return@forEachIndexed
                
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                }
                
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                
                val pendingIntent = PendingIntent.getBroadcast(this, index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        }
    }
}
