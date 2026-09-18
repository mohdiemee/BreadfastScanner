package com.breadfast.scanner

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
                
                // تفعيل المواعيد في نظام الأندرويد
                scheduleAlarms(timesInput.text.toString())
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
                    scheduleAlarms(timesInput.text.toString()) // إعادة جدولة عند التشغيل
                } else {
                    text = "تشغيل البوت"
                    setBackgroundColor(Color.parseColor("#4CAF50"))
                    statusText.text = "حالة البوت: متوقف 🔴"
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
                    calendar.add(Calendar.DAY_OF_YEAR, 1) // إذا مر الوقت اليوم، اجدوله للغد
                }
                
                val pendingIntent = PendingIntent.getBroadcast(this, index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                
                // طلب فتح دقيق وموقظ للنظام
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        }
    }
}
