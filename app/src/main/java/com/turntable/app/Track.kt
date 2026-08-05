package com.turntable.app

data class Track(
    val uri: String,
    val name: String,
    var hidden: Boolean = false,
    var durationMs: Long = 0L
)
