package com.shihabkt.ktsdialer

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user preferences including:
 * - 'Dialpad Tap Mode' (Single Tap Mode, Double Tap Mode, Lift Mode)
 * - 'Announce letters with numbers on dialpad' (Boolean)
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var dialpadTapMode: String
        get() = prefs.getString(KEY_TAP_MODE, MODE_DOUBLE_TAP) ?: MODE_DOUBLE_TAP
        set(value) {
            prefs.edit().putString(KEY_TAP_MODE, value).apply()
        }

    val isSingleTapMode: Boolean
        get() = dialpadTapMode == MODE_SINGLE_TAP

    val isLiftMode: Boolean
        get() = dialpadTapMode == MODE_LIFT_MODE

    var announceLettersWithNumbers: Boolean
        get() = prefs.getBoolean(KEY_ANNOUNCE_LETTERS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ANNOUNCE_LETTERS, value).apply()
        }

    var callRecordingMode: String
        get() = prefs.getString(KEY_RECORDING_MODE, MODE_RECORDING_MANUAL) ?: MODE_RECORDING_MANUAL
        set(value) {
            prefs.edit().putString(KEY_RECORDING_MODE, value).apply()
        }

    val isAutoRecording: Boolean
        get() = callRecordingMode == MODE_RECORDING_AUTO

    var recordingQuality: String
        get() = prefs.getString(KEY_RECORDING_QUALITY, QUALITY_MEDIUM) ?: QUALITY_MEDIUM
        set(value) {
            prefs.edit().putString(KEY_RECORDING_QUALITY, value).apply()
        }

    val recordingBitRate: Int
        get() = when (recordingQuality) {
            QUALITY_LOW -> 64000
            QUALITY_HIGH -> 256000
            else -> 128000
        }

    val recordingSampleRate: Int
        get() = when (recordingQuality) {
            QUALITY_LOW -> 22050
            QUALITY_HIGH -> 48000
            else -> 44100
        }

    companion object {
        private const val PREFS_NAME = "kts_dialer_settings_prefs"
        const val KEY_TAP_MODE = "dialpad_tap_mode"
        const val KEY_ANNOUNCE_LETTERS = "announce_letters_with_numbers"
        const val KEY_RECORDING_MODE = "call_recording_mode"
        const val KEY_RECORDING_QUALITY = "call_recording_quality"

        const val MODE_SINGLE_TAP = "single_tap"
        const val MODE_DOUBLE_TAP = "double_tap"
        const val MODE_LIFT_MODE = "lift_mode"

        const val MODE_RECORDING_MANUAL = "manual"
        const val MODE_RECORDING_AUTO = "auto"

        const val QUALITY_LOW = "low"
        const val QUALITY_MEDIUM = "medium"
        const val QUALITY_HIGH = "high"
    }
}
