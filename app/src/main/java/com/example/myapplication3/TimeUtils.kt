// File: TimeUtils.kt
package com.example.myapplication3

import java.util.concurrent.TimeUnit

fun formatElapsedTime(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return String.format("%02d:%02d", minutes, seconds)
}
