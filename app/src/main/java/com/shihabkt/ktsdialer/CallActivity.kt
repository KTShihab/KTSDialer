package com.shihabkt.ktsdialer

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shihabkt.ktsdialer.databinding.ActivityCallBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Activity presenting the accessible call interface for incoming, outgoing, and ongoing calls.
 * Built specifically for TalkBack/Jieshuo accessibility without swipe gestures.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var settingsManager: SettingsManager
    private var callTimerJob: Job? = null

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val number = CallManager.currentCall.value?.details?.handle?.schemeSpecificPart.orEmpty()
            startRecordingInternal(number)
        } else {
            Toast.makeText(this, "Microphone permission is required to record calls", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenFlags()

        settingsManager = SettingsManager(this)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        observeCallUpdates()
        observeAudioUpdates()
        observeRecordingUpdates()
    }

    private fun configureLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager?.requestDismissKeyguard(this, null)
        }
    }

    private fun setupClickListeners() {
        // Answer call
        binding.btnAnswer.setOnClickListener {
            CallManager.answer()
        }

        // Decline call
        binding.btnDecline.setOnClickListener {
            CallManager.decline()
        }

        // End active / outgoing call
        binding.btnEndCall.setOnClickListener {
            CallManager.disconnect()
        }

        // Toggle speaker
        binding.btnSpeaker.setOnClickListener {
            CallManager.toggleSpeaker()
        }

        // Toggle mute
        binding.btnMute.setOnClickListener {
            CallManager.toggleMute()
        }

        // Toggle call recording
        binding.btnRecord.setOnClickListener {
            val number = CallManager.currentCall.value?.details?.handle?.schemeSpecificPart.orEmpty()
            if (CallRecorder.isRecording.value) {
                CallRecorder.stopRecording(this)
                Toast.makeText(this, getString(R.string.recording_stopped), Toast.LENGTH_SHORT).show()
                binding.root.announceForAccessibility(getString(R.string.recording_stopped))
            } else {
                startRecordingInternal(number)
            }
        }
    }

    private fun startRecordingInternal(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val callerName = binding.callerNameTextView.text?.toString()?.takeIf { it.isNotBlank() }
        val started = CallRecorder.startRecording(this, number, callerName)
        if (started) {
            Toast.makeText(this, getString(R.string.recording_started), Toast.LENGTH_SHORT).show()
            binding.root.announceForAccessibility(getString(R.string.recording_started))
        }
    }

    private fun observeRecordingUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallRecorder.isRecording.collect { isRecording ->
                    binding.btnRecord.isSelected = isRecording
                    binding.btnRecord.text = if (isRecording) "Recording" else getString(R.string.btn_record)
                    binding.btnRecord.contentDescription = if (isRecording) {
                        getString(R.string.action_stop_recording)
                    } else {
                        getString(R.string.action_start_recording)
                    }
                }
            }
        }
    }

    private fun observeCallUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CallManager.currentCall.collect { call ->
                        if (call != null) {
                            resolveCallerInfo(call)
                        } else {
                            stopCallTimer()
                            finish()
                        }
                    }
                }

                launch {
                    CallManager.callState.collect { state ->
                        handleCallStateChange(state)
                    }
                }
            }
        }
    }

    private fun observeAudioUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.audioState.collect { audioState ->
                    updateAudioControls(audioState)
                }
            }
        }
    }

    private fun handleCallStateChange(state: Int) {
        when (state) {
            Call.STATE_RINGING -> {
                stopCallTimer()
                binding.callStateTextView.text = getString(R.string.call_state_ringing)
                binding.callStateTextView.contentDescription = getString(R.string.call_state_ringing)
                binding.ringingControlsLayout.isVisible = true
                binding.activeControlsLayout.isVisible = false
                binding.btnAnswer.requestFocus()
            }

            Call.STATE_ACTIVE -> {
                val connectTime = CallManager.currentCall.value?.details?.connectTimeMillis ?: 0L
                startCallTimer(connectTime)
                binding.ringingControlsLayout.isVisible = false
                binding.activeControlsLayout.isVisible = true

                if (settingsManager.isAutoRecording && !CallRecorder.isRecording.value) {
                    val number = CallManager.currentCall.value?.details?.handle?.schemeSpecificPart.orEmpty()
                    startRecordingInternal(number)
                }
            }

            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                stopCallTimer()
                binding.callStateTextView.text = getString(R.string.call_state_calling)
                binding.callStateTextView.contentDescription = getString(R.string.call_state_calling)
                binding.ringingControlsLayout.isVisible = false
                binding.activeControlsLayout.isVisible = true
            }

            Call.STATE_HOLDING -> {
                stopCallTimer()
                binding.callStateTextView.text = getString(R.string.call_state_on_hold)
                binding.callStateTextView.contentDescription = getString(R.string.call_state_on_hold)
                binding.ringingControlsLayout.isVisible = false
                binding.activeControlsLayout.isVisible = true
            }

            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                CallRecorder.stopRecording(this)
                stopCallTimer()
                binding.callStateTextView.text = getString(R.string.call_state_ended)
                binding.callStateTextView.contentDescription = getString(R.string.call_state_ended)
                binding.ringingControlsLayout.isVisible = false
                binding.activeControlsLayout.isVisible = false
                finish()
            }

            else -> {
                // Keep existing UI for other intermediate states
            }
        }
    }

    private fun resolveCallerInfo(call: Call) {
        val handle = call.details?.handle
        val number = handle?.schemeSpecificPart.orEmpty()
        var displayName = call.details?.callerDisplayName

        if (displayName.isNullOrBlank() && number.isNotBlank()) {
            displayName = queryContactName(number)
        }

        val resolvedName = if (!displayName.isNullOrBlank()) displayName else number
        binding.callerNameTextView.text = resolvedName
        binding.callerNameTextView.contentDescription = "Caller: $resolvedName"

        binding.callerNumberTextView.text = number
        binding.callerNumberTextView.contentDescription = "Phone number: $number"
    }

    private fun queryContactName(phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startCallTimer(connectTimeMillis: Long) {
        callTimerJob?.cancel()
        val startTime = if (connectTimeMillis > 0L) connectTimeMillis else System.currentTimeMillis()

        callTimerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                val safeElapsed = if (elapsedSeconds < 0) 0 else elapsedSeconds
                val minutes = safeElapsed / 60
                val seconds = safeElapsed % 60
                val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                binding.callStateTextView.text = formattedTime
                binding.callStateTextView.contentDescription = "Call duration $formattedTime"
                delay(1000)
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
    }

    private fun updateAudioControls(audioState: CallAudioState?) {
        val isSpeakerOn = audioState?.route == CallAudioState.ROUTE_SPEAKER
        val isMuted = audioState?.isMuted == true

        binding.btnSpeaker.isSelected = isSpeakerOn
        binding.btnSpeaker.contentDescription = if (isSpeakerOn) {
            getString(R.string.action_turn_off_speaker)
        } else {
            getString(R.string.action_turn_on_speaker)
        }
        binding.btnSpeaker.text = if (isSpeakerOn) "Speaker (On)" else getString(R.string.btn_speaker)

        binding.btnMute.isSelected = isMuted
        binding.btnMute.contentDescription = if (isMuted) {
            getString(R.string.action_unmute_microphone)
        } else {
            getString(R.string.action_mute_microphone)
        }
        binding.btnMute.text = if (isMuted) "Muted" else getString(R.string.btn_mute)
    }

    override fun onDestroy() {
        super.onDestroy()
        CallRecorder.stopRecording(this)
        stopCallTimer()
    }
}
