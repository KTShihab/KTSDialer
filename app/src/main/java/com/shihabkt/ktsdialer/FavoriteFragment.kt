package com.shihabkt.ktsdialer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shihabkt.ktsdialer.databinding.FragmentFavoriteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment displaying ONLY the starred/favorite contacts fetched directly from ContactsContract.
 * Does NOT use local SharedPreferences or Room.
 */
class FavoriteFragment : Fragment() {

    private var _binding: FragmentFavoriteBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactAdapter: ContactAdapter
    private var favoriteContacts: List<ContactModel> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoriteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    private var contactsObserver: ContentObserver? = null

    private fun hasReadContactsPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerContactsObserver() {
        if (contactsObserver != null) return
        val appContext = context?.applicationContext ?: return
        if (!hasReadContactsPermission()) return

        contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Contacts change detected via ContentObserver: uri=$uri")
                if (isAdded && hasReadContactsPermission()) {
                    fetchStarredContacts()
                }
            }
        }

        try {
            appContext.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                contactsObserver!!
            )
            Log.d(TAG, "Registered Contacts ContentObserver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Contacts ContentObserver: ${e.message}", e)
        }
    }

    private fun unregisterContactsObserver() {
        contactsObserver?.let { observer ->
            try {
                context?.applicationContext?.contentResolver?.unregisterContentObserver(observer)
                Log.d(TAG, "Unregistered Contacts ContentObserver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister Contacts ContentObserver: ${e.message}", e)
            }
            contactsObserver = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasReadContactsPermission()) {
            registerContactsObserver()
            fetchStarredContacts()
        }
    }

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter(
            onViewDetails = { item ->
                showFavoriteDetailsDialog(item)
            },
            onToggleFavorite = { item ->
                removeStarredContact(item)
            }
        )

        binding.favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactAdapter
            addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            )
        }
    }

    /**
     * Fetches ONLY contacts where ContactsContract.Contacts.STARRED == 1.
     * Operates on an IO coroutine thread and safely posts to Main thread.
     */
    private fun fetchStarredContacts() {
        val appContext = context?.applicationContext ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = mutableListOf<ContactModel>()

            try {
                if (ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.READ_CONTACTS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(Dispatchers.Main) { showEmptyState(true) }
                    return@launch
                }

                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone._ID,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.STARRED,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                )

                // Query specifically for starred contacts using STARRED column
                val selection = "${ContactsContract.CommonDataKinds.Phone.STARRED} = ?"
                val selectionArgs = arrayOf("1")
                val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC, " +
                        "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                        "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"

                val cursor: Cursor? = appContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

                val rawList = mutableListOf<ContactModel>()
                cursor?.use {
                    val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
                    val contactIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    while (it.moveToNext()) {
                        val id = if (idIndex != -1) it.getLong(idIndex) else 0L
                        val contactId = if (contactIdIndex != -1) it.getLong(contactIdIndex) else 0L
                        val name = if (nameIndex != -1) it.getString(nameIndex) ?: "" else ""
                        val number = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""

                        rawList.add(
                            ContactModel(
                                id = id,
                                contactId = contactId,
                                name = name,
                                number = number,
                                isStarred = true
                            )
                        )
                    }
                }

                // Deduplicate so each favorite person appears exactly ONCE (preventing duplicate rows)
                val seenContactIds = HashSet<Long>()
                val seenNames = HashSet<String>()
                val seenNumbers = HashSet<String>()
                val uniqueFavorites = mutableListOf<ContactModel>()

                for (item in rawList) {
                    val cleanName = item.name.trim().lowercase()
                    val normalizedNumber = item.number.replace("[^0-9+]".toRegex(), "")
                    val hasValidName = cleanName.isNotBlank() && cleanName != "unknown caller"

                    // If contactId was already included, skip (avoids multiple numbers for the same person)
                    if (item.contactId > 0L && seenContactIds.contains(item.contactId)) {
                        continue
                    }
                    // If the same person's name was already included, skip (avoids duplicate accounts like WhatsApp/Google)
                    if (hasValidName && seenNames.contains(cleanName)) {
                        continue
                    }
                    // If the exact same phone number was already included, skip
                    if (normalizedNumber.isNotBlank() && seenNumbers.contains(normalizedNumber)) {
                        continue
                    }

                    if (item.contactId > 0L) seenContactIds.add(item.contactId)
                    if (hasValidName) seenNames.add(cleanName)
                    if (normalizedNumber.isNotBlank()) seenNumbers.add(normalizedNumber)

                    uniqueFavorites.add(item)
                }

                withContext(Dispatchers.Main) {
                    favoriteContacts = uniqueFavorites
                    contactAdapter.updateData(favoriteContacts)
                    showEmptyState(favoriteContacts.isEmpty())
                }

            } catch (securityEx: SecurityException) {
                Log.e(TAG, "SecurityException while accessing starred contacts: ${securityEx.message}", securityEx)
                withContext(Dispatchers.Main) { showEmptyState(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching starred contacts: ${e.message}", e)
                withContext(Dispatchers.Main) { showEmptyState(true) }
            }
        }
    }

    /**
     * Unstars the contact in the Android system contacts.
     */
    private fun removeStarredContact(item: ContactModel) {
        val appContext = context?.applicationContext ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.WRITE_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val values = ContentValues().apply {
                        put(ContactsContract.Contacts.STARRED, 0)
                    }
                    appContext.contentResolver.update(
                        ContactsContract.Contacts.CONTENT_URI,
                        values,
                        "${ContactsContract.Contacts._ID} = ?",
                        arrayOf(item.contactId.toString())
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unstarring contact: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                val displayName = if (item.name.isNotBlank()) item.name else item.number
                val announcement = getString(R.string.removed_from_favorites, displayName)
                Toast.makeText(requireContext(), announcement, Toast.LENGTH_SHORT).show()
                binding.root.announceForAccessibility(announcement)
                fetchStarredContacts()
            }
        }
    }

    private fun showFavoriteDetailsDialog(item: ContactModel) {
        val displayName = if (item.name.isNotBlank()) item.name else getString(R.string.unknown_caller)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_contact_details_title)
            .setMessage("Name: $displayName\nNumber: ${item.number}\nStatus: Starred Contact")
            .setPositiveButton(R.string.dialog_close, null)
            .setNeutralButton("WhatsApp") { _, _ ->
                SocialMessenger.openWhatsApp(requireContext(), item.number)
            }
            .setNegativeButton("Telegram") { _, _ ->
                SocialMessenger.openTelegram(requireContext(), item.number)
            }
            .create()
            .apply {
                setOnShowListener {
                    window?.decorView?.sendAccessibilityEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    )
                }
            }
            .show()
    }

    private fun showEmptyState(isEmpty: Boolean) {
        if (_binding != null) {
            binding.emptyFavoritesTextView.isVisible = isEmpty
            binding.favoritesRecyclerView.isVisible = !isEmpty
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterContactsObserver()
        _binding = null
    }

    companion object {
        private const val TAG = "FavoriteFragment"
        fun newInstance(): FavoriteFragment = FavoriteFragment()
    }
}
