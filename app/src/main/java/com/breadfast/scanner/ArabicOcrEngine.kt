package com.breadfast.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class ArabicOcrEngine(
    private val context: Context
) {
    private val tessDataPath: String =
        File(context.filesDir, "tesseract").absolutePath

    private val tessDataDir: File =
        File(tessDataPath, "tessdata")

    private var tess: TessBaseAPI? = null

    fun initialize(): Boolean {
        return try {
            copyTrainingData()

            val api = TessBaseAPI()

            val initialized = api.init(
                tessDataPath,
                "ara+eng"
            )

            if (!initialized) {
                api.recycle()
                false
            } else {
                api.pageSegMode =
                    TessBaseAPI.PageSegMode.PSM_AUTO

                tess = api
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun recognize(bitmap: Bitmap): String {
        val api = tess ?: return ""

        return try {
            api.setImage(bitmap)

            api.utF8Text
                ?.replace("\u000C", "")
                ?.replace("\r", "")
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
                ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            api.clear()
        }
    }

    fun recycle() {
        try {
            tess?.recycle()
        } catch (_: Exception) {
        } finally {
            tess = null
        }
    }

    private fun copyTrainingData() {
        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        copyAssetIfMissing("tessdata/ara.traineddata")
        copyAssetIfMissing("tessdata/eng.traineddata")
    }

    private fun copyAssetIfMissing(assetPath: String) {
        val fileName = assetPath.substringAfterLast("/")
        val destination = File(tessDataDir, fileName)

        if (destination.exists() && destination.length() > 0) {
            return
        }

        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}
