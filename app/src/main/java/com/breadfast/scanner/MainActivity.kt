package com.breadfast.scanner

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.content.Context
import android.text.InputType
import android.graphics.Color

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // واجهة مستخدم بسيطة
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.WHITE)
        }

        val prefs = getSharedPreferences("ScannerPrefs", Context.MODE_PRIVATE)

        val tokenInput = EditText(this).apply {
            hint = "Telegram Bot Token"
            setText(prefs.getString("BOT_TOKEN", ""))
            textSize = 16f
            setPadding(0, 40, 0, 40)
        }

        val chatInput = EditText(this).apply {
            hint = "Telegram Chat ID"
            setText(prefs.getString("CHAT_ID", ""))
            textSize = 16f
            setPadding(0, 40, 0, 40)
        }

        val discountInput = EditText(this).apply {
            hint = "نسبة الخصم المطلوبة (مثال: 20)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("MIN_DISCOUNT", 20).toString())
            textSize = 16f
            setPadding(0, 40, 0, 80)
        }

        val saveBtn = Button(this).apply {
            text = "حفظ الإعدادات"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener {
                prefs.edit().apply {
                    putString("BOT_TOKEN", tokenInput.text.toString())
                    putString("CHAT_ID", chatInput.text.toString())
                    putInt("MIN_DISCOUNT", discountInput.text.toString().toIntOrNull() ?: 20)
                    apply()
                }
                Toast.makeText(this@MainActivity, "تم الحفظ بنجاح!", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(tokenInput)
        layout.addView(chatInput)
        layout.addView(discountInput)
        layout.addView(saveBtn)

        setContentView(layout)
    }
}
