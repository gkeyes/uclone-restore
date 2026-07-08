package com.uclone.restore.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    private val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun time(millis: Long?): String = when (millis) {
        null, 0L -> "未建立"
        else -> dateTime.format(Date(millis))
    }

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var size = value / 1024.0
        var index = 0
        while (size >= 1024 && index < units.lastIndex) {
            size /= 1024.0
            index += 1
        }
        return "%.1f %s".format(Locale.US, size, units[index])
    }

    fun kilobytes(value: Long?): String = when {
        value == null -> "未建立"
        value <= 0L -> "0 KB"
        else -> bytes(value * 1024)
    }
}
