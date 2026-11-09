package com.shihabkt.ktsdialer

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton managing active Telecom Call and Audio state across the application.
 * Emits reactive StateFlow updates for UI components like CallActivity.
 */
object CallManager {

    private const val TAG = "CallManager"

    private val _currentCall = MutableStateFlow<Call?>(null)
    val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()

    private val _callState = MutableStateFlow(Call.STATE_DISCONNECTED)
    val callState: StateFlow<Int> = _callState.asStateFlow()

    private val _audioState = MutableStateFlow<CallAudioState?>(null)
    val audioState: StateFlow<CallAudioState?> = _audioState.asStateFlow()

    var inCallService: KtsInCallService? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "Call callback onStateChanged: state=$state")
            _callState.value = state
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            Log.d(TAG, "Call callback onDetailsChanged")
        }
    }

    fun updateCall(call: Call?) {
        try {
            _currentCall.value?.unregisterCallback(callCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering callback: ${e.message}")
        }

        _currentCall.value = call

        if (call != null) {
            call.registerCallback(callCallback)
            _callState.value = call.state
        } else {
            _callState.value = Call.STATE_DISCONNECTED
        }
    }

    fun updateAudioState(audioState: CallAudioState?) {
        _audioState.value = audioState
    }

    fun answer() {
        try {
            _currentCall.value?.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call: ${e.message}", e)
        }
    }

    fun decline() {
        try {
            _currentCall.value?.reject(false, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error declining call: ${e.message}", e)
        }
    }

    fun disconnect() {
        try {
            _currentCall.value?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting call: ${e.message}", e)
        }
    }

    fun toggleSpeaker() {
        val service = inCallService ?: return
        val currentRoute = _audioState.value?.route ?: service.callAudioState?.route
        val targetRoute = if (currentRoute == CallAudioState.ROUTE_SPEAKER) {
            CallAudioState.ROUTE_EARPIECE
        } else {
            CallAudioState.ROUTE_SPEAKER
        }
        service.setAudioRoute(targetRoute)
    }

    fun toggleMute() {
        val service = inCallService ?: return
        val isMuted = _audioState.value?.isMuted ?: service.callAudioState?.isMuted ?: false
        service.setMuted(!isMuted)
    }
}
