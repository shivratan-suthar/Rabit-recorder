# Rabbit Recorder - Android Audio Recording App

## Overview

Rabbit Recorder is an Android application designed for quick and easy audio recording. It allows users to record audio, manage their recordings by categorizing them into "Normal" (temporary) and "Permanent" storage, and perform actions like playback, renaming, and deletion. The app supports background recording via a foreground service and provides customizable settings for recording duration and retention.

## Key Features

*   **One-Tap Recording:** Easily start and stop audio recordings.
*   **Background Recording:** Continue recording even when the app is in the background, with a persistent notification.
*   **Organized File Management:**
    *   Recordings are initially saved as "Normal" and can be moved to "Permanent" storage.
    *   Automatic cleanup of "Normal" recordings based on user-defined retention period.
*   **In-App Playback:** Listen to recordings directly within the app, with playback controls and a seek bar.
*   **File Operations:** Rename, delete, and move recordings between Normal and Permanent storage.
*   **Customizable Settings:**
    *   Set a maximum duration for individual recordings.
    *   Define how long "Normal" recordings are kept before being auto-deleted.
*   **User-Friendly Interface:** Clear display of recordings, playback time, and recording timer.
*   **Permission Handling:** Requests necessary permissions on first launch.

## Project Structure

The project follows a standard Android application structure. Key components include:

*   **`app/src/main/java/com/example/myapplication3/`**
    *   **`MainActivity.kt`**: The main screen of the application, handling UI interactions, displaying recording lists, playback controls, and initiating recording actions. Implements `MainActivityFileManagerHost`.
    *   **`AudioRecordService.kt`**: A foreground service responsible for handling audio recording in the background, managing the recording timer, and saving files.
    *   **`AudioFileInfo.kt`**: A data class representing a recorded audio file, holding information like file path, display name, and duration.
    *   **`AudioFileAdapter.kt`**: An `ArrayAdapter` to display `AudioFileInfo` objects in `ListView`s for normal and permanent recordings.
    *   **`AudioFileManager.kt`**: Handles all file system operations related to audio recordings, including loading, saving, renaming, deleting, and moving files. Also responsible for cleaning up old recordings.
    *   **`AppSettings.kt`**: Manages application settings and preferences using `SharedPreferences`.
    *   **`MainActivityFileManagerHost.kt`**: An interface defining callbacks and methods for `MainActivity` to interact with `AudioFileManager` and respond to file operations.
    *   **`TimeUtils.kt`**: (Likely contains utility functions for time formatting or calculations, if not fully integrated into other classes).
    *   **`SettingsActivity.kt`**: (Presumably, an activity for more detailed settings, if `R.id.action_settings` in `MainActivity` leads to it).

*   **`app/src/main/res/`**
    *   **`layout/activity_main.xml`**: Defines the UI for the main screen.
    *   **`layout/dialog_recording_settings.xml`**: Defines the UI for the recording settings dialog.
    *   **`layout/list_item_audio_file.xml`**: Defines the layout for each item in the recordings list.
    *   **`drawable/`**: Contains various icons used in the app (e.g., `ic_play_arrow.xml`, `ic_pause.xml`, `ic_settings.xml`, `ic_more_vert.xml`).
    *   **`menu/main_menu.xml`**: Defines the options menu for `MainActivity`.
    *   **`menu/menu_audio_item.xml`**: Defines the context menu for individual audio file items.
    *   **`values/colors.xml`**: Defines color resources.
    *   **`values/strings.xml`**: Defines string resources.

*   **`app/build.gradle.kts`**: The Gradle build script for the app module, specifying dependencies and build configurations.
*   **`build.gradle.kts` (Project Level)**: The top-level Gradle build script.
*   **`AndroidManifest.xml`**: Declares application components, permissions (like `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` - pre-Android 10), and other essential app metadata.

## How to Build and Run

1.  Clone the repository:
    ```bash
    git clone https://github.com/shivratan-suthar/Rabit-recorder.git
    ```
2.  Open the project in Android Studio.
3.  Let Android Studio sync the Gradle project.
4.  Connect an Android device or start an emulator.
5.  Run the app from Android Studio (Shift + F10 or Run > Run 'app').

## Permissions Required

The application requires the following permissions:

*   **`android.permission.RECORD_AUDIO`**: To record audio.
*   **`android.permission.FOREGROUND_SERVICE`**: To run the recording service in the foreground, ensuring it's not killed by the system during background recording.
*   **(Potentially `android.permission.POST_NOTIFICATIONS` for Android 13+ if foreground service notifications are used).**
*   **Storage Permissions (Implicit or Explicit pre-Android 10):** While the app uses `getExternalFilesDir(Environment.DIRECTORY_MUSIC)`, which doesn't require explicit runtime permissions for app-specific storage on modern Android versions, older versions might behave differently or if `MANAGE_EXTERNAL_STORAGE` were ever considered (not currently indicated).

## Author

*   **Shiv** 

---

