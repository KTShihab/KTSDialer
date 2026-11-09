package com.shihabkt.ktsdialer

/**
 * Data representation for an Android system contact.
 *
 * @property id Unique data row ID.
 * @property contactId Raw or aggregate contact ID in ContactsContract.
 * @property name Contact display name.
 * @property number Contact phone number.
 * @property isStarred Whether the contact is marked as starred/favorite in the system contacts.
 */
data class ContactModel(
    val id: Long,
    val contactId: Long,
    val name: String,
    val number: String,
    val isStarred: Boolean = false
)
