package com.monde.tv

data class MediaItem(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val category: String = "TV en vivo"
)
