package com.example.myapplication3

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler // Added import
import android.os.IBinder
import android.os.Looper // Added import
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.timer

class AudioRecordService : Service() {

    companion object {
        const val ACTION_TIMER_UPDATE = "com.example.myapplication3.action.TIMER_UPDATE"
        const val EXTRA_ELAPSED_MS = "com.example.myapplication3.extra.ELAPSED_MS"
        const val ACTION_RECORDING_SAVED = "com.example.myapplication3.ACTION_RECORDING_SAVED"
        const val EXTRA_FILE_PATH = "com.example.myapplication3.extra.FILE_PATH"
        const val EXTRA_MAX_REC_DURATION_MS = "com.example.myapplication3.EXTRA_MAX_REC_DURATION_MS" // Added

        @Volatile
        var isServiceRunning = false
            private set
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: String? = null
    private var timerTask: Timer? = null
    private var recordingStartTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var maxRecDurationMs: Long = 0L // Added

    private val TAG = "AudioRecordService"
    private val NOTIFICATION_CHANNEL_ID = "AudioRecordServiceChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service created.")
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApplication3::AudioRecordWakeLock")
        try {
            wakeLock?.acquire(30 * 60 * 1000L /* 30 minutes timeout */)
            Log.d(TAG, "WakeLock acquired.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received.")
        maxRecDurationMs = intent?.getLongExtra(EXTRA_MAX_REC_DURATION_MS, 0L) ?: 0L
        if (maxRecDurationMs > 0) {
            Log.i(TAG, "Max recording duration set to: $maxRecDurationMs ms")
        }

        if (startForegroundServiceWithNotification()) {
            startRecording()
        } else {
            Log.w(TAG, "Foreground service did not start successfully. Service should be stopping or already stopped.")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.d(TAG, "onDestroy: Service destroying...")
        stopRecordingInternal(notifyChange = true, stopTimer = true)
        timerTask?.cancel()
        timerTask = null
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released.")
            }
        }
        Log.d(TAG, "Service fully destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceWithNotification(): Boolean {
        Log.d(TAG, "Attempting to start foreground service with notification.")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Audio Recorder Service",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Channel for Audio Recording Service"
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created/updated.")
            }

            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, pendingIntentFlags
            )

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Audio Recorder") // Note: App name is now "Rabbit recorder", consider updating this.
                .setContentText("Recording in progress...")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this icon exists and is suitable
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            isServiceRunning = true
            Log.i(TAG, "startForeground successful. isServiceRunning set to true.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Error in startForegroundServiceWithNotification", e)
            isServiceRunning = false
            stopSelf()
            Log.w(TAG, "Called stopSelf() due to error in startForegroundServiceWithNotification.")
            return false
        }
    }

    private fun startRecording() {
        if (!isServiceRunning) {
            Log.w(TAG, "startRecording called but service is not marked as running (foreground likely failed). Aborting.")
            stopSelf()
            return
        }

        if (mediaRecorder != null) {
            Log.w(TAG, "startRecording called but mediaRecorder is not null. Stopping previous one.")
            stopRecordingInternal(notifyChange = false, stopTimer = true)
        }

        outputFile = getOutputFilePath()
        if (outputFile == null) {
            Log.e(TAG, "Failed to get output file path. Cannot start recording.")
            stopSelf()
            return
        }

        try {
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile)
                prepare()
                start()
            }
            Log.i(TAG, "Recording started: $outputFile")
            recordingStartTime = System.currentTimeMillis()
            startTimerBroadcast()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            outputFile?.let {
                try {
                    File(it).delete()
                    Log.d(TAG, "Deleted potentially empty/corrupt file: $it")
                } catch (fe: Exception) {
                    Log.e(TAG, "Error deleting file after recording failure: $it", fe)
                }
            }
            stopSelf()
        }
    }

    private fun stopRecordingInternal(notifyChange: Boolean, stopTimer: Boolean) {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
                Log.i(TAG, "MediaRecorder stopped, reset, and released.")
            }
            if (notifyChange && outputFile != null) {
                notifyFileChanged()
            } else if (notifyChange && outputFile == null) {
                Log.w(TAG, "stopRecordingInternal called with notifyChange=true, but outputFile is null.")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "IllegalStateException during stopRecordingInternal.", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "RuntimeException during stopRecordingInternal.", e)
        } finally {
            mediaRecorder = null
        }

        if (stopTimer) {
            stopTimerBroadcast()
        }
    }

    private fun handleMaxDurationReached() {
        Log.i(TAG, "Max recording duration reached. Stopping current recording and restarting.")
        stopRecordingInternal(notifyChange = true, stopTimer = false) 

        if (isServiceRunning) {
            startRecording()
        } else {
            Log.i(TAG, "Service is no longer running. Not restarting recording after max duration.")
            stopTimerBroadcast() 
            stopSelf() 
        }
    }

    private fun startTimerBroadcast() {
        stopTimerBroadcast() 
        timerTask = timer(period = 1000) {
            if (isServiceRunning && recordingStartTime > 0 && mediaRecorder != null) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val intent = Intent(ACTION_TIMER_UPDATE)
                intent.putExtra(EXTRA_ELAPSED_MS, elapsed)
                LocalBroadcastManager.getInstance(this@AudioRecordService).sendBroadcast(intent)

                if (maxRecDurationMs > 0 && elapsed >= maxRecDurationMs) {
                    Handler(Looper.getMainLooper()).post {
                        if(isServiceRunning) { 
                           handleMaxDurationReached()
                        }
                    }
                }
            } else if (!isServiceRunning || mediaRecorder == null) {
                stopTimerBroadcast()
            }
        }
    }

    private fun stopTimerBroadcast() {
        timerTask?.cancel()
        timerTask = null
        if (!isServiceRunning || (timerTask == null && mediaRecorder == null)) { 
            val intent = Intent(ACTION_TIMER_UPDATE)
            intent.putExtra(EXTRA_ELAPSED_MS, 0L)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(TAG, "Timer stopped and final 0L update sent.")
        } else {
             Log.d(TAG, "Timer stopped, but not sending 0L update (likely restarting).")
        }
    }

    private fun notifyFileChanged() {
        if (outputFile == null) {
            Log.w(TAG, "notifyFileChanged called but outputFile is null.")
            return
        }
        val intent = Intent(ACTION_RECORDING_SAVED)
        intent.putExtra(EXTRA_FILE_PATH, outputFile)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Sent ACTION_RECORDING_SAVED broadcast with file: $outputFile")
        outputFile = null 
    }

    private fun getOutputFilePath(): String? {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = sdf.format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (dir == null) {
            Log.e(TAG, "External storage (Music directory) not available or accessible.")
            return null
        }
        if (!dir.exists()) {
            Log.d(TAG, "Music directory does not exist, attempting to create: ${dir.absolutePath}")
            if (!dir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: ${dir.absolutePath}")
                return null
            }
            Log.d(TAG, "Successfully created directory: ${dir.absolutePath}")
        }
        // MODIFIED: Removed "recording_" prefix
        return "${dir.absolutePath}/$timestamp.mp4"
    }
}
