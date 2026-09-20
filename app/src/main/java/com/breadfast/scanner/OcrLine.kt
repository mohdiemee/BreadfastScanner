package com.breadfast.scanner

data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val words: List<OcrWord>
)
