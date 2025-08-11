package com.example.myapplication3

import android.content.Context
import android.content.SharedPreferences

object AppSettings {

    private const val PREFS_NAME = "AudioRecorderSettings" // Changed to match SettingsActivity or a general name
    private const val KEY_PERMANENT_FILES = "permanent_files_set"

    // Keys for SharedPreferences
    const val KEY_RECORDING_DURATION_SEC = "recording_duration_sec"
    const val KEY_DELETION_TIME_MIN = "deletion_time_minutes"

    // Default values
    const val DEFAULT_RECORDING_DURATION_SEC = 300 // Example: 5 minutes (300 seconds)
    const val DEFAULT_DELETION_TIME_MIN = 10 // Example: 10 minutes

    fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isPermanentFile(context: Context, fileName: String): Boolean {
        val prefs = getSharedPreferences(context)
        val permanentFiles = prefs.getStringSet(KEY_PERMANENT_FILES, emptySet()) ?: emptySet()
        return permanentFiles.contains(fileName)
    }

    fun addPermanentFile(context: Context, fileName: String) {
        val prefs = getSharedPreferences(context)
        val permanentFiles = prefs.getStringSet(KEY_PERMANENT_FILES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (permanentFiles.add(fileName)) { // Add returns true if the set was changed
            prefs.edit().putStringSet(KEY_PERMANENT_FILES, permanentFiles).apply()
        }
    }

    fun removePermanentFile(context: Context, fileName: String) {
        val prefs = getSharedPreferences(context)
        val permanentFiles = prefs.getStringSet(KEY_PERMANENT_FILES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (permanentFiles.remove(fileName)) { // Remove returns true if the set was changed
            prefs.edit().putStringSet(KEY_PERMANENT_FILES, permanentFiles).apply()
        }
    }
}
