package com.shihabkt.ktsdialer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shihabkt.ktsdialer.databinding.FragmentContactBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment displaying all contacts fetched directly from Android's ContactsContract.
 * Items are single-part focusable for screen readers (TalkBack, Jieshuo, AccessiMind).
 */
class ContactFragment : Fragment() {

    private var _binding: FragmentContactBinding? = null
    private val binding get() = _binding!!

    private lateinit var contactAdapter: ContactAdapter
    private var originalContactsList: List<ContactModel> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readContactsGranted = permissions[Manifest.permission.READ_CONTACTS] == true
        if (readContactsGranted) {
            fetchContacts()
        } else {
            showEmptyState(true)
            Toast.makeText(
                requireContext(),
                getString(R.string.permission_contacts_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearchBar()
        checkAndRequestPermissions()
    }

    private fun setupSearchBar() {
        binding.searchContactEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterContacts(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterContacts(query: String) {
        val trimmed = query.trim()
        val filteredList = if (trimmed.isEmpty()) {
            originalContactsList
        } else {
            val normalizedQuery = trimmed.replace(" ", "")
            originalContactsList.filter { contact ->
                contact.name.contains(trimmed, ignoreCase = true) ||
                    contact.number.contains(trimmed) ||
                    (normalizedQuery.isNotEmpty() && contact.number.replace(" ", "").contains(normalizedQuery))
            }
        }
        contactAdapter.updateData(filteredList)
        showEmptyState(filteredList.isEmpty())
    }

    private var contactsObserver: ContentObserver? = null

    private fun registerContactsObserver() {
        if (contactsObserver != null) return
        val appContext = context?.applicationContext ?: return
        if (!hasReadContactsPermission()) return

        contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Contacts change detected via ContentObserver: uri=$uri")
                if (isAdded && hasReadContactsPermission()) {
                    fetchContacts()
                }
            }
        }

        try {
            appContext.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                contactsObserver!!
            )
            Log.d(TAG, "Registered Contacts ContentObserver in ContactFragment")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Contacts ContentObserver: ${e.message}", e)
        }
    }

    private fun unregisterContactsObserver() {
        contactsObserver?.let { observer ->
            try {
                context?.applicationContext?.contentResolver?.unregisterContentObserver(observer)
                Log.d(TAG, "Unregistered Contacts ContentObserver in ContactFragment")
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
            fetchContacts()
        }
    }

    private fun setupRecyclerView() {
        contactAdapter = ContactAdapter(
            onPermissionNeeded = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
                )
            },
            onViewDetails = { item ->
                showContactDetailsDialog(item)
            },
            onToggleFavorite = { item ->
                toggleStarredContact(item)
            }
        )

        binding.contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactAdapter
            addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            )
        }
    }

    private fun hasReadContactsPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        if (hasReadContactsPermission()) {
            fetchContacts()
        } else {
            showEmptyState(true)
            permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            )
        }
    }

    /**
     * Fetches all contacts from ContactsContract.CommonDataKinds.Phone.CONTENT_URI
     * on an IO thread and updates the adapter safely on the Main Thread.
     */
    private fun fetchContacts() {
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
                    ContactsContract.CommonDataKinds.Phone.STARRED
                )

                val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"

                val cursor: Cursor? = appContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )

                cursor?.use {
                    val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
                    val contactIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val starredIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                    while (it.moveToNext()) {
                        val id = if (idIndex != -1) it.getLong(idIndex) else 0L
                        val contactId = if (contactIdIndex != -1) it.getLong(contactIdIndex) else 0L
                        val name = if (nameIndex != -1) it.getString(nameIndex) ?: "" else ""
                        val number = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""
                        val isStarred = if (starredIndex != -1) it.getInt(starredIndex) == 1 else false

                        list.add(
                            ContactModel(
                                id = id,
                                contactId = contactId,
                                name = name,
                                number = number,
                                isStarred = isStarred
                            )
                        )
                    }
                }

                // Deduplicate identical contact & number copies (e.g. Google sync + WhatsApp sync)
                val uniqueList = list.distinctBy {
                    "${it.contactId}_${it.number.replace("[^0-9+]".toRegex(), "")}"
                }

                withContext(Dispatchers.Main) {
                    originalContactsList = uniqueList
                    val currentQuery = _binding?.searchContactEditText?.text?.toString().orEmpty()
                    filterContacts(currentQuery)
                }

            } catch (securityEx: SecurityException) {
                Log.e(TAG, "SecurityException reading contacts: ${securityEx.message}", securityEx)
                withContext(Dispatchers.Main) { showEmptyState(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching contacts: ${e.message}", e)
                withContext(Dispatchers.Main) { showEmptyState(true) }
            }
        }
    }

    /**
     * Toggles the starred/favorite status of a contact in the system ContactsContract.
     */
    private fun toggleStarredContact(item: ContactModel) {
        val appContext = context?.applicationContext ?: return
        val newStarredValue = if (item.isStarred) 0 else 1

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.WRITE_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val values = ContentValues().apply {
                        put(ContactsContract.Contacts.STARRED, newStarredValue)
                    }
                    appContext.contentResolver.update(
                        ContactsContract.Contacts.CONTENT_URI,
                        values,
                        "${ContactsContract.Contacts._ID} = ?",
                        arrayOf(item.contactId.toString())
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating starred contact status: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                val displayName = if (item.name.isNotBlank()) item.name else item.number
                val announcement = if (newStarredValue == 1) {
                    getString(R.string.added_to_favorites, displayName)
                } else {
                    getString(R.string.removed_from_favorites, displayName)
                }
                Toast.makeText(requireContext(), announcement, Toast.LENGTH_SHORT).show()
                binding.root.announceForAccessibility(announcement)
                fetchContacts()
            }
        }
    }

    private fun showContactDetailsDialog(item: ContactModel) {
        val displayName = if (item.name.isNotBlank()) item.name else getString(R.string.unknown_caller)
        val starredStatus = if (item.isStarred) "Yes" else "No"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_contact_details_title)
            .setMessage("Name: $displayName\nNumber: ${item.number}\nStarred Favorite: $starredStatus")
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
            binding.emptyContactsTextView.isVisible = isEmpty
            binding.contactsRecyclerView.isVisible = !isEmpty
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterContactsObserver()
        _binding = null
    }

    companion object {
        private const val TAG = "ContactFragment"
        fun newInstance(): ContactFragment = ContactFragment()
    }
}
