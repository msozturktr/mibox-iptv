package com.mibox.iptv

data class Channel(
    val id: String,
    var number: Int,
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String = "Genel"
)
