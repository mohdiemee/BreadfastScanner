package com.breadfast.scanner

data class OcrCartProduct(
    val name: String,
    val price: String,
    val oldPrice: String?,
    val discount: Int?,
    val topY: Int = 0,
    val bottomY: Int = 0
) {
    fun toDealText(): String {
        val discountText =
            discount?.let { " بخصم $it%" } ?: ""

        return "$name ب $price جنيه$discountText"
    }
}
