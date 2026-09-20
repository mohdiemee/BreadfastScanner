package com.breadfast.scanner

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.ResultIterator
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
                ?.map { line ->
                    line.trim()
                }
                ?.filter { line ->
                    line.isNotBlank()
                }
                ?.joinToString("\n")
                ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            api.clear()
        }
    }

    fun recognizeWords(
        bitmap: Bitmap
    ): List<OcrWord> {
        val api = tess ?: return emptyList()

        return try {
            api.setImage(bitmap)

            val iterator: ResultIterator =
                api.resultIterator
                    ?: return emptyList()

            val level =
                TessBaseAPI.PageIteratorLevel.RIL_WORD

            val words = mutableListOf<OcrWord>()

            var sequence = 0

            iterator.begin()

            do {
                val rawText =
                    iterator.getUTF8Text(level)
                        ?.replace("\u000C", "")
                        ?.replace("\n", " ")
                        ?.replace("\r", " ")
                        ?.trim()
                        .orEmpty()

                val confidence =
                    iterator.confidence(level)

                if (
                    rawText.isNotBlank() &&
                    confidence >= 15f
                ) {
                    words.add(
                        OcrWord(
                            text = rawText,
                            left = 0,
                            top = sequence * 40,
                            right = 0,
                            bottom = (sequence * 40) + 30,
                            confidence = confidence
                        )
                    )

                    sequence++
                }
            } while (
                iterator.next(level)
            )

            words
        } catch (_: Exception) {
            emptyList()
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

        copyAssetIfMissing(
            "tessdata/ara.traineddata"
        )

        copyAssetIfMissing(
            "tessdata/eng.traineddata"
        )
    }

    private fun copyAssetIfMissing(
        assetPath: String
    ) {
        val fileName =
            assetPath.substringAfterLast("/")

        val destination =
            File(tessDataDir, fileName)

        if (
            destination.exists() &&
            destination.length() > 0
        ) {
            return
        }

        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}
