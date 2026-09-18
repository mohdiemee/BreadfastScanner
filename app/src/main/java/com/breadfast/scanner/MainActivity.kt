package com.breadfast.scanner

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.graphics.Color
import android.view.Gravity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.WHITE)
        }

        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)

        // 1. إعداد التوكن (مخفي بالنجوم)
        val savedToken = prefs.getString("BOT_TOKEN", "") ?: ""
        val maskedToken = if (savedToken.length > 4) {
            savedToken.take(2) + "*".repeat(savedToken.length - 4) + savedToken.takeLast(2)
        } else savedToken

        val tokenInput = EditText(this).apply {
            hint = if (savedToken.isEmpty()) "Telegram Bot Token" else "Token: $maskedToken (اكتب لتغييره)"
            textSize = 16f
            setPadding(0, 40, 0, 40)
        }

        // 2. إعداد Chat ID
        val chatInput = EditText(this).apply {
            hint = "Telegram Chat ID"
            setText(prefs.getString("CHAT_ID", ""))
            textSize = 16f
            setPadding(0, 40, 0, 40)
        }

        // 3. إعداد نسبة الخصم
        val discountInput = EditText(this).apply {
            hint = "الحد الأدنى للخصم (مثال: 20)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("MIN_DISCOUNT", 20).toString())
            textSize = 16f
            setPadding(0, 40, 0, 40)
        }

        // 4. إعداد وقت الفحص
        val intervalInput = EditText(this).apply {
            hint = "وقت الانتظار بين كل فحص (بالدقائق)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("INTERVAL_MINUTES", 5).toString())
            textSize = 16f
            setPadding(0, 40, 0, 60)
        }

        // زر الحفظ
        val saveBtn = Button(this).apply {
            text = "حفظ الإعدادات"
            setBackgroundColor(Color.parseColor("#2196F3")) // أزرق
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                val inputToken = tokenInput.text.toString()
                prefs.edit().apply {
                    if (inputToken.isNotEmpty()) putString("BOT_TOKEN", inputToken)
                    putString("CHAT_ID", chatInput.text.toString())
                    putInt("MIN_DISCOUNT", discountInput.text.toString().toIntOrNull() ?: 20)
                    putInt("INTERVAL_MINUTES", intervalInput.text.toString().toIntOrNull() ?: 5)
                    apply()
                }
                Toast.makeText(this@MainActivity, "تم الحفظ بنجاح!", Toast.LENGTH_SHORT).show()
                
                // تحديث شكل التوكن فوراً بعد الحفظ
                if (inputToken.isNotEmpty()) {
                    val newMask = if (inputToken.length > 4) inputToken.take(2) + "*".repeat(inputToken.length - 4) + inputToken.takeLast(2) else inputToken
                    tokenInput.hint = "Token: $newMask (اكتب لتغييره)"
                    tokenInput.setText("")
                }
            }
        }

        // حالة البوت (يعمل أو متوقف)
        val statusText = TextView(this).apply {
            val isActive = prefs.getBoolean("IS_ACTIVE", false)
            text = if (isActive) "حالة البوت: يعمل 🟢" else "حالة البوت: متوقف 🔴"
            textSize = 18f
            setPadding(0, 60, 0, 20)
            gravity = Gravity.CENTER
        }

        // زر التشغيل والإيقاف
        val toggleBtn = Button(this).apply {
            val isActive = prefs.getBoolean("IS_ACTIVE", false)
            text = if (isActive) "إيقاف البوت" else "تشغيل البوت"
            setBackgroundColor(if (isActive) Color.parseColor("#F44336") else Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val currentState = prefs.getBoolean("IS_ACTIVE", false)
                val newState = !currentState
                prefs.edit().putBoolean("IS_ACTIVE", newState).apply()
                
                if (newState) {
                    text = "إيقاف البوت"
                    setBackgroundColor(Color.parseColor("#F44336")) // أحمر للإيقاف
                    statusText.text = "حالة البوت: يعمل 🟢"
                } else {
                    text = "تشغيل البوت"
                    setBackgroundColor(Color.parseColor("#4CAF50")) // أخضر للتشغيل
                    statusText.text = "حالة البوت: متوقف 🔴"
                }
            }
        }

        // زر اختصار لفتح الصلاحيات لتسهيل الأمر عليك
        val accessBtn = Button(this).apply {
            text = "فتح إعدادات الصلاحية (Accessibility)"
            setBackgroundColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(tokenInput)
        layout.addView(chatInput)
        layout.addView(discountInput)
        layout.addView(intervalInput)
        layout.addView(saveBtn)
        layout.addView(statusText)
        layout.addView(toggleBtn)
        
        // مسافة صغيرة
        val space = android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) }
        layout.addView(space)
        layout.addView(accessBtn)

        setContentView(layout)
    }
}
