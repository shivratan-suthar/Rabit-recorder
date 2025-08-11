package com.example.myapplication3

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
// import android.os.Environment // Not strictly needed in this snippet, but likely in full file
import android.util.Log
import android.widget.Toast
import java.io.File
// import java.util.Calendar // Calendar will be removed for the new logic

// Interface for MainActivity to provide necessary dependencies and actions
interface MainActivityFileManagerHost {
    val appSharedPreferences: SharedPreferences
    val normalAudioFiles: MutableList<AudioFileInfo>
    val permanentAudioFiles: MutableList<AudioFileInfo>
    val normalAdapter: AudioFileAdapter?
    val permanentAdapter: AudioFileAdapter?
    val currentlyPlayingPath: String?

    fun stopPlayback()
    fun showToast(message: String, duration: Int)
    fun logDebug(tag: String, message: String)
    fun logInfo(tag: String, message: String)
    fun logError(tag: String, message: String, throwable: Throwable? = null)
    fun getExternalMusicDir(): File?
    fun refreshAdapters()
}

class AudioFileManager(
    private val context: Context,
    private val host: MainActivityFileManagerHost
) {

    private companion object {
        private const val TAG = "AudioFileManager"
        // Key for SharedPreferences, must match the one in MainActivity
        private const val PREF_KEEP_REC_HOURS_FLOAT = "keep_rec_hours_float"
    }

    fun getAudioDurationMs(file: File): Long {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            mmr.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            host.logError(TAG, "getAudioDurationMs failed for ${file.name}", e)
            0L
        }
    }

    fun addOrUpdateSingleRecording(filePath: String) {
        host.logDebug(TAG, "addOrUpdateSingleRecording: Processing $filePath")
        val file = File(filePath)

        if (!file.exists() || !file.isFile || !(file.name.endsWith(".mp4") || file.name.endsWith(".aac") || file.name.endsWith(".m4a"))) {
            host.logError(TAG, "File does not exist, is not a file, or has an unsupported extension: $filePath")
            return
        }

        val duration = getAudioDurationMs(file)
        // It's important that duration is correctly fetched here, after the file is saved.
        if (duration == 0L) {
            host.logInfo(TAG, "addOrUpdateSingleRecording: File ${file.name} has 0ms duration. It might be empty or corrupted.")
            // Depending on desired behavior, you might choose to not add 0-duration files,
            // or add them as they are. For now, we'll add it.
        }

        val newAudioInfo = AudioFileInfo(file, duration, file.nameWithoutExtension, file.parentFile?.absolutePath ?: file.absolutePath)

        val isPermanentParentDir = file.parentFile?.name == "permanent"
        // Check SharedPreferences as well, though a new recording is unlikely to be marked permanent immediately
        val isMarkedPermanent = AppSettings.isPermanentFile(context, file.name)

        val targetList: MutableList<AudioFileInfo>
        val isEffectivelyPermanent = isPermanentParentDir || isMarkedPermanent

        if (isEffectivelyPermanent) {
            targetList = host.permanentAudioFiles
        } else {
            targetList = host.normalAudioFiles
        }

        // Remove any existing entry for this file path. This handles the case where a
        // preliminary (potentially 0-duration) entry was added by loadRecordedFiles().
        val removed = targetList.removeAll { it.file.absolutePath == filePath }
        if (removed) {
            host.logDebug(TAG, "Removed pre-existing entry for ${file.name} before adding updated one.")
        }
        targetList.add(newAudioInfo)

        // Sort the list again (typically by date)
        if (isEffectivelyPermanent) {
            host.permanentAudioFiles.sortByDescending { it.file.lastModified() }
        } else {
            host.normalAudioFiles.sortByDescending { it.file.lastModified() }
        }

        host.refreshAdapters()
        host.logInfo(TAG, "Added/Updated recording: ${newAudioInfo.displayName} with duration ${newAudioInfo.durationMs}ms to ${if(isEffectivelyPermanent) "permanent" else "normal"} list.")
    }


    fun loadRecordedFiles() {
        host.logDebug(TAG, "loadRecordedFiles: Starting to load files.")
        val wasPlayingPath = host.currentlyPlayingPath

        host.normalAudioFiles.clear()
        host.permanentAudioFiles.clear()

        val normalDir = host.getExternalMusicDir()
        val permDir = normalDir?.let { File(it, "permanent") }

        val keepRecordingsHoursFloat = host.appSharedPreferences.getFloat(PREF_KEEP_REC_HOURS_FLOAT, 0.0f)
        var deletionThresholdTimeMillis: Long? = null

        if (keepRecordingsHoursFloat > 0.0f) {
            val keepDurationMillis = (keepRecordingsHoursFloat * 60 * 60 * 1000).toLong()
            deletionThresholdTimeMillis = System.currentTimeMillis() - keepDurationMillis
            host.logInfo(TAG, "Keep recordings for: $keepRecordingsHoursFloat hours. Deletion threshold (epoch ms): $deletionThresholdTimeMillis")
        } else {
            host.logInfo(TAG, "Keep recordings feature is off or set to 0 hours.")
        }

        if (normalDir != null && normalDir.exists()) {
            normalDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".aac") || file.name.endsWith(".m4a"))) {
                    val isPermanentParentDir = file.parentFile?.name == "permanent"
                    val isMarkedPermanent = AppSettings.isPermanentFile(context, file.name)
                    val isEffectivelyPermanent = isPermanentParentDir || isMarkedPermanent

                    var fileDeleted = false
                    if (deletionThresholdTimeMillis != null && file.lastModified() < deletionThresholdTimeMillis && !isEffectivelyPermanent) {
                        host.logInfo(TAG, "Attempting to delete old non-permanent file: ${file.name}.")
                        if (file.absolutePath == host.currentlyPlayingPath) {
                            host.stopPlayback()
                        }
                        if (file.delete()) {
                            host.logInfo(TAG, "Successfully deleted: ${file.name}")
                            fileDeleted = true
                        } else {
                            host.logError(TAG, "Failed to delete: ${file.name}")
                        }
                    }

                    if (!fileDeleted) {
                        val duration = getAudioDurationMs(file)
                        val audioInfo = AudioFileInfo(file, duration, file.nameWithoutExtension, file.parentFile?.absolutePath ?: file.absolutePath)
                        if (isEffectivelyPermanent) { // This case handles files directly in 'permanent' or marked via AppSettings
                           // Add to permanent only if it's truly in the permanent directory, otherwise it's handled below.
                           if (isPermanentParentDir) host.permanentAudioFiles.add(audioInfo)
                           else if (isMarkedPermanent && !isPermanentParentDir) { // Marked but in normal dir
                                host.permanentAudioFiles.add(audioInfo) // Should ideally be moved, but for loading logic add here.
                           } else { // Not in permanent dir and not marked (should be normal)
                                host.normalAudioFiles.add(audioInfo)
                           }
                        } else if (!isPermanentParentDir) { // Explicitly not in permanent dir
                            host.normalAudioFiles.add(audioInfo)
                        }
                        // Files in the 'permanent' subdirectory are handled separately below
                    }
                }
            }
        }

        if (permDir != null && permDir.exists()) {
            permDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".aac") || it.name.endsWith(".m4a")) }?.forEach { file ->
                 // Ensure not already added if AppSettings.isPermanentFile was true and it was in normalDir
                if (!host.permanentAudioFiles.any { it.file.absolutePath == file.absolutePath }) {
                    val duration = getAudioDurationMs(file)
                    host.permanentAudioFiles.add(AudioFileInfo(file, duration, file.nameWithoutExtension, file.parentFile?.absolutePath ?: file.absolutePath))
                }
            }
        }
        
        // Consolidate files marked as permanent via AppSettings that might still be in the normal directory physically
        // This is more of a cleanup/consistency step. The move logic should handle physical relocation.
        val filesToMoveToPermanentList = mutableListOf<AudioFileInfo>()
        host.normalAudioFiles.filter { AppSettings.isPermanentFile(context, it.file.name) && it.file.parentFile?.name != "permanent" }.forEach { afi ->
            if (!host.permanentAudioFiles.any {it.file.absolutePath == afi.file.absolutePath }) {
                 filesToMoveToPermanentList.add(afi)
            }
        }
        host.permanentAudioFiles.addAll(filesToMoveToPermanentList)
        host.normalAudioFiles.removeAll(filesToMoveToPermanentList)


        host.normalAudioFiles.sortByDescending { it.file.lastModified() }
        host.permanentAudioFiles.sortByDescending { it.file.lastModified() }
        
        host.refreshAdapters()

        if (wasPlayingPath != null) {
            val stillExistsInNormal = host.normalAudioFiles.any { it.file.absolutePath == wasPlayingPath }
            val stillExistsInPerm = host.permanentAudioFiles.any { it.file.absolutePath == wasPlayingPath }
            if (!(stillExistsInNormal || stillExistsInPerm)) {
                if (host.currentlyPlayingPath == wasPlayingPath) host.stopPlayback()
            }
        }
        host.logDebug(TAG, "loadRecordedFiles: Finished loading files.")
    }

    fun renameFileLogic(audioFile: AudioFileInfo, newName: String) {
        val parentDir = audioFile.file.parentFile ?: return
        val extension = audioFile.file.extension
        val newFileNameWithExtension = if (extension.isNotEmpty()) "$newName.$extension" else newName
        val newFile = File(parentDir, newFileNameWithExtension)

        if (newFile.exists()) {
            host.showToast("File with this name already exists", Toast.LENGTH_SHORT)
            return
        }

        try {
            if (audioFile.file.absolutePath == host.currentlyPlayingPath) {
                host.stopPlayback()
            }
            val originalName = audioFile.file.name
            val renamed = audioFile.file.renameTo(newFile)
            if (renamed) {
                host.showToast("File renamed", Toast.LENGTH_SHORT)
                if (AppSettings.isPermanentFile(context, originalName)) {
                    AppSettings.removePermanentFile(context, originalName)
                    AppSettings.addPermanentFile(context, newFile.name)
                }
                loadRecordedFiles() 
            } else {
                host.showToast("Failed to rename file", Toast.LENGTH_SHORT)
            }
        } catch (e: Exception) {
            host.showToast("Error renaming file: ${e.message}", Toast.LENGTH_SHORT)
            host.logError(TAG, "Error renaming file", e)
        }
    }

    fun performMoveFileToPermanentLogic(audioFile: AudioFileInfo, destFile: File, overwrite: Boolean) {
        if (audioFile.file.parentFile?.name == "permanent" || AppSettings.isPermanentFile(context, audioFile.file.name)) {
             // If already marked as permanent and in normal dir, just ensure it's in the permanent list
            if (audioFile.file.parentFile?.name != "permanent" && AppSettings.isPermanentFile(context, audioFile.file.name)) {
                 host.logInfo(TAG, "${audioFile.displayName} is marked permanent but in normal dir. Ensuring it's in permanent list.")
                 // No physical move needed if it's just a list update due to AppSettings
            } else {
                host.showToast("'${audioFile.displayName}' is already in permanent storage or directory.", Toast.LENGTH_SHORT)
            }
            loadRecordedFiles() // Refresh to ensure consistency
            return
        }


        if (audioFile.file.absolutePath == host.currentlyPlayingPath) {
            host.stopPlayback()
        }

        val permDir = destFile.parentFile
        if (permDir != null && !permDir.exists()) {
            permDir.mkdirs()
        }

        if (overwrite && destFile.exists()) {
            val oldFileWasPermanent = AppSettings.isPermanentFile(context, destFile.name)
            if (oldFileWasPermanent) AppSettings.removePermanentFile(context, destFile.name)
            destFile.delete()
        }

        val success = audioFile.file.renameTo(destFile)
        if (success) {
            AppSettings.addPermanentFile(context, destFile.name) // Mark the new file name as permanent
            host.showToast("'${audioFile.displayName}' moved to permanent storage.", Toast.LENGTH_SHORT)
            loadRecordedFiles()
        } else {
            host.showToast("Failed to move file.", Toast.LENGTH_LONG)
            host.logError(TAG, "Failed to move file from ${audioFile.file.absolutePath} to ${destFile.absolutePath}")
        }
    }

    fun performDeleteLogic(audioFile: AudioFileInfo) {
        if (audioFile.file.absolutePath == host.currentlyPlayingPath) {
            host.stopPlayback()
        }
        val originalName = audioFile.file.name
        val deleted = audioFile.file.delete()
        if (deleted) {
            AppSettings.removePermanentFile(context, originalName) 
            host.showToast("'${audioFile.displayName}' deleted", Toast.LENGTH_SHORT)
            loadRecordedFiles()
        } else {
            host.showToast("Failed to delete '${audioFile.displayName}'", Toast.LENGTH_SHORT)
            host.logError(TAG, "Failed to delete file: ${audioFile.file.absolutePath}")
        }
    }
}
