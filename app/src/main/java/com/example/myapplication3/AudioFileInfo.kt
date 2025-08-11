package com.example.myapplication3

import java.io.File

data class AudioFileInfo(
    val file: File,
    val durationMs: Long,
    val displayName: String,
    val displayPath: String
)
