package com.shihabkt.ktsdialer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.shihabkt.ktsdialer.databinding.ItemCallLogBinding

/**
 * Adapter for displaying recent call logs in a RecyclerView.
 *
 * Provides a thread-safe updateData function with notifyDataSetChanged()
 * and maintains complete screen-reader accessibility for TalkBack and Jieshuo.
 *
 * Accessibility Highlights:
 * - Checks ContactsContract to determine if the number is already starred.
 * - Adds 'Remove from Favorite' if already a favorite, or 'Add to Favorite' if not.
 * - Handles the ContactsContract.Contacts.STARRED update to 1 or 0 in performAccessibilityAction.
 */
class CallLogAdapter(
    private val onPermissionNeeded: ((String) -> Unit)? = null,
    private val onViewDetails: ((CallLogModel) -> Unit)? = null,
    private val onViewCallHistory: ((CallLogModel) -> Unit)? = null,
    private val onFavoriteToggled: ((CallLogModel, Boolean) -> Unit)? = null,
    private val onSendMessage: ((CallLogModel) -> Unit)? = null,
    private val onDeleteFromLog: ((CallLogModel) -> Unit)? = null
) : RecyclerView.Adapter<CallLogAdapter.CallLogViewHolder>() {

    private val callLogItems = mutableListOf<CallLogModel>()

    /**
     * Safely assigns the new list and notifies the adapter of the data change.
     */
    fun updateData(newList: List<CallLogModel>) {
        callLogItems.clear()
        callLogItems.addAll(newList)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = callLogItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallLogViewHolder {
        val binding = ItemCallLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CallLogViewHolder(
            binding = binding,
            onPermissionNeeded = onPermissionNeeded,
            onViewDetails = onViewDetails,
            onViewCallHistory = onViewCallHistory,
            onFavoriteToggled = onFavoriteToggled,
            onSendMessage = onSendMessage,
            onDeleteFromLog = onDeleteFromLog
        )
    }

    override fun onBindViewHolder(holder: CallLogViewHolder, position: Int) {
        holder.bind(callLogItems[position])
    }

    class CallLogViewHolder(
        private val binding: ItemCallLogBinding,
        private val onPermissionNeeded: ((String) -> Unit)?,
        private val onViewDetails: ((CallLogModel) -> Unit)?,
        private val onViewCallHistory: ((CallLogModel) -> Unit)?,
        private val onFavoriteToggled: ((CallLogModel, Boolean) -> Unit)?,
        private val onSendMessage: ((CallLogModel) -> Unit)?,
        private val onDeleteFromLog: ((CallLogModel) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        data class ContactLookup(
            val contactId: Long,
            val isStarred: Boolean
        )

        fun bind(item: CallLogModel) {
            val context: Context = itemView.context
            val displayName = if (!item.name.isNullOrBlank()) {
                item.name
            } else {
                context.getString(R.string.unknown_caller)
            }

            val isContact = item.callType.equals("Contact", ignoreCase = true)
            val simSuffix = if (item.simInfo.isNotBlank()) " on ${item.simInfo}" else ""
            val callTypeWithSim = "${item.callType}$simSuffix"

            // Bind visual text views (child accessibility is disabled in item_call_log.xml)
            binding.nameTextView.text = displayName
            binding.numberTextView.text = item.number
            binding.timeTextView.text = if (isContact) {
                "Saved Contact"
            } else {
                "${item.time} • $callTypeWithSim • ${item.duration}"
            }

            // 1. Direct ACTION_CALL on root view click
            itemView.setOnClickListener {
                initiateDirectCall(context, item.number)
            }

            // 2. Set combined contentDescription
            val combinedAccessibilityDescription = if (isContact) {
                "$displayName, ${item.number}, Saved Contact"
            } else {
                "$displayName, ${item.number}, $callTypeWithSim, ${item.time}, Duration: ${item.duration}"
            }
            itemView.contentDescription = combinedAccessibilityDescription

            // 3. Configure AccessibilityDelegate with custom actions for screen readers
            ViewCompat.setAccessibilityDelegate(itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.contentDescription = combinedAccessibilityDescription

                    // 1. 'View Details' action
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_view_details,
                            context.getString(R.string.action_view_details)
                        )
                    )

                    // 2. 'View Call History' action
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_view_call_history,
                            context.getString(R.string.action_view_call_history)
                        )
                    )

                    // 3. Dynamic Favorite action based on ContactsContract check
                    val contactLookup = findContactLookup(context, item.number)
                    val isAlreadyStarred = contactLookup?.isStarred == true

                    if (isAlreadyStarred) {
                        info.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                R.id.action_remove_from_favorite,
                                context.getString(R.string.action_remove_from_favorite)
                            )
                        )
                    } else {
                        info.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                R.id.action_add_to_favorite,
                                context.getString(R.string.action_add_to_favorite)
                            )
                        )
                    }

                    // Additional utility actions
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_whatsapp_message,
                            context.getString(R.string.action_whatsapp_message)
                        )
                    )
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_telegram_message,
                            context.getString(R.string.action_telegram_message)
                        )
                    )
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_send_message,
                            context.getString(R.string.action_send_message)
                        )
                    )

                    // Call recording actions if recording exists for this number or contact
                    val recordings = CallRecorder.getRecordingsForNumber(context, item.number, item.name)
                    if (recordings.isNotEmpty()) {
                        val playLabel = if (recordings.size > 1) {
                            "${context.getString(R.string.action_play_recording)} (${recordings.size})"
                        } else {
                            context.getString(R.string.action_play_recording)
                        }
                        info.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                R.id.action_play_recording,
                                playLabel
                            )
                        )
                        info.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                R.id.action_share_recording,
                                context.getString(R.string.action_share_recording)
                            )
                        )
                    }

                    if (!isContact) {
                        info.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                R.id.action_delete_from_log,
                                context.getString(R.string.action_delete_from_log)
                            )
                        )
                    }
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?
                ): Boolean {
                    return when (action) {
                        R.id.action_view_details -> {
                            onViewDetails?.invoke(item)
                            true
                        }
                        R.id.action_view_call_history -> {
                            onViewCallHistory?.invoke(item)
                            true
                        }
                        R.id.action_add_to_favorite -> {
                            updateContactStarredState(context, item, targetStarred = true)
                            true
                        }
                        R.id.action_remove_from_favorite -> {
                            updateContactStarredState(context, item, targetStarred = false)
                            true
                        }
                        R.id.action_whatsapp_message -> {
                            SocialMessenger.openWhatsApp(context, item.number)
                            true
                        }
                        R.id.action_telegram_message -> {
                            SocialMessenger.openTelegram(context, item.number)
                            true
                        }
                        R.id.action_send_message -> {
                            handleSendMessage(context, item)
                            true
                        }
                        R.id.action_play_recording -> {
                            val list = CallRecorder.getRecordingsForNumber(context, item.number, item.name)
                            if (list.isNotEmpty()) {
                                val title = item.name ?: item.number
                                RecordingPlayerDialog.showRecordings(context, list, title)
                            } else {
                                Toast.makeText(context, context.getString(R.string.no_recordings_found), Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        R.id.action_share_recording -> {
                            val list = CallRecorder.getRecordingsForNumber(context, item.number, item.name)
                            if (list.isNotEmpty()) {
                                CallRecorder.shareRecording(context, list.first())
                            } else {
                                Toast.makeText(context, context.getString(R.string.no_recordings_found), Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                        R.id.action_delete_from_log -> {
                            onDeleteFromLog?.invoke(item)
                            true
                        }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            })
        }

        /**
         * Checks ContactsContract using PhoneLookup to find the contact ID and starred status.
         */
        private fun findContactLookup(context: Context, phoneNumber: String): ContactLookup? {
            if (ContextCompat.checkSelfPermission(
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
                val projection = arrayOf(
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.STARRED
                )

                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                        val starredIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.STARRED)
                        val id = if (idIndex != -1) cursor.getLong(idIndex) else -1L
                        val starred = if (starredIndex != -1) cursor.getInt(starredIndex) == 1 else false
                        if (id != -1L) ContactLookup(id, starred) else null
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Updates ContactsContract.Contacts.STARRED column to 1 (add) or 0 (remove) for that specific contact.
         */
        private fun updateContactStarredState(
            context: Context,
            item: CallLogModel,
            targetStarred: Boolean
        ) {
            val displayName = if (!item.name.isNullOrBlank()) item.name else item.number

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_CONTACTS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    context,
                    context.getString(R.string.permission_contacts_required),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val lookup = findContactLookup(context, item.number)
            if (lookup == null) {
                val notSavedMsg = context.getString(R.string.not_in_contacts)
                Toast.makeText(context, notSavedMsg, Toast.LENGTH_SHORT).show()
                itemView.announceForAccessibility(notSavedMsg)
                return
            }

            try {
                val values = ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (targetStarred) 1 else 0)
                }

                val rowsUpdated = context.contentResolver.update(
                    ContactsContract.Contacts.CONTENT_URI,
                    values,
                    "${ContactsContract.Contacts._ID} = ?",
                    arrayOf(lookup.contactId.toString())
                )

                if (rowsUpdated > 0) {
                    val announcement = if (targetStarred) {
                        context.getString(R.string.added_to_favorites, displayName)
                    } else {
                        context.getString(R.string.removed_from_favorites, displayName)
                    }
                    Toast.makeText(context, announcement, Toast.LENGTH_SHORT).show()
                    itemView.announceForAccessibility(announcement)
                    onFavoriteToggled?.invoke(item, targetStarred)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error updating contact favorite status", Toast.LENGTH_SHORT).show()
            }
        }

        private fun initiateDirectCall(context: Context, phoneNumber: String) {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                context.startActivity(callIntent)
            } else {
                if (onPermissionNeeded != null) {
                    onPermissionNeeded.invoke(phoneNumber)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.call_permission_required),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        private fun handleSendMessage(context: Context, item: CallLogModel) {
            if (onSendMessage != null) {
                onSendMessage.invoke(item)
            } else {
                val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${item.number}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(smsIntent)
            }
        }
    }
}
