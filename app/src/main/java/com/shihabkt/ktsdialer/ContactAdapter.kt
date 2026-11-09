package com.shihabkt.ktsdialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.shihabkt.ktsdialer.databinding.ItemContactBinding

/**
 * Adapter for displaying system contacts in a RecyclerView.
 * Ensures items are single-part focusable for screen readers (TalkBack, Jieshuo, AccessiMind).
 */
class ContactAdapter(
    private val onPermissionNeeded: ((String) -> Unit)? = null,
    private val onViewDetails: ((ContactModel) -> Unit)? = null,
    private val onToggleFavorite: ((ContactModel) -> Unit)? = null,
    private val onSendMessage: ((ContactModel) -> Unit)? = null
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    private val contactItems = mutableListOf<ContactModel>()

    fun updateData(newList: List<ContactModel>) {
        contactItems.clear()
        contactItems.addAll(newList)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = contactItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ContactViewHolder(
            binding = binding,
            onPermissionNeeded = onPermissionNeeded,
            onViewDetails = onViewDetails,
            onToggleFavorite = onToggleFavorite,
            onSendMessage = onSendMessage
        )
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(contactItems[position])
    }

    class ContactViewHolder(
        private val binding: ItemContactBinding,
        private val onPermissionNeeded: ((String) -> Unit)?,
        private val onViewDetails: ((ContactModel) -> Unit)?,
        private val onToggleFavorite: ((ContactModel) -> Unit)?,
        private val onSendMessage: ((ContactModel) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContactModel) {
            val context: Context = itemView.context
            val displayName = if (item.name.isNotBlank()) item.name else context.getString(R.string.unknown_caller)

            // Visual TextViews (accessibility disabled in XML)
            binding.contactNameTextView.text = displayName
            binding.contactNumberTextView.text = item.number

            // 1. Direct call on root view click
            itemView.setOnClickListener {
                initiateDirectCall(context, item.number)
            }

            // 2. Set combined single-part content description for screen readers
            val starStatus = if (item.isStarred) ", Starred" else ""
            val accessibleDescription = "$displayName, ${item.number}$starStatus"
            itemView.contentDescription = accessibleDescription

            // 3. AccessibilityDelegate with custom actions for TalkBack and Jieshuo
            ViewCompat.setAccessibilityDelegate(itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.contentDescription = accessibleDescription

                    // Custom action: 'View Details'
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_view_details,
                            context.getString(R.string.action_view_details)
                        )
                    )

                    // Custom action: 'Message on WhatsApp'
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_whatsapp_message,
                            context.getString(R.string.action_whatsapp_message)
                        )
                    )

                    // Custom action: 'Message on Telegram'
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_telegram_message,
                            context.getString(R.string.action_telegram_message)
                        )
                    )

                    // Custom action: 'Send Message'
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_send_message,
                            context.getString(R.string.action_send_message)
                        )
                    )

                    // Custom action: 'Add to Favorite' or 'Remove from Favorite'
                    val favoriteActionLabel = if (item.isStarred) {
                        "Remove from Favorite"
                    } else {
                        context.getString(R.string.action_add_to_favorite)
                    }
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_add_to_favorite,
                            favoriteActionLabel
                        )
                    )
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
                        R.id.action_add_to_favorite -> {
                            onToggleFavorite?.invoke(item)
                            true
                        }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            })
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

        private fun handleSendMessage(context: Context, item: ContactModel) {
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
