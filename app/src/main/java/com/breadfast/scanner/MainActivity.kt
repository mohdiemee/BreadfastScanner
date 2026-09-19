package com.breadfast.scanner

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestExactAlarmPermissionIfNeeded() // طلب صلاحية الجدولة المباشرة

        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F5F5F5")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

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

        layout.addView(createLabel("Telegram Chat ID:"))
        val chatInput = EditText(this).apply {
            setText(prefs.getString("CHAT_ID", ""))
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(chatInput)

        layout.addView(createLabel("الحد الأدنى للخصم (%):"))
        val discountInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("MIN_DISCOUNT", 40).toString())
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(discountInput)

        layout.addView(createLabel("عدم تكرار العرض قبل (ساعات):"))
        val cooldownInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("COOLDOWN_HOURS", 24).toString())
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(cooldownInput)

        layout.addView(createLabel("مواعيد التشغيل (مثال: 23:50, 05:50):"))
        val timesInput = EditText(this).apply {
            setText(prefs.getString("RUN_TIMES", "23:50, 05:50, 11:50"))
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(timesInput)
        
        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        layout.addView(createLabel("التطبيق المستهدف للمراقبة:"))
        val selectAppBtn = Button(this).apply {
            val savedPkg = prefs.getString("TARGET_PACKAGE", "com.breadfast.application")
            text = "التطبيق المحدد: $savedPkg\n(اضغط لاختيار تطبيق آخر)"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            setPadding(20, 30, 20, 30)
            setOnClickListener { showAppPickerDialog(this, prefs) }
        }
        layout.addView(selectAppBtn)

        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

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
                
                AlarmScheduler.scheduleAll(this@MainActivity, timesInput.text.toString())
                Toast.makeText(this@MainActivity, "تم الحفظ والجدولة بنجاح!", Toast.LENGTH_LONG).show()
                
                val newlySavedToken = prefs.getString("BOT_TOKEN", "") ?: ""
                val newMask = if (newlySavedToken.length > 4) newlySavedToken.take(2) + "*".repeat(newlySavedToken.length - 4) + newlySavedToken.takeLast(2) else newlySavedToken
                tokenInput.setText(newMask)
            }
        }
        layout.addView(saveBtn)

        val statusText = TextView(this).apply {
            val isActive = prefs.getBoolean("IS_ACTIVE", false)
            text = if (isActive) "حالة البوت: يعمل 🟢" else "حالة البوت: متوقف 🔴"
            textSize = 18f
            setPadding(0, 40, 0, 20)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        layout.addView(statusText)

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
                    AlarmScheduler.scheduleAll(this@MainActivity, timesInput.text.toString())
                } else {
                    text = "تشغيل البوت"
                    setBackgroundColor(Color.parseColor("#4CAF50"))
                    statusText.text = "حالة البوت: متوقف 🔴"
                    AlarmScheduler.cancelAll(this@MainActivity)
                    prefs.edit().putBoolean("IS_AUTO_RUNNING", false).apply()
                }
            }
        }
        layout.addView(toggleBtn)
        
        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        val accessBtn = Button(this).apply {
            text = "فتح إعدادات الصلاحية (Accessibility)"
            setBackgroundColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        layout.addView(accessBtn)

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
            setOnClickListener { logText.text = prefs.getString("APP_LOGS", "لا توجد سجلات حتى الآن.") }
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

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(this, "يرجى تفعيل صلاحية Alarms & reminders لتشغيل البوت في الوقت المحدد.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAppPickerDialog(button: Button, prefs: android.content.SharedPreferences) {
        val pm = packageManager
        Toast.makeText(this, "جاري جلب التطبيقات...", Toast.LENGTH_SHORT).show()
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val launchableApps = packages.filter { pm.getLaunchIntentForPackage(it.packageName) != null }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val appNames = launchableApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()

        AlertDialog.Builder(this).setTitle("اختر التطبيق المطلوب مراقبته").setItems(appNames) { _, which ->
            val selectedApp = launchableApps[which]
            val pkgName = selectedApp.packageName
            val appName = pm.getApplicationLabel(selectedApp).toString()
            prefs.edit().putString("TARGET_PACKAGE", pkgName).apply()
            button.text = "التطبيق المحدد: $appName\n($pkgName)"
            Toast.makeText(this, "تم اختيار: $appName", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("إلغاء", null).show()
    }
}
