package com.shihabkt.ktsdialer

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

/**
 * InCallService implementation required to qualify as the default phone app on Android.
 * Receives lifecycle updates for incoming and outgoing calls and launches CallActivity.
 */
class KtsInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: $call")
        CallManager.inCallService = this
        CallManager.updateCall(call)
        // Delegating in-call UI to system so native 2-way call recording runs automatically
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
        if (CallManager.currentCall.value == call) {
            CallManager.updateCall(null)
        }
        // As soon as call ends, index new recordings made by the phone's built-in recorder
        CallRecorder.refreshRecordingsCacheAsync(applicationContext)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        Log.d(TAG, "onCallAudioStateChanged: route=${audioState?.route}, isMuted=${audioState?.isMuted}")
        CallManager.updateAudioState(audioState)
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.inCallService = null
    }

    companion object {
        private const val TAG = "KtsInCallService"
    }
}
