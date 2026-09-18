package com.breadfast.scanner

import android.app.Activity
import android.os.Bundle
import android.widget.*
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.graphics.Color
import android.view.Gravity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)
        
        // استخدام ScrollView لتجنب اختفاء الأزرار في الشاشات الصغيرة
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        // --- التوكن ---
        val savedToken = prefs.getString("BOT_TOKEN", "") ?: ""
        val maskedToken = if (savedToken.length > 4) {
            savedToken.take(2) + "*".repeat(savedToken.length - 4) + savedToken.takeLast(2)
        } else savedToken

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

        // --- عدم تكرار العرض ---
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
        layout.addView(createLabel("مواعيد التشغيل (مثال: 23:50, 05:50, 11:50):"))
        val timesInput = EditText(this).apply {
            setText(prefs.getString("RUN_TIMES", "23:50, 05:50, 11:50"))
            textSize = 16f
            setPadding(20, 30, 20, 30)
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(timesInput)
        
        // مسافة
        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // --- زر الحفظ ---
        val saveBtn = Button(this).apply {
            text = "حفظ الإعدادات"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 30, 0, 30)
            setOnClickListener {
                val inputToken = tokenInput.text.toString()
                prefs.edit().apply {
                    // يحفظ التوكن فقط إذا كان لا يحتوي على نجوم (يعني المستخدم غيره فعلاً)
                    if (inputToken.isNotEmpty() && !inputToken.contains("*")) {
                        putString("BOT_TOKEN", inputToken)
                    }
                    putString("CHAT_ID", chatInput.text.toString())
                    putInt("MIN_DISCOUNT", discountInput.text.toString().toIntOrNull() ?: 40)
                    putInt("COOLDOWN_HOURS", cooldownInput.text.toString().toIntOrNull() ?: 24)
                    putString("RUN_TIMES", timesInput.text.toString())
                    apply()
                }
                Toast.makeText(this@MainActivity, "تم الحفظ بنجاح!", Toast.LENGTH_SHORT).show()
                
                // تحديث شكل التوكن في الواجهة بعد الحفظ
                val newlySavedToken = prefs.getString("BOT_TOKEN", "") ?: ""
                val newMask = if (newlySavedToken.length > 4) {
                    newlySavedToken.take(2) + "*".repeat(newlySavedToken.length - 4) + newlySavedToken.takeLast(2)
                } else newlySavedToken
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
            setPadding(0, 30, 0, 30)
            setOnClickListener {
                val currentState = prefs.getBoolean("IS_ACTIVE", false)
                val newState = !currentState
                prefs.edit().putBoolean("IS_ACTIVE", newState).apply()
                
                if (newState) {
                    text = "إيقاف البوت"
                    setBackgroundColor(Color.parseColor("#F44336"))
                    statusText.text = "حالة البوت: يعمل 🟢"
                } else {
                    text = "تشغيل البوت"
                    setBackgroundColor(Color.parseColor("#4CAF50"))
                    statusText.text = "حالة البوت: متوقف 🔴"
                }
            }
        }
        layout.addView(toggleBtn)

        // مسافة
        layout.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // --- زر الصلاحيات ---
        val accessBtn = Button(this).apply {
            text = "فتح إعدادات الصلاحية (Accessibility)"
            setBackgroundColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        layout.addView(accessBtn)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    // دالة مساعدة لإنشاء العناوين (Labels)
    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 20, 0, 5)
        }
    }
}
