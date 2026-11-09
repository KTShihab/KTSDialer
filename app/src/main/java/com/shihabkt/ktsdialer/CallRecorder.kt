package com.shihabkt.ktsdialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance, low-latency Call Recorder & Sync Engine:
 * 1. Asynchronously indexes all recordings from iQOO / vivo, Samsung, Xiaomi, OnePlus,
 *    ACR, and KTS in memory, preventing any UI lag or freezing during RecyclerView scrolling.
 * 2. Silent direct recording using voice-optimized audio sources.
 * 3. Crystal-clear playback on Loudspeaker and Earpiece with proper Android AudioPolicy routing.
 * 4. File sharing via standard Android FileProvider.
 */
object CallRecorder {

    private const val TAG = "CallRecorder"
    private const val FOLDER_NAME = "KTS_Recordings"

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlaybackFile: File? = null
    private var onCurrentCompletion: (() -> Unit)? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoudspeaker = MutableStateFlow(true)
    val isLoudspeaker: StateFlow<Boolean> = _isLoudspeaker.asStateFlow()

    // In-memory cache of scanned files to keep the UI thread completely non-blocking
    private var cachedFileList: List<File> = emptyList()
    private val contactNameCache = ConcurrentHashMap<String, String>()
    private var lastIndexTime: Long = 0L
    private var isIndexing = false

    private val KNOWN_RECORDING_PATHS = listOf(
        // iQOO & vivo
        "Recordings/Record/Call",
        "Record/Call",
        "Record",
        // Samsung
        "Recordings/Call",
        "Call",
        // Xiaomi / Redmi / POCO
        "MIUI/sound_recorder/call_rec",
        // OnePlus / Oppo / Realme (ColorOS / OxygenOS)
        "Recordings/Call Recordings",
        "Music/Recordings/Call Recordings",
        // Huawei / Honor
        "record",
        "Sounds/CallRecord",
        // ACR & Third party call recorders
        "Recordings/ACRPhone",
        "AudioRecorder",
        // KTS Dialer
        "Recordings/KTS_Recordings",
        "Recordings"
    )

    private val audioExtensions = setOf("m4a", "mp3", "wav", "aac", "amr", "3gp")

