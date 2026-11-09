package com.shihabkt.ktsdialer

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accessible Dialog for playing call recordings with:
 * - Play / Pause toggle
 * - Earpiece vs Loudspeaker audio routing toggle (using modern setCommunicationDevice)
 * - One-tap sharing
 * - Multi-recording selection picker for contacts with multiple recordings
 */
object RecordingPlayerDialog {

    fun showRecordings(context: Context, files: List<File>, title: String? = null) {
        if (files.isEmpty()) return

        if (files.size == 1) {
            show(context, files[0])
            return
        }

        // Show a list dialog to pick from multiple recordings
        val dateFormat = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault())
        val items = files.map { file ->
            val formattedDate = dateFormat.format(Date(file.lastModified()))
            val sizeStr = Formatter.formatFileSize(context, file.length())
            "$formattedDate ($sizeStr)"
        }.toTypedArray()

        val dialogTitle = title ?: context.getString(R.string.dialog_recording_player_title)

        MaterialAlertDialogBuilder(context)
            .setTitle("$dialogTitle (${files.size})")
            .setItems(items) { _, which ->
                show(context, files[which])
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    fun show(context: Context, file: File) {
        var isPlaying = true
        var isLoudspeaker = true

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_recording_player, null)
        val fileNameTextView = dialogView.findViewById<TextView>(R.id.tvRecordingFileName)
        val btnPlayPause = dialogView.findViewById<Button>(R.id.btnPlayPause)
        val btnToggleRoute = dialogView.findViewById<Button>(R.id.btnToggleRoute)
        val btnShare = dialogView.findViewById<Button>(R.id.btnShareRecording)

        fileNameTextView.text = file.name

        fun updateUI() {
            btnPlayPause.text = if (isPlaying) "Pause" else "Play"
            btnPlayPause.contentDescription = if (isPlaying) "Pause Recording" else "Play Recording"

            val routeName = if (isLoudspeaker) context.getString(R.string.mode_loudspeaker) else context.getString(R.string.mode_earpiece)
            val otherRoute = if (isLoudspeaker) context.getString(R.string.mode_earpiece) else context.getString(R.string.mode_loudspeaker)
            btnToggleRoute.text = "Output: $routeName"
            btnToggleRoute.contentDescription = context.getString(R.string.action_toggle_output, otherRoute)
        }

        updateUI()

        CallRecorder.startPlayback(context, file, useLoudspeaker = true) {
            isPlaying = false
            updateUI()
        }

        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                CallRecorder.stopPlayback(context)
                isPlaying = false
            } else {
                CallRecorder.startPlayback(context, file, useLoudspeaker = isLoudspeaker) {
                    isPlaying = false
                    updateUI()
                }
                isPlaying = true
            }
            updateUI()
        }

        btnToggleRoute.setOnClickListener {
            isLoudspeaker = !isLoudspeaker
            CallRecorder.toggleAudioRoute(context)
            updateUI()
        }

        btnShare.setOnClickListener {
            CallRecorder.shareRecording(context, file)
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_recording_player_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_close) { _, _ ->
                CallRecorder.stopPlayback(context)
            }
            .setOnDismissListener {
                CallRecorder.stopPlayback(context)
            }
            .create()

        dialog.show()
    }
}
