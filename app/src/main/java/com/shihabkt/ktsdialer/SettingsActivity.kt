package com.shihabkt.ktsdialer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shihabkt.ktsdialer.databinding.ActivitySettingsBinding

/**
 * Settings Activity allowing users to configure:
 * 1. 'Dialpad Tap Mode' (Single Tap Mode, Double Tap Mode, Lift Mode)
 * 2. 'Announce letters with numbers on dialpad' (Toggle)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        setupToolbar()
        setupTapModeSelection()
        setupAnnounceLettersSwitch()
        setupRecordingModeSelection()
        setupRecordingQualitySelection()
        setupCheckUpdatesButton()
    }

    private fun setupCheckUpdatesButton() {
        binding.btnCheckUpdates.setOnClickListener {
            AppUpdateManager.checkForUpdates(this, manual = true)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.settingsToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupTapModeSelection() {
        when (settingsManager.dialpadTapMode) {
            SettingsManager.MODE_SINGLE_TAP -> binding.rbSingleTap.isChecked = true
            SettingsManager.MODE_LIFT_MODE -> binding.rbLiftMode.isChecked = true
            else -> binding.rbDoubleTap.isChecked = true
        }

        binding.tapModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val (selectedMode, modeNameRes) = when (checkedId) {
                R.id.rbSingleTap -> Pair(SettingsManager.MODE_SINGLE_TAP, R.string.mode_single_tap)
                R.id.rbLiftMode -> Pair(SettingsManager.MODE_LIFT_MODE, R.string.mode_lift_mode)
                else -> Pair(SettingsManager.MODE_DOUBLE_TAP, R.string.mode_double_tap)
            }

            settingsManager.dialpadTapMode = selectedMode

            val modeName = getString(modeNameRes)
            val feedbackText = "$modeName enabled"
            Toast.makeText(this, feedbackText, Toast.LENGTH_SHORT).show()
            binding.root.announceForAccessibility(feedbackText)
        }
    }

    private fun setupAnnounceLettersSwitch() {
        binding.switchAnnounceLetters.isChecked = settingsManager.announceLettersWithNumbers

        binding.switchAnnounceLetters.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.announceLettersWithNumbers = isChecked

            val feedbackText = if (isChecked) {
                getString(R.string.announce_letters_enabled)
            } else {
                getString(R.string.announce_letters_disabled)
            }
            Toast.makeText(this, feedbackText, Toast.LENGTH_SHORT).show()
            binding.root.announceForAccessibility(feedbackText)
        }
    }

    private fun setupRecordingModeSelection() {
        if (settingsManager.isAutoRecording) {
            binding.rbRecordingAuto.isChecked = true
        } else {
            binding.rbRecordingManual.isChecked = true
        }

        binding.recordingModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isAuto = checkedId == R.id.rbRecordingAuto
            settingsManager.callRecordingMode = if (isAuto) {
                SettingsManager.MODE_RECORDING_AUTO
            } else {
                SettingsManager.MODE_RECORDING_MANUAL
            }

            val modeName = if (isAuto) getString(R.string.recording_mode_auto) else getString(R.string.recording_mode_manual)
            val feedbackText = "$modeName selected"
            Toast.makeText(this, feedbackText, Toast.LENGTH_SHORT).show()
            binding.root.announceForAccessibility(feedbackText)
        }
    }

    private fun setupRecordingQualitySelection() {
        when (settingsManager.recordingQuality) {
            SettingsManager.QUALITY_LOW -> binding.rbQualityLow.isChecked = true
            SettingsManager.QUALITY_HIGH -> binding.rbQualityHigh.isChecked = true
            else -> binding.rbQualityMedium.isChecked = true
        }

        binding.recordingQualityRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val (quality, qualityLabel) = when (checkedId) {
                R.id.rbQualityLow -> Pair(SettingsManager.QUALITY_LOW, getString(R.string.recording_quality_low))
                R.id.rbQualityHigh -> Pair(SettingsManager.QUALITY_HIGH, getString(R.string.recording_quality_high))
                else -> Pair(SettingsManager.QUALITY_MEDIUM, getString(R.string.recording_quality_medium))
            }

            settingsManager.recordingQuality = quality
            val feedbackText = "Quality set to $qualityLabel"
            Toast.makeText(this, feedbackText, Toast.LENGTH_SHORT).show()
            binding.root.announceForAccessibility(feedbackText)
        }
    }
}
