package com.breadfast.scanner

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestExactAlarmPermissionIfNeeded()

        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F5F5F5")) }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // ==========================================
        // 1. الإعدادات العامة (مشتركة بين التطبيقين)
        // ==========================================
        val savedToken = prefs.getString("BOT_TOKEN", "") ?: ""
        val maskedToken = if (savedToken.length > 4) savedToken.take(2) + "*".repeat(savedToken.length - 4) + savedToken.takeLast(2) else savedToken

        mainLayout.addView(createLabel("Telegram Bot Token (مشترك):"))
        val tokenInput = createEditText(maskedToken, InputType.TYPE_CLASS_TEXT)
        mainLayout.addView(tokenInput)

        mainLayout.addView(createLabel("Telegram Chat ID (مشترك):"))
        val chatInput = createEditText(prefs.getString("CHAT_ID", ""), InputType.TYPE_CLASS_TEXT)
        mainLayout.addView(chatInput)

        mainLayout.addView(createLabel("عدم تكرار العرض قبل (ساعات):"))
        val cooldownInput = createEditText(prefs.getInt("COOLDOWN_HOURS", 24).toString(), InputType.TYPE_CLASS_NUMBER)
        mainLayout.addView(cooldownInput)

        mainLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // ==========================================
        // 2. نظام التبويبات (Tabs) للتطبيقات
        // ==========================================
        val tabsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val breadfastLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.VISIBLE }
        val rabbitLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        val btnTabBreadfast = Button(this).apply {
            text = "إعدادات بريدفاست"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val btnTabRabbit = Button(this).apply {
            text = "إعدادات رابيت"
            setBackgroundColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnTabBreadfast.setOnClickListener {
            breadfastLayout.visibility = View.VISIBLE
            rabbitLayout.visibility = View.GONE
            btnTabBreadfast.setBackgroundColor(Color.parseColor("#FF9800"))
            btnTabRabbit.setBackgroundColor(Color.parseColor("#9E9E9E"))
        }

        btnTabRabbit.setOnClickListener {
            breadfastLayout.visibility = View.GONE
            rabbitLayout.visibility = View.VISIBLE
            btnTabBreadfast.setBackgroundColor(Color.parseColor("#9E9E9E"))
            btnTabRabbit.setBackgroundColor(Color.parseColor("#4CAF50"))
        }

        tabsLayout.addView(btnTabBreadfast)
        tabsLayout.addView(btnTabRabbit)
        mainLayout.addView(tabsLayout)
        mainLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })

        // ------------------------------------------
        // قسم بريدفاست
        // ------------------------------------------
        breadfastLayout.addView(createLabel("الحد الأدنى لخصم بريدفاست (%):"))
        val bfDiscountInput = createEditText(prefs.getInt("MIN_DISCOUNT", 46).toString(), InputType.TYPE_CLASS_NUMBER)
        breadfastLayout.addView(bfDiscountInput)

        breadfastLayout.addView(createLabel("مواعيد الجدولة (مثال: 10:05, 12:05):"))
        val bfTimesInput = createEditText(prefs.getString("BREADFAST_RUN_TIMES", "10:05, 12:05, 14:05, 16:05, 18:05, 20:05, 22:05"), InputType.TYPE_CLASS_TEXT)
        breadfastLayout.addView(bfTimesInput)

        breadfastLayout.addView(createLabel("كلمات سلبية مستبعدة (مفصولة بفاصلة):"))
        val bfKeywordsInput = createEditText(prefs.getString("BREADFAST_NEGATIVE_KEYWORDS", "جراب,واقي عدسات,واقي شاشة"), InputType.TYPE_CLASS_TEXT)
        breadfastLayout.addView(bfKeywordsInput)

        breadfastLayout.addView(createLabel("حزمة بريدفاست (Target Package):"))
        val bfSelectAppBtn = Button(this).apply {
            val savedPkg = prefs.getString("TARGET_PACKAGE", "com.breadfast.application")
            text = "$savedPkg\n(اضغط للتغيير)"
            setBackgroundColor(Color.parseColor("#FFCC80"))
            setOnClickListener { showAppPickerDialog(this, prefs, "TARGET_PACKAGE") }
        }
        breadfastLayout.addView(bfSelectAppBtn)

        // حالة ومفتاح تشغيل جدولة بريدفاست
        val bfStatusText = TextView(this).apply {
            val isActive = prefs.getBoolean("BREADFAST_ACTIVE", false)
            text = if (isActive) "حالة بريدفاست: مفعل 🟢" else "حالة بريدفاست: متوقف 🔴"
            textSize = 16f
            setPadding(0, 30, 0, 10)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        breadfastLayout.addView(bfStatusText)

        val btnToggleBreadfast = Button(this).apply {
            val isActive = prefs.getBoolean("BREADFAST_ACTIVE", false)
            text = if (isActive) "إيقاف جدولة بريدفاست" else "تفعيل جدولة بريدفاست"
            setBackgroundColor(if (isActive) Color.parseColor("#F44336") else Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 0) }
        }
        breadfastLayout.addView(btnToggleBreadfast)

        // ------------------------------------------
        // قسم رابيت
        // ------------------------------------------
        rabbitLayout.addView(createLabel("الحد الأدنى لخصم رابيت (%):"))
        val rbDiscountInput = createEditText(prefs.getInt("RABBIT_MIN_DISCOUNT", 41).toString(), InputType.TYPE_CLASS_NUMBER)
        rabbitLayout.addView(rbDiscountInput)

        rabbitLayout.addView(createLabel("مواعيد الجدولة (مثال: 11:05, 13:05):"))
        val rbTimesInput = createEditText(prefs.getString("RABBIT_RUN_TIMES", "11:05, 13:05, 15:05, 17:05, 19:05, 21:05, 23:05"), InputType.TYPE_CLASS_TEXT)
        rabbitLayout.addView(rbTimesInput)

        rabbitLayout.addView(createLabel("كلمات سلبية مستبعدة (مفصولة بفاصلة):"))
        val rbKeywordsInput = createEditText(prefs.getString("RABBIT_NEGATIVE_KEYWORDS", "جراب,واقي عدسات,واقي شاشة"), InputType.TYPE_CLASS_TEXT)
        rabbitLayout.addView(rbKeywordsInput)

        rabbitLayout.addView(createLabel("حزمة رابيت (Target Package):"))
        val rbSelectAppBtn = Button(this).apply {
            val savedPkg = prefs.getString("RABBIT_PACKAGE", "com.rabbit.grocery")
            text = "$savedPkg\n(اضغط للتغيير)"
            setBackgroundColor(Color.parseColor("#A5D6A7"))
            setOnClickListener { showAppPickerDialog(this, prefs, "RABBIT_PACKAGE") }
        }
        rabbitLayout.addView(rbSelectAppBtn)

        // حالة ومفتاح تشغيل جدولة رابيت
        val rbStatusText = TextView(this).apply {
            val isActive = prefs.getBoolean("RABBIT_ACTIVE", false)
            text = if (isActive) "حالة رابيت: مفعل 🟢" else "حالة رابيت: متوقف 🔴"
            textSize = 16f
            setPadding(0, 30, 0, 10)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        rabbitLayout.addView(rbStatusText)

        val btnToggleRabbit = Button(this).apply {
            val isActive = prefs.getBoolean("RABBIT_ACTIVE", false)
            text = if (isActive) "إيقاف جدولة رابيت" else "تفعيل جدولة رابيت"
            setBackgroundColor(if (isActive) Color.parseColor("#F44336") else Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 0) }
        }
        rabbitLayout.addView(btnToggleRabbit)

        mainLayout.addView(breadfastLayout)
        mainLayout.addView(rabbitLayout)
        mainLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // ==========================================
        // 3. برمجة أزرار التشغيل المستقلة ودالة الجدولة
        // ==========================================
        fun syncSchedules() {
            val bfActive = prefs.getBoolean("BREADFAST_ACTIVE", false)
            val rbActive = prefs.getBoolean("RABBIT_ACTIVE", false)
            
            // السماح للخدمة بالعمل إذا كان أي من التطبيقين مفعلاً
            prefs.edit().putBoolean("IS_ACTIVE", bfActive || rbActive).apply()
            
            // مسح كل الجداول لتجنب التكرار
            AlarmScheduler.cancelAll(this@MainActivity)
            
            // جدولة بريدفاست فقط إذا كان مفعلاً
            if (bfActive) {
                AlarmScheduler.scheduleAllForApp(this@MainActivity, "BREADFAST", bfTimesInput.text.toString())
            }
            
            // جدولة رابيت فقط إذا كان مفعلاً
            if (rbActive) {
                AlarmScheduler.scheduleAllForApp(this@MainActivity, "RABBIT", rbTimesInput.text.toString())
            }
        }

        btnToggleBreadfast.setOnClickListener {
            val newState = !prefs.getBoolean("BREADFAST_ACTIVE", false)
            prefs.edit().putBoolean("BREADFAST_ACTIVE", newState).apply()
            
            if (newState) {
                btnToggleBreadfast.text = "إيقاف جدولة بريدفاست"
                btnToggleBreadfast.setBackgroundColor(Color.parseColor("#F44336"))
                bfStatusText.text = "حالة بريدفاست: مفعل 🟢"
            } else {
                btnToggleBreadfast.text = "تفعيل جدولة بريدفاست"
                btnToggleBreadfast.setBackgroundColor(Color.parseColor("#FF9800"))
                bfStatusText.text = "حالة بريدفاست: متوقف 🔴"
            }
            syncSchedules()
        }

        btnToggleRabbit.setOnClickListener {
            val newState = !prefs.getBoolean("RABBIT_ACTIVE", false)
            prefs.edit().putBoolean("RABBIT_ACTIVE", newState).apply()
            
            if (newState) {
                btnToggleRabbit.text = "إيقاف جدولة رابيت"
                btnToggleRabbit.setBackgroundColor(Color.parseColor("#F44336"))
                rbStatusText.text = "حالة رابيت: مفعل 🟢"
            } else {
                btnToggleRabbit.text = "تفعيل جدولة رابيت"
                btnToggleRabbit.setBackgroundColor(Color.parseColor("#4CAF50"))
                rbStatusText.text = "حالة رابيت: متوقف 🔴"
            }
            syncSchedules()
        }

        val saveBtn = Button(this).apply {
            text = "حفظ كافة الإعدادات 💾"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnClickListener {
                val inputToken = tokenInput.text.toString()
                prefs.edit().apply {
                    if (inputToken.isNotEmpty() && !inputToken.contains("*")) putString("BOT_TOKEN", inputToken)
                    putString("CHAT_ID", chatInput.text.toString())
                    putInt("COOLDOWN_HOURS", cooldownInput.text.toString().toIntOrNull() ?: 24)
                    
                    putInt("MIN_DISCOUNT", bfDiscountInput.text.toString().toIntOrNull() ?: 46) // قيمة الخصم الافتراضية
                    putString("BREADFAST_RUN_TIMES", bfTimesInput.text.toString())
                    putString("BREADFAST_NEGATIVE_KEYWORDS", bfKeywordsInput.text.toString())
                    
                    putInt("RABBIT_MIN_DISCOUNT", rbDiscountInput.text.toString().toIntOrNull() ?: 41) // قيمة الخصم الافتراضية
                    putString("RABBIT_RUN_TIMES", rbTimesInput.text.toString())
                    putString("RABBIT_NEGATIVE_KEYWORDS", rbKeywordsInput.text.toString())
                    apply()
                }
                
                syncSchedules() // تحديث الجداول بعد حفظ الأوقات الجديدة
                
                Toast.makeText(this@MainActivity, "تم حفظ الإعدادات وتحديث الجداول المفعّلة!", Toast.LENGTH_LONG).show()
                val newlySavedToken = prefs.getString("BOT_TOKEN", "") ?: ""
                tokenInput.setText(if (newlySavedToken.length > 4) newlySavedToken.take(2) + "*".repeat(newlySavedToken.length - 4) + newlySavedToken.takeLast(2) else newlySavedToken)
            }
        }
        mainLayout.addView(saveBtn)

        val accessBtn = Button(this).apply {
            text = "فتح إعدادات الصلاحية (Accessibility)"
            setBackgroundColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 20, 0, 0) }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        mainLayout.addView(accessBtn)

        // ==========================================
        // 4. سجل الأحداث (Logs)
        // ==========================================
        mainLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        mainLayout.addView(createLabel("سجل الأحداث (Logs):"))
        
        val logText = TextView(this).apply {
            text = prefs.getString("APP_LOGS", "لا توجد سجلات حتى الآن.")
            textSize = 12f
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            setPadding(20, 20, 20, 20)
            setTextColor(Color.BLACK)
        }
        mainLayout.addView(logText)

        val logButtonsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 40) }
        
        val refreshLogBtn = Button(this).apply {
            text = "تحديث السجل"
            setBackgroundColor(Color.GRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { logText.text = prefs.getString("APP_LOGS", "لا توجد سجلات حتى الآن.") }
        }

        val copyLogBtn = Button(this).apply {
            text = "نسخ السجل"
            setBackgroundColor(Color.parseColor("#2196F3")) 
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("ScannerLogs", prefs.getString("APP_LOGS", ""))
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "تم نسخ السجل بنجاح!", Toast.LENGTH_SHORT).show()
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
        logButtonsLayout.addView(copyLogBtn)
        logButtonsLayout.addView(clearLogBtn)
        mainLayout.addView(logButtonsLayout)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun createLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setPadding(0, 20, 0, 5)
    }

    private fun createEditText(defaultText: String?, inputTypeParam: Int): EditText = EditText(this).apply {
        setText(defaultText)
        inputType = inputTypeParam
        textSize = 16f
        setPadding(20, 30, 20, 30)
        setBackgroundColor(Color.WHITE)
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(this, "يرجى تفعيل الصلاحية لتشغيل البوت في الوقت المحدد.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAppPickerDialog(button: Button, prefs: SharedPreferences, prefKey: String) {
        val pm = packageManager
        Toast.makeText(this, "جاري جلب التطبيقات...", Toast.LENGTH_SHORT).show()
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val launchableApps = packages.filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val appNames = launchableApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()

        AlertDialog.Builder(this).setTitle("اختر التطبيق").setItems(appNames) { _, which ->
            val selectedApp = launchableApps[which]
            val pkgName = selectedApp.packageName
            val appName = pm.getApplicationLabel(selectedApp).toString()
            prefs.edit().putString(prefKey, pkgName).apply()
            button.text = "$appName\n($pkgName)"
            Toast.makeText(this, "تم اختيار: $appName", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("إلغاء", null).show()
    }
}
