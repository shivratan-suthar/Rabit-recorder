package com.example.myapplication3

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), MainActivityFileManagerHost {

    private companion object {
        private const val TAG_ACTIVITY = "MainActivity"
        // SharedPreferences keys for recording settings
        private const val PREF_MAX_REC_MINUTES_FLOAT = "max_rec_minutes_float"
        private const val PREF_KEEP_REC_HOURS_FLOAT = "keep_rec_hours_float"
        private const val PREF_FIRST_LAUNCH_PERMISSION_REQUESTED = "first_launch_permission_requested"
    }

    private lateinit var startButton: Button
    private lateinit var timerTextView: TextView
    private lateinit var editSettingsButton: ImageButton
    private lateinit var infoButton: ImageButton // ADDED: Info button declaration

    private var isRecording: Boolean = false

    private lateinit var normalFilesListView: ListView
    private lateinit var permanentFilesListView: ListView

    override val normalAudioFiles = mutableListOf<AudioFileInfo>()
    override val permanentAudioFiles = mutableListOf<AudioFileInfo>()

    override lateinit var normalAdapter: AudioFileAdapter
    override lateinit var permanentAdapter: AudioFileAdapter

    private var mediaPlayer: MediaPlayer? = null
    override var currentlyPlayingPath: String? = null
    var isMediaPlayerPaused: Boolean = false

    private var currentPlayingItemHolder: AudioFileAdapter.ViewHolder? = null
    private val seekBarUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var seekBarUpdateRunnable: Runnable

    override lateinit var appSharedPreferences: SharedPreferences

    private lateinit var audioFileManager: AudioFileManager

    // For periodic refresh of file list
    private val refreshFilesHandler = Handler(Looper.getMainLooper())
    private lateinit var refreshFilesRunnable: Runnable
    private val REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    private var isActivityInForeground = false


    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        permissions.forEach { (perm, granted) ->
            Log.d(TAG_ACTIVITY, "Permission: $perm, Granted: $granted")
        }
        if (recordAudioGranted) {
            Log.d(TAG_ACTIVITY, "RECORD_AUDIO permission granted. Starting recording service.")
            // No need to call startRecordingService() here if checkPermissionsAndStart was called for a button click
            // If called from onCreate, startRecordingService() would have been part of checkPermissionsAndStart's logic
        } else {
            Log.w(TAG_ACTIVITY, "RECORD_AUDIO permission denied. Cannot start recording.")
            showToast("Audio recording permission is required to record.", Toast.LENGTH_LONG)
            isRecording = false // Ensure UI consistency
            startButton.text = "Start Recording"
            timerTextView.text = "Recording Time: 00:00"
        }
    }

    private val timerUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioRecordService.ACTION_TIMER_UPDATE) {
                val elapsedMs = intent.getLongExtra(AudioRecordService.EXTRA_ELAPSED_MS, 0L)
                runOnUiThread {
                    if (isRecording && AudioRecordService.isServiceRunning) {
                        timerTextView.text = "Recording Time: ${formatElapsedTime(elapsedMs)}"
                    } else if (!AudioRecordService.isServiceRunning && isRecording) {
                        Log.w(TAG_ACTIVITY, "Timer update received but service not running or UI desynced. Resetting UI.")
                        isRecording = false
                        startButton.text = "Start Recording"
                        timerTextView.text = "Recording Time: 00:00"
                    }
                }
            }
        }
    }

    private val recordingSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioRecordService.ACTION_RECORDING_SAVED) {
                val filePath = intent.getStringExtra(AudioRecordService.EXTRA_FILE_PATH)
                Log.d(TAG_ACTIVITY, "Received ACTION_RECORDING_SAVED for: $filePath")
                if (filePath != null) {
                    val newFile = File(filePath)
                    if (newFile.exists()) {
                        showToast("Recording saved: ${newFile.nameWithoutExtension}", Toast.LENGTH_SHORT)
                        // Add a delay before processing to allow file system to finalize metadata
                        refreshFilesHandler.postDelayed({
                            audioFileManager.addOrUpdateSingleRecording(filePath)
                            Log.d(TAG_ACTIVITY, "Delayed addOrUpdateSingleRecording executed for: $filePath")
                        }, 500L) // 500ms delay

                        if (!AudioRecordService.isServiceRunning && isRecording) {
                            Log.d(TAG_ACTIVITY, "Recording service stopped itself after saving. Updating UI.")
                            isRecording = false
                            startButton.text = "Start Recording"
                            timerTextView.text = "Recording Time: 00:00"
                        } else if (AudioRecordService.isServiceRunning && !isRecording) {
                            Log.w(TAG_ACTIVITY, "Service is running but UI thinks it\'s not. Re-syncing for stop.")
                            // Consider if this stop logic should also be delayed or re-evaluated after file load
                            stopRecordingServiceUiUpdate()
                        }
                    } else {
                        Log.e(TAG_ACTIVITY, "Saved recording file not found at: $filePath")
                        showToast("Error: Saved recording file not found", Toast.LENGTH_LONG)
                    }
                } else {
                    Log.e(TAG_ACTIVITY, "File path is null in recordingSavedReceiver intent")
                    showToast("Error: File path not provided for saved recording", Toast.LENGTH_LONG)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG_ACTIVITY, "onCreate called.")

        appSharedPreferences = AppSettings.getSharedPreferences(this)
        audioFileManager = AudioFileManager(applicationContext, this)

        startButton = findViewById(R.id.btnStart)
        timerTextView = findViewById(R.id.tvTimer)
        editSettingsButton = findViewById(R.id.btnEditSettings)
        infoButton = findViewById(R.id.btnInfo) // Initialize info button

        normalFilesListView = findViewById(R.id.lvNormalFiles)
        permanentFilesListView = findViewById(R.id.lvPermanentFiles)

        // Find the empty TextViews from your layout
        val emptyNormalTextView = findViewById<TextView>(R.id.tvEmptyNormalFiles)
        val emptyPermanentTextView = findViewById<TextView>(R.id.tvEmptyPermanentFiles)

        normalAdapter = AudioFileAdapter(normalAudioFiles, this)
        permanentAdapter = AudioFileAdapter(permanentAudioFiles, this)

        normalFilesListView.adapter = normalAdapter
        permanentFilesListView.adapter = permanentAdapter

        // Set the empty views
        normalFilesListView.emptyView = emptyNormalTextView
        permanentFilesListView.emptyView = emptyPermanentTextView

        startButton.setOnClickListener {
            Log.d(TAG_ACTIVITY, "Start button clicked. isRecording: $isRecording")
            if (isRecording) {
                stopRecordingServiceUiUpdate()
            } else {
                // When start button is clicked, it implies user interaction, so we check permissions and then start.
                checkPermissionsAndStart(true)
            }
        }

        editSettingsButton.setOnClickListener {
            showRecordingSettingsDialog()
        }

        infoButton.setOnClickListener {
            Log.d(TAG_ACTIVITY, "infoButton clicked!")
            showAppInfoDialog()
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(timerUpdateReceiver, IntentFilter(AudioRecordService.ACTION_TIMER_UPDATE))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(recordingSavedReceiver, IntentFilter(AudioRecordService.ACTION_RECORDING_SAVED))

        seekBarUpdateRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying && !isMediaPlayerPaused) {
                        try {
                            val currentPosition = mp.currentPosition
                            val totalDuration = mp.duration
                            currentPlayingItemHolder?.seekBarAudio?.progress = currentPosition
                            currentPlayingItemHolder?.tvPlaybackTime?.text =
                                "${formatElapsedTime(currentPosition.toLong())} / ${formatElapsedTime(totalDuration.toLong())}"
                            seekBarUpdateHandler.postDelayed(this, 500)
                        } catch (e: IllegalStateException) {
                            Log.e(TAG_ACTIVITY, "MediaPlayer in invalid state in runnable", e)
                            stopPlayback()
                        }
                    }
                }
            }
        }

        refreshFilesRunnable = Runnable {
            Log.d(TAG_ACTIVITY, "Periodic refresh: Calling loadRecordedFiles.")
            audioFileManager.loadRecordedFiles()
            if (isActivityInForeground) {
                 refreshFilesHandler.postDelayed(refreshFilesRunnable, REFRESH_INTERVAL_MS)
            }
        }
        audioFileManager.loadRecordedFiles() // Initial load

        // Request permissions on first launch if not already requested
        val permissionRequested = appSharedPreferences.getBoolean(PREF_FIRST_LAUNCH_PERMISSION_REQUESTED, false)
        if (!permissionRequested) {
            Log.d(TAG_ACTIVITY, "First launch: Requesting permissions.")
            // Call checkPermissionsAndStart, but don't automatically start recording from onCreate
            checkPermissionsAndStart(false)
            with(appSharedPreferences.edit()) {
                putBoolean(PREF_FIRST_LAUNCH_PERMISSION_REQUESTED, true)
                apply()
            }
        }
    }

    private fun showRecordingSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_recording_settings, null)
        val etMaxRecLength = dialogView.findViewById<EditText>(R.id.etMaxRecordingLength)
        val etKeepRecHours = dialogView.findViewById<EditText>(R.id.etKeepRecordingsHours)

        val currentMaxRecMinutes = appSharedPreferences.getFloat(PREF_MAX_REC_MINUTES_FLOAT, 10.0f)
        val currentKeepRecHours = appSharedPreferences.getFloat(PREF_KEEP_REC_HOURS_FLOAT, 1.0f)

        if (currentMaxRecMinutes > 0.0f) etMaxRecLength.setText(currentMaxRecMinutes.toString())
        if (currentKeepRecHours > 0.0f) etKeepRecHours.setText(currentKeepRecHours.toString())

        AlertDialog.Builder(this)
            .setTitle("Recording Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val maxRecMinutesStr = etMaxRecLength.text.toString()
                val keepRecHoursStr = etKeepRecHours.text.toString()

                val maxRecMinutes = maxRecMinutesStr.toFloatOrNull() ?: 0.0f
                val keepRecHours = keepRecHoursStr.toFloatOrNull() ?: 0.0f

                with(appSharedPreferences.edit()) {
                    putFloat(PREF_MAX_REC_MINUTES_FLOAT, maxRecMinutes)
                    putFloat(PREF_KEEP_REC_HOURS_FLOAT, keepRecHours)
                    apply()
                }
                showToast("Settings saved", Toast.LENGTH_SHORT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppInfoDialog() {
        Log.d(TAG_ACTIVITY, "showAppInfoDialog called.") // Log method entry
        val appDescription = """
**Rabbit Recorder**

**Overview**
Rabbit Recorder is a fast, reliable, and easy-to-use audio recording app, designed for capturing anything from quick voice notes to important interviews. With intuitive controls and smart file management, it ensures your recordings are always organized and accessible.

**Key Features**
- **One-Tap Recording** – Start or stop recording instantly.
- **Organized Library** – Recordings are automatically sorted into **Normal** (auto-deleted after your chosen time) or **Permanent** categories.
- **Smart File Management** – Play, rename, move to Permanent, or delete recordings directly from the app.
- **Customizable Settings** –
  - Set a maximum recording duration per file.
  - Choose how long Normal recordings are kept before auto-deletion.
- **Background Recording** – Record seamlessly while using other apps, supported by a foreground service notification.

**How to Use**
1. **Record** – Tap the Start/Stop button to begin or end a recording.
2. **Browse** – Access your recordings in the Normal or Permanent lists.
3. **Manage** – Tap a file to play it, or long-press (or use the 'More' menu) for rename, move, or delete options.
4. **Customize** – Open Settings (gear icon) to adjust recording limits and retention preferences.
5. **Info** – Tap the Info icon for detailed guidance (you’re here now!).

---
Created by **Shiv** with passion.
📧 Email: mintshiv@gmail.com
""".trimIndent()

        AlertDialog.Builder(this)
            .setTitle("About Rabbit Recorder") // Updated title
            .setMessage(appDescription)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_info -> {
                showAppInfoDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityInForeground = true
        Log.d(TAG_ACTIVITY, "onResume called. Service running: ${AudioRecordService.isServiceRunning}, UI isRecording: $isRecording")
        if (AudioRecordService.isServiceRunning) {
            if (!isRecording) {
                Log.w(TAG_ACTIVITY, "onResume: Service is running, but UI thought it was stopped. Syncing UI to 'Recording'.")
                isRecording = true
            }
            startButton.text = "Stop Recording"
        } else {
            if (isRecording) {
                Log.w(TAG_ACTIVITY, "onResume: Service is NOT running, but UI thought it was. Syncing UI to 'Not Recording'.")
                isRecording = false
            }
            startButton.text = "Start Recording"
            timerTextView.text = "Recording Time: 00:00"
        }
        audioFileManager.loadRecordedFiles()

        refreshFilesHandler.removeCallbacks(refreshFilesRunnable)
        refreshFilesHandler.postDelayed(refreshFilesRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG_ACTIVITY, "onResume: Scheduled periodic file list refresh.")
    }

     override fun onPause() {
        super.onPause()
        isActivityInForeground = false
        refreshFilesHandler.removeCallbacks(refreshFilesRunnable)
        Log.d(TAG_ACTIVITY, "onPause: Stopped periodic file list refresh.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityInForeground = false
        refreshFilesHandler.removeCallbacks(refreshFilesRunnable)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(timerUpdateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(recordingSavedReceiver)
        seekBarUpdateHandler.removeCallbacks(seekBarUpdateRunnable)
        stopPlayback()
    }

    private fun checkPermissionsAndStart(shouldStartRecording: Boolean = false) {
        Log.d(TAG_ACTIVITY, "checkPermissionsAndStart called. shouldStartRecording: $shouldStartRecording")
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        // Add other permission checks here if needed in the future

        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG_ACTIVITY, "All necessary permissions already granted.")
            if (shouldStartRecording) {
                 Log.d(TAG_ACTIVITY, "Proceeding to start recording service.")
                startRecordingService()
            } else {
                Log.d(TAG_ACTIVITY, "Permissions granted, but not starting recording automatically (e.g. on first launch check).")
            }
        } else {
            Log.d(TAG_ACTIVITY, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            // The ActivityResultLauncher callback will handle the outcome.
            // If shouldStartRecording is true, the user expects recording to start if they grant permission.
            // We can pass this intent to the launcher's callback or handle it there based on the grant result.
            // For now, the existing launcher callback handles starting the service if RECORD_AUDIO is granted.
            // We need to ensure it only starts if shouldStartRecording was true.
            // However, the current requestPermissionsLauncher is generic.
            // Let's adjust requestPermissionsLauncher to respect shouldStartRecording conceptually.
            // The current requestPermissionsLauncher already calls startRecordingService if permission is granted.
            // This is fine for the "start button" case. For "first launch", we don't want to auto-start.
            // So, the `shouldStartRecording` parameter is primarily for the case where permissions are ALREADY granted.

            // The `requestPermissionsLauncher` will call `startRecordingService` if RECORD_AUDIO is granted.
            // This is okay if `shouldStartRecording` was true.
            // If `shouldStartRecording` was false (first launch check), and permissions are granted via dialog,
            // `startRecordingService` will still be called by the launcher. This is an acceptable side-effect for simplicity,
            // as the main goal is to *request* permissions on first launch.
            // If strict "don't start on first grant" is needed, the launcher callback would need more context.
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startRecordingService() {
        if (AudioRecordService.isServiceRunning) {
            Log.w(TAG_ACTIVITY, "startRecordingService called, but service is already running. Updating UI just in case.")
            if (!isRecording) {
                isRecording = true
                startButton.text = "Stop Recording"
            }
            return
        }
        Log.d(TAG_ACTIVITY, "Attempting to start AudioRecordService.")
        val serviceIntent = Intent(this, AudioRecordService::class.java)

        val maxRecMinutesFloat = appSharedPreferences.getFloat(PREF_MAX_REC_MINUTES_FLOAT, 0.0f)
        if (maxRecMinutesFloat > 0.0f) {
            val maxRecDurationMs = (maxRecMinutesFloat * 60 * 1000).toLong()
            serviceIntent.putExtra(AudioRecordService.EXTRA_MAX_REC_DURATION_MS, maxRecDurationMs)
        }

        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            isRecording = true
            startButton.text = "Stop Recording"
            timerTextView.text = "Recording Time: 00:00"
            showToast("Recording started...", Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            Log.e(TAG_ACTIVITY, "Failed to start foreground service", e)
            showToast("Failed to start recording service: ${e.message}", Toast.LENGTH_LONG)
            isRecording = false
            startButton.text = "Start Recording"
        }
    }

    private fun stopRecordingServiceUiUpdate() {
        Log.d(TAG_ACTIVITY, "stopRecordingServiceUiUpdate called.")
        if (!AudioRecordService.isServiceRunning && !isRecording) {
            Log.w(TAG_ACTIVITY, "Stop called, but service not running and UI already shows not recording. No action.")
            isRecording = false
            startButton.text = "Start Recording"
            timerTextView.text = "Recording Time: 00:00"
            return
        }
        val serviceIntent = Intent(this, AudioRecordService::class.java)
        try {
            stopService(serviceIntent)
            Log.d(TAG_ACTIVITY, "stopService called on AudioRecordService.")
        } catch (e: Exception) {
            Log.e(TAG_ACTIVITY, "Error stopping service: ", e)
        }
        isRecording = false
        startButton.text = "Start Recording"
        timerTextView.text = "Recording Time: 00:00"
        showToast("Recording stopped.", Toast.LENGTH_SHORT)
    }

    fun playAudio(audioFile: AudioFileInfo, newHolder: AudioFileAdapter.ViewHolder) {
        val playButton = newHolder.btnPlay
        if (currentPlayingItemHolder != null && currentPlayingItemHolder != newHolder) {
            currentPlayingItemHolder?.btnPlay?.setImageResource(R.drawable.ic_play_arrow)
            currentPlayingItemHolder?.seekBarAudio?.visibility = View.GONE
            currentPlayingItemHolder?.seekBarAudio?.progress = 0
            currentPlayingItemHolder?.tvPlaybackTime?.visibility = View.GONE // Hide old item's time
        }
        seekBarUpdateHandler.removeCallbacks(seekBarUpdateRunnable)

        if (currentlyPlayingPath == audioFile.file.absolutePath) {
            mediaPlayer?.let {
                if (it.isPlaying && !isMediaPlayerPaused) {
                    it.pause()
                    isMediaPlayerPaused = true
                    playButton.setImageResource(R.drawable.ic_play_arrow)
                    seekBarUpdateHandler.removeCallbacks(seekBarUpdateRunnable) // Stop time updates
                } else {
                    it.start()
                    isMediaPlayerPaused = false
                    playButton.setImageResource(R.drawable.ic_pause)
                    newHolder.seekBarAudio.max = it.duration
                    newHolder.seekBarAudio.visibility = View.VISIBLE
                    newHolder.tvPlaybackTime.visibility = View.VISIBLE // Show time on resume
                    seekBarUpdateHandler.post(seekBarUpdateRunnable) // Start time updates
                }
            }
        } else {
            stopPlayback() // Stops previous, also handles its UI (including tvPlaybackTime)
            currentlyPlayingPath = audioFile.file.absolutePath
            isMediaPlayerPaused = false
            currentPlayingItemHolder = newHolder // Set new holder
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(audioFile.file.absolutePath)
                    setOnPreparedListener { mp ->
                        mp.start()
                        playButton.setImageResource(R.drawable.ic_pause)
                        newHolder.seekBarAudio.max = mp.duration
                        newHolder.seekBarAudio.visibility = View.VISIBLE
                        newHolder.tvPlaybackTime.visibility = View.VISIBLE // Show time on new play
                        newHolder.seekBarAudio.progress = 0
                        // Set initial time text
                        newHolder.tvPlaybackTime.text = "${formatElapsedTime(0L)} / ${formatElapsedTime(mp.duration.toLong())}"
                        seekBarUpdateHandler.post(seekBarUpdateRunnable)
                    }
                    setOnCompletionListener {
                        showToast("Playback completed", Toast.LENGTH_SHORT)
                        stopPlayback() // This will also hide tvPlaybackTime via currentPlayingItemHolder
                    }
                    setOnErrorListener { _, _, _ ->
                        showToast("Playback error", Toast.LENGTH_SHORT)
                        stopPlayback()
                        true
                    }
                    prepareAsync()
                } catch (e: IOException) {
                    Log.e(TAG_ACTIVITY, "MediaPlayer prepare failed", e)
                    showToast("Playback failed: ${e.message}", Toast.LENGTH_SHORT)
                    stopPlayback()
                }
            }
        }
        refreshAdapters() // This will also help sync UI states via adapter's getView
    }

    override fun stopPlayback() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG_ACTIVITY, "Error stopping/releasing MediaPlayer: ", e)
            }
        }
        mediaPlayer = null
        seekBarUpdateHandler.removeCallbacks(seekBarUpdateRunnable)
        currentPlayingItemHolder?.let { holder ->
            holder.btnPlay.setImageResource(R.drawable.ic_play_arrow)
            holder.seekBarAudio.progress = 0
            holder.seekBarAudio.visibility = View.GONE
            holder.tvPlaybackTime.visibility = View.GONE // Hide playback time
            // Reset text to default, e.g., "00:00 / 00:00" or based on actual duration if available
            // The adapter will likely refresh this on next getView if item is still visible
            val totalDurationMs = holder.seekBarAudio.max // Max should be total duration
            holder.tvPlaybackTime.text = "${formatElapsedTime(0L)} / ${formatElapsedTime(totalDurationMs.toLong())}"
        }
        currentlyPlayingPath = null
        isMediaPlayerPaused = false
        currentPlayingItemHolder = null
        refreshAdapters()
    }

    fun getCurrentMediaPlayerPosition(): Int {
        return mediaPlayer?.currentPosition ?: 0
    }

    fun seekMediaPlayerTo(position: Int) {
        mediaPlayer?.let {
            if (it.isPlaying || isMediaPlayerPaused) {
                 try {
                    it.seekTo(position)
                    // Update time text immediately after seek by user
                    currentPlayingItemHolder?.tvPlaybackTime?.text =
                        "${formatElapsedTime(position.toLong())} / ${formatElapsedTime(it.duration.toLong())}"
                 } catch (e: IllegalStateException) {
                    Log.e(TAG_ACTIVITY, "MediaPlayer seek failed", e)
                 }
            }
        }
    }

    fun formatElapsedTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    fun showRenameDialog(audioFile: AudioFileInfo) {
        val input = EditText(this)
        input.setText(audioFile.file.nameWithoutExtension)
        AlertDialog.Builder(this)
            .setTitle("Rename Recording")
            .setMessage("Enter new name for the recording (without extension):")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    showToast("Name cannot be empty", Toast.LENGTH_SHORT)
                    return@setPositiveButton
                }
                audioFileManager.renameFileLogic(audioFile, newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showMoveToPermanentDialog(audioFile: AudioFileInfo) {
        AlertDialog.Builder(this)
            .setTitle("Move to Permanent")
            .setMessage("Move this recording to permanent storage? It will be saved and not deleted automatically.")
            .setPositiveButton("Move") { _, _ ->
                val normalDir = getExternalMusicDir() ?: return@setPositiveButton
                val permDir = File(normalDir, "permanent")
                if (!permDir.exists()) permDir.mkdirs()
                val destFile = File(permDir, audioFile.file.name)

                if (AppSettings.isPermanentFile(this, audioFile.file.name) || audioFile.file.parentFile?.name == "permanent") {
                    showToast("'${audioFile.displayName}' is already in permanent storage.", Toast.LENGTH_SHORT)
                    audioFileManager.loadRecordedFiles()
                    return@setPositiveButton
                }

                if (destFile.exists()) {
                    AlertDialog.Builder(this)
                        .setTitle("File Exists")
                        .setMessage("A file named '${audioFile.file.name}' already exists in permanent storage. Overwrite?")
                        .setPositiveButton("Overwrite") { _, _ ->
                            audioFileManager.performMoveFileToPermanentLogic(audioFile, destFile, true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    audioFileManager.performMoveFileToPermanentLogic(audioFile, destFile, false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun deleteFile(audioFile: AudioFileInfo) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Are you sure you want to delete '${audioFile.displayName}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                audioFileManager.performDeleteLogic(audioFile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun showToast(message: String, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }
    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logError(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
    override fun getExternalMusicDir(): File? {
        return getExternalFilesDir(Environment.DIRECTORY_MUSIC)
    }
    override fun refreshAdapters(){
        if(::normalAdapter.isInitialized) normalAdapter.notifyDataSetChanged()
        if(::permanentAdapter.isInitialized) permanentAdapter.notifyDataSetChanged()
    }
}