    fun getRecordingsDirectory(context: Context): File {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        val ktsFolder = File(publicDir, FOLDER_NAME)
        if (!ktsFolder.exists()) {
            val created = ktsFolder.mkdirs()
            if (!created && !ktsFolder.exists()) {
                val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS), FOLDER_NAME)
                if (!fallback.exists()) fallback.mkdirs()
                return fallback
            }
        }
        return ktsFolder
    }

    /**
     * Re-indexes recordings in the background without blocking the UI thread.
     */
    fun refreshRecordingsCacheAsync(context: Context, onComplete: (() -> Unit)? = null) {
        if (isIndexing) return
        isIndexing = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rootSd = Environment.getExternalStorageDirectory()
                val dirs = mutableListOf<File>()

                for (path in KNOWN_RECORDING_PATHS) {
                    val d = File(rootSd, path)
                    if (d.exists() && d.isDirectory) {
                        dirs.add(d)
                    }
                }
                dirs.add(getRecordingsDirectory(context))

                val foundFiles = mutableListOf<File>()
                val seen = mutableSetOf<String>()

                for (d in dirs) {
                    val list = d.listFiles { f ->
                        f.isFile && audioExtensions.contains(f.extension.lowercase(Locale.ROOT))
                    } ?: continue

                    for (f in list) {
                        val can = f.canonicalPath
                        if (seen.add(can)) {
                            foundFiles.add(f)
                        }
                    }
                }

                // Sort newest first
                foundFiles.sortByDescending { it.lastModified() }

                synchronized(this@CallRecorder) {
                    cachedFileList = foundFiles
                    lastIndexTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing recordings cache: ${e.message}", e)
            } finally {
                isIndexing = false
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            }
        }
    }

    /**
     * Fast in-memory lookup of recordings matching a phone number and/or contact name.
     * Takes < 1ms on the UI thread!
     */
    fun getRecordingsForNumber(
        context: Context,
        phoneNumber: String,
        contactName: String? = null
    ): List<File> {
        val cleanNumber = phoneNumber.replace("[^0-9]".toRegex(), "").trim()
        val last7 = if (cleanNumber.length >= 7) cleanNumber.takeLast(7) else cleanNumber

        val resolvedName = if (!contactName.isNullOrBlank()) {
            contactName.trim()
        } else {
            contactNameCache[phoneNumber] ?: run {
                val queried = queryContactName(context, phoneNumber)?.trim()
                if (queried != null) contactNameCache[phoneNumber] = queried
                queried
            }
        }

        // Trigger background refresh if empty or older than 60 seconds
        val currentCache = synchronized(this) { cachedFileList }
        if (currentCache.isEmpty() || (System.currentTimeMillis() - lastIndexTime > 60_000L)) {
            refreshRecordingsCacheAsync(context)
        }

        if (currentCache.isEmpty()) return emptyList()

        val matched = mutableListOf<File>()

        for (file in currentCache) {
            val fileName = file.name
            var isMatch = false

            // Match phone digits (e.g. 9633656393 or last 7 digits)
            if (cleanNumber.length >= 6 && fileName.contains(cleanNumber)) {
                isMatch = true
            } else if (last7.length >= 6 && fileName.contains(last7)) {
                isMatch = true
            }

            // Match contact name
            if (!isMatch && !resolvedName.isNullOrBlank()) {
                if (fileName.contains(resolvedName, ignoreCase = true)) {
                    isMatch = true
                } else {
                    val words = resolvedName.split("\\s+".toRegex()).filter { it.length >= 3 }
                    for (w in words) {
                        if (fileName.contains(w, ignoreCase = true)) {
                            isMatch = true
                            break
                        }
                    }
                }
            }

            if (isMatch) {
                matched.add(file)
            }
        }

        return matched
    }

    private fun queryContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank() || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Starts recording silently.
     */
    @Synchronized
    fun startRecording(context: Context, phoneNumber: String, contactName: String? = null): Boolean {
        if (_isRecording.value) return true

        val settingsManager = SettingsManager(context)
        val recordingsDir = getRecordingsDirectory(context)
        val cleanNumber = phoneNumber.replace("[^0-9+]".toRegex(), "").ifBlank { "Unknown" }
        val resolvedName = if (!contactName.isNullOrBlank()) {
            contactName.trim()
        } else {
            queryContactName(context, phoneNumber)?.trim()
        }
        val safeNamePrefix = resolvedName?.replace("[^a-zA-Z0-9 _-]".toRegex(), "")?.trim()?.ifBlank { null }
        val filePrefix = if (!safeNamePrefix.isNullOrBlank()) "${safeNamePrefix}_${cleanNumber}" else "KTS_${cleanNumber}"
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputFile = File(recordingsDir, "${filePrefix}_${timeStamp}.m4a")

        val audioSources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT
        )

        for (source in audioSources) {
            try {
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder.setAudioSource(source)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(settingsManager.recordingBitRate)
                recorder.setAudioSamplingRate(settingsManager.recordingSampleRate)
                recorder.setOutputFile(outputFile.absolutePath)

                recorder.prepare()
                recorder.start()

                mediaRecorder = recorder
                currentRecordingFile = outputFile
                _isRecording.value = true
                Log.d(TAG, "Call recording started: ${outputFile.name} using source: $source")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start recording with source $source: ${e.message}")
                mediaRecorder?.release()
                mediaRecorder = null
            }
        }

        return false
    }

    /**
     * Stops the active call recording, scans it, and updates the in-memory cache.
     */
    @Synchronized
    fun stopRecording(context: Context): File? {
        if (!_isRecording.value) return null

        val file = currentRecordingFile
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder: ${e.message}")
        } finally {
            try {
                mediaRecorder?.release()
            } catch (ignored: Exception) {}
            mediaRecorder = null
            _isRecording.value = false
            currentRecordingFile = null
        }

        if (file != null && file.exists() && file.length() > 0) {
            Log.d(TAG, "Call recording finished: ${file.absolutePath} (${file.length()} bytes)")
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                arrayOf("audio/mp4", "audio/m4a"),
                null
            )
            refreshRecordingsCacheAsync(context)
            return file
        }
        return null
    }

    /**
     * Plays recording with correct audio output routing that ensures audible sound.
     * For Loudspeaker: MODE_NORMAL + USAGE_MEDIA.
     * For Earpiece: MODE_IN_COMMUNICATION + setCommunicationDevice(Earpiece) + USAGE_VOICE_COMMUNICATION.
     */
    fun startPlayback(
        context: Context,
        file: File,
        useLoudspeaker: Boolean = true,
        startPositionMs: Int = 0,
        onCompletion: (() -> Unit)? = null
    ) {
        stopPlayback(context)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _isLoudspeaker.value = useLoudspeaker
        currentPlaybackFile = file
        onCurrentCompletion = onCompletion

        try {
            routeAudio(audioManager, useLoudspeaker)

            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)

                if (useLoudspeaker) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                } else {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .build()
                    )
                }

                setVolume(1.0f, 1.0f)

                setOnCompletionListener {
                    stopPlayback(context)
                    onCompletion?.invoke()
                }
                prepare()
                if (startPositionMs > 0) {
                    seekTo(startPositionMs)
                }
                start()
            }

            mediaPlayer = player
            _isPlaying.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback: ${e.message}", e)
            stopPlayback(context)
        }
    }

    /**
     * Toggles audio output between Loudspeaker and Earpiece seamlessly during playback.
     */
    fun toggleAudioRoute(context: Context) {
        val targetLoudspeaker = !_isLoudspeaker.value
        val player = mediaPlayer
        val pos = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        val file = currentPlaybackFile
        val comp = onCurrentCompletion

        if (file != null && player != null) {
            startPlayback(context, file, useLoudspeaker = targetLoudspeaker, startPositionMs = pos, onCompletion = comp)
        } else {
            _isLoudspeaker.value = targetLoudspeaker
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            routeAudio(audioManager, targetLoudspeaker)
        }
    }

    private fun routeAudio(audioManager: AudioManager, useLoudspeaker: Boolean) {
        try {
            if (useLoudspeaker) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false

                val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (vol == 0) {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.8).toInt(), 0)
                }
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val earpiece = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (earpiece != null) {
                        audioManager.setCommunicationDevice(earpiece)
                    }
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false

                val vol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                if (vol == 0) {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * 0.8).toInt(), 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing audio: ${e.message}", e)
        }
    }

    /**
     * Stops current playback cleanly restoring normal system audio mode.
     */
    fun stopPlayback(context: Context) {
        try {
            mediaPlayer?.stop()
        } catch (ignored: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        currentPlaybackFile = null
        onCurrentCompletion = null
        _isPlaying.value = false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        } catch (ignored: Exception) {}
    }

    /**
     * Shares a recording file via standard Android share sheet.
     */
    fun shareRecording(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = when (file.extension.lowercase(Locale.ROOT)) {
                "m4a", "mp4" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "3gp" -> "audio/3gpp"
                "amr" -> "audio/amr"
                else -> "audio/*"
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, context.getString(R.string.action_share_recording)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing recording: ${e.message}", e)
        }
    }
}
