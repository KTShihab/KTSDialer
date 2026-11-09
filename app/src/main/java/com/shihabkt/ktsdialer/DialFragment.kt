package com.shihabkt.ktsdialer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shihabkt.ktsdialer.databinding.FragmentDialBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment displaying recent call logs with accessible filter mechanisms.
 * Follows strict data separation:
 * 1. originalCallLogs holds the master list and is NEVER modified by filtering.
 * 2. Filtering creates a brand new list from originalCallLogs.
 * 3. Updates are sent via adapter.updateData(filteredList) strictly on the Main Thread.
 */
class DialFragment : Fragment() {

    private var _binding: FragmentDialBinding? = null
    private val binding get() = _binding!!

    private lateinit var callLogAdapter: CallLogAdapter

    // Rule 1: Master fetched call logs stored separately; never modified during filtering
    private var originalCallLogs: List<CallLogModel> = emptyList()
    private var systemContacts: List<ContactModel> = emptyList()

    private var currentFilterOption: String = ""
    private var currentSearchQuery: String = ""

    // Telephony & Contacts permissions
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readLogGranted = permissions[Manifest.permission.READ_CALL_LOG] == true
        val callPhoneGranted = permissions[Manifest.permission.CALL_PHONE] == true
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] == true
        val readContactsGranted = permissions[Manifest.permission.READ_CONTACTS] == true

        if (readLogGranted && callPhoneGranted) {
            registerCallLogObserver()
            fetchCallLogs()
            if (readContactsGranted) {
                fetchContactsInBackground()
            }
        } else {
            showEmptyState(true)
            if (!readLogGranted) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.permission_call_log_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (!callPhoneGranted) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.call_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentFilterOption = getString(R.string.filter_all_calls)

        setupRecyclerView()
        setupSearchBar()
        setupFilterSpinner()
        setupDialpadFab()
        checkAndRequestPermissions()
    }

    private fun setupSearchBar() {
        binding.searchCallLogEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString().orEmpty()
                applyFilter(currentFilterOption, announce = false)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupDialpadFab() {
        binding.fabOpenDialpad.setOnClickListener {
            DialpadBottomSheetFragment.newInstance().show(childFragmentManager, "DialpadBottomSheet")
        }
    }

    private fun setupRecyclerView() {
        callLogAdapter = CallLogAdapter(
            onPermissionNeeded = {
                permissionLauncher.launch(requiredPermissions)
            },
            onViewDetails = { item ->
                showCallDetailsDialog(item)
            },
            onViewCallHistory = { item ->
                showCallHistoryDialog(item)
            },
            onFavoriteToggled = { _, _ ->
                callLogAdapter.notifyDataSetChanged()
            },
            onSendMessage = { item ->
                val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${item.number}")
                }
                startActivity(smsIntent)
            },
            onDeleteFromLog = { item ->
                deleteCallLogEntry(item)
            }
        )

        binding.callLogRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callLogAdapter
            addItemDecoration(
                DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
            )
        }
    }

    /**
     * Configures the filter Spinner with accessible dropdown layouts and TalkBack labelling.
     */
    private fun setupFilterSpinner() {
        val filterOptions = listOf(
            getString(R.string.filter_all_calls),
            getString(R.string.filter_missed),
            getString(R.string.filter_incoming),
            getString(R.string.filter_outgoing)
        )

        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_filter,
            R.id.spinnerText,
            filterOptions
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_filter_dropdown)
        }

        binding.filterSpinner.adapter = spinnerAdapter
        binding.filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedOption = filterOptions[position]
                applyFilter(selectedOption, announce = true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Rule 2 & 4: Filters originalCallLogs into a new list and updates adapter on the Main Thread.
     * Combines both call-type selection and real-time name/number search filtering.
     */
    private fun applyFilter(selectedOption: String, announce: Boolean = false) {
        currentFilterOption = selectedOption

        // 1. Filter by call type
        val typeFilteredList = when (selectedOption) {
            getString(R.string.filter_missed) -> {
                originalCallLogs.filter {
                    it.callType.equals("Missed", ignoreCase = true) ||
                    it.callType.equals("Rejected", ignoreCase = true)
                }
            }
            getString(R.string.filter_incoming) -> {
                originalCallLogs.filter {
                    it.callType.equals("Incoming", ignoreCase = true)
                }
            }
            getString(R.string.filter_outgoing) -> {
                originalCallLogs.filter {
                    it.callType.equals("Outgoing", ignoreCase = true)
                }
            }
            else -> {
                originalCallLogs
            }
        }

        // 2. Filter by search query (name or phone number)
        val trimmed = currentSearchQuery.trim()
        val filteredList = if (trimmed.isEmpty()) {
            typeFilteredList
        } else {
            val normalizedQuery = trimmed.replace(" ", "")

            // Preference 1: Matching call logs
            val matchingCallLogs = typeFilteredList.filter { item ->
                val nameMatches = item.name?.contains(trimmed, ignoreCase = true) == true
                val numberMatches = item.number.contains(trimmed) ||
                        (normalizedQuery.isNotEmpty() && item.number.replace(" ", "").contains(normalizedQuery))
                nameMatches || numberMatches
            }

            // Preference 2: Matching contacts not already present in matching call logs
            val existingNormalizedNumbers = matchingCallLogs.map {
                it.number.replace("[^0-9+]".toRegex(), "")
            }.toSet()

            val matchingContacts = systemContacts.filter { contact ->
                val nameMatches = contact.name.contains(trimmed, ignoreCase = true)
                val numberMatches = contact.number.contains(trimmed) ||
                        (normalizedQuery.isNotEmpty() && contact.number.replace(" ", "").contains(normalizedQuery))
                nameMatches || numberMatches
            }.filterNot { contact ->
                existingNormalizedNumbers.contains(contact.number.replace("[^0-9+]".toRegex(), ""))
            }.map { contact ->
                CallLogItem(
                    id = -(contact.contactId.takeIf { it > 0 } ?: contact.id),
                    name = contact.name,
                    number = contact.number,
                    time = "Contact",
                    callType = "Contact",
                    duration = "",
                    simInfo = ""
                )
            }

            matchingCallLogs + matchingContacts
        }

        // Rule 4: Update UI safely on the Main Thread
        requireActivity().runOnUiThread {
            if (_binding != null) {
                callLogAdapter.updateData(filteredList)
                showEmptyState(filteredList.isEmpty())

                if (announce && isAdded) {
                    val announcement = getString(
                        R.string.filter_result_announcement,
                        selectedOption,
                        filteredList.size
                    )
                    binding.root.announceForAccessibility(announcement)
                }
            }
        }
    }

    private val resumeRefreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable {
        if (isAdded && hasRequiredPermissions()) {
            fetchCallLogs()
        }
    }

    private var callLogObserver: ContentObserver? = null

    private fun hasRequiredPermissions(): Boolean {
        val context = context ?: return false
        val readLogGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        val callPhoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val phoneStateGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return readLogGranted && callPhoneGranted && phoneStateGranted
    }

    private fun registerCallLogObserver() {
        if (callLogObserver != null) return
        val appContext = context?.applicationContext ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Call log change detected via ContentObserver: uri=$uri")
                if (isAdded && hasRequiredPermissions()) {
                    fetchCallLogs()
                }
            }
        }

        try {
            appContext.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                callLogObserver!!
            )
            Log.d(TAG, "Registered CallLog ContentObserver successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register CallLog ContentObserver: ${e.message}", e)
        }
    }

    private fun unregisterCallLogObserver() {
        callLogObserver?.let { observer ->
            try {
                context?.applicationContext?.contentResolver?.unregisterContentObserver(observer)
                Log.d(TAG, "Unregistered CallLog ContentObserver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister CallLog ContentObserver: ${e.message}", e)
            }
            callLogObserver = null
        }
    }

    private fun fetchContactsInBackground() {
        val appContext = context?.applicationContext ?: return
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = mutableListOf<ContactModel>()
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone._ID,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.STARRED
                )
                val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
                val cursor = appContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )
                cursor?.use {
                    val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
                    val cIdIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val starIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                    while (it.moveToNext()) {
                        val id = if (idIdx != -1) it.getLong(idIdx) else 0L
                        val contactId = if (cIdIdx != -1) it.getLong(cIdIdx) else 0L
                        val name = if (nameIdx != -1) it.getString(nameIdx) ?: "" else ""
                        val number = if (numIdx != -1) it.getString(numIdx) ?: "" else ""
                        val isStarred = if (starIdx != -1) it.getInt(starIdx) == 1 else false
                        list.add(ContactModel(id, contactId, name, number, isStarred))
                    }
                }
                systemContacts = list.distinctBy {
                    "${it.contactId}_${it.number.replace("[^0-9+]".toRegex(), "")}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching background contacts for search: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasRequiredPermissions()) {
            registerCallLogObserver()
            fetchCallLogs()
            fetchContactsInBackground()
            // Schedule safety refresh in case of telephony provider write latency
            resumeRefreshHandler.removeCallbacks(refreshRunnable)
            resumeRefreshHandler.postDelayed(refreshRunnable, 1000)
        }
    }

    override fun onPause() {
        super.onPause()
        resumeRefreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun checkAndRequestPermissions() {
        if (hasRequiredPermissions()) {
            registerCallLogObserver()
            fetchCallLogs()
            fetchContactsInBackground()
        } else {
            showEmptyState(true)
            permissionLauncher.launch(requiredPermissions)
        }
    }

    /**
     * Action 1: 'View Details'
     */
    private fun showCallDetailsDialog(item: CallLogModel) {
        val context = requireContext()
        val displayName = if (!item.name.isNullOrBlank()) item.name else getString(R.string.unknown_caller)

        val message = if (item.callType.equals("Contact", ignoreCase = true)) {
            buildString {
                append("Name: ").append(displayName).append("\n")
                append("Number: ").append(item.number).append("\n")
                append("Source: Saved Contact")
            }
        } else {
            buildString {
                append("Name: ").append(displayName).append("\n")
                append("Number: ").append(item.number).append("\n")
                if (item.simInfo.isNotBlank()) {
                    append("SIM: ").append(item.simInfo).append("\n")
                }
                append("Date & Time: ").append(item.time).append("\n")
                append("Call Type: ").append(item.callType).append("\n")
                append("Duration: ").append(item.duration)
            }
        }

        val recordings = CallRecorder.getRecordingsForNumber(context, item.number, item.name)
        val detailsMessage = if (recordings.isNotEmpty()) {
            "$message\n\nRecordings: ${recordings.size} available"
        } else {
            message
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_details_title)
            .setMessage(detailsMessage)
            .setPositiveButton(R.string.dialog_close, null)

        if (recordings.isNotEmpty()) {
            val playBtnText = if (recordings.size > 1) {
                "${getString(R.string.btn_play_recording)} (${recordings.size})"
            } else {
                getString(R.string.btn_play_recording)
            }
            builder.setNeutralButton(playBtnText) { _, _ ->
                RecordingPlayerDialog.showRecordings(context, recordings, displayName)
            }
            builder.setNegativeButton(R.string.btn_share_recording) { _, _ ->
                CallRecorder.shareRecording(context, recordings.first())
            }
        } else {
            val social = SocialMessenger.checkSocialAvailability(
                context = context,
                contactId = null,
                rawNumber = item.number
            )
            if (social.hasWhatsApp) {
                builder.setNeutralButton("WhatsApp") { _, _ ->
                    SocialMessenger.openWhatsApp(context, item.number)
                }
            }
            if (social.hasTelegram) {
                builder.setNegativeButton("Telegram") { _, _ ->
                    SocialMessenger.openTelegram(context, item.number)
                }
            }
        }

        builder.create()
            .apply {
                setOnShowListener {
                    window?.decorView?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                }
            }
            .show()
    }

    /**
     * Action 2: 'View Call History'
     */
    private fun showCallHistoryDialog(item: CallLogModel) {
        val context = requireContext()
        val targetNumber = item.number
        val displayName = if (!item.name.isNullOrBlank()) item.name else targetNumber

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val historyList = mutableListOf<String>()

            try {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CALL_LOG
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val projection = arrayOf(
                        CallLog.Calls.DATE,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.NUMBER
                    )
                    val sortOrder = "${CallLog.Calls.DATE} DESC"

                    val cursor: Cursor? = context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        projection,
                        null,
                        null,
                        sortOrder
                    )

                    cursor?.use {
                        val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                        val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
                        val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                        val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)

                        while (it.moveToNext() && historyList.size < 50) {
                            val rowNumber = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""
                            if (PhoneNumberUtils.compare(context, rowNumber, targetNumber) || rowNumber == targetNumber) {
                                val dateMillis = if (dateIndex != -1) it.getLong(dateIndex) else 0L
                                val typeCode = if (typeIndex != -1) it.getInt(typeIndex) else 0
                                val durationSec = if (durationIndex != -1) it.getLong(durationIndex) else 0L

                                val formattedTime = formatCallDate(context, dateMillis)
                                val callTypeString = mapCallType(typeCode)
                                val formattedDuration = formatDuration(durationSec)

                                historyList.add("$formattedTime\n$callTypeString • $formattedDuration")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching call history for number: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                val dialogTitle = getString(R.string.dialog_call_history_title, displayName)

                if (historyList.isEmpty()) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(dialogTitle)
                        .setMessage(R.string.no_call_history_found)
                        .setPositiveButton(R.string.dialog_close, null)
                        .show()
                } else {
                    val itemsArray = historyList.toTypedArray()
                    MaterialAlertDialogBuilder(context)
                        .setTitle(dialogTitle)
                        .setItems(itemsArray) { _, _ -> }
                        .setPositiveButton(R.string.dialog_close, null)
                        .create()
                        .apply {
                            setOnShowListener {
                                window?.decorView?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                            }
                        }
                        .show()
                }
            }
        }
    }

    /**
     * Action 3: 'Add to Favorite'
     * Stars the contact in the Android system contacts using ContactsContract.
     */
    private fun addToFavorites(item: CallLogModel) {
        val displayName = if (!item.name.isNullOrBlank()) item.name else item.number
        val context = requireContext()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var contactFound = false
            try {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val uri = Uri.withAppendedPath(
                        android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(item.number)
                    )
                    val projection = arrayOf(android.provider.ContactsContract.PhoneLookup._ID)
                    val cursor = context.contentResolver.query(uri, projection, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val contactId = it.getLong(0)
                            val values = android.content.ContentValues().apply {
                                put(android.provider.ContactsContract.Contacts.STARRED, 1)
                            }
                            context.contentResolver.update(
                                android.provider.ContactsContract.Contacts.CONTENT_URI,
                                values,
                                "${android.provider.ContactsContract.Contacts._ID} = ?",
                                arrayOf(contactId.toString())
                            )
                            contactFound = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starring contact from call log: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                val announcementText = getString(R.string.added_to_favorites, displayName)
                Toast.makeText(context, announcementText, Toast.LENGTH_SHORT).show()
                binding.root.announceForAccessibility(announcementText)
            }
        }
    }

    /**
     * Rule 1: Fetches call logs and populates originalCallLogs.
     */
    fun fetchCallLogs() {
        val appContext = context?.applicationContext ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val logs = mutableListOf<CallLogModel>()

            try {
                if (ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.READ_CALL_LOG
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(Dispatchers.Main) {
                        showEmptyState(true)
                    }
                    return@launch
                }

                // 1. Fetch active subscriptions and call-capable phone accounts
                var telecomManager: TelecomManager? = null
                var activeSubscriptions: List<SubscriptionInfo>? = null
                var callCapableHandles: List<PhoneAccountHandle>? = null

                if (ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        val subManager = appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                        activeSubscriptions = subManager?.activeSubscriptionInfoList

                        telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                        callCapableHandles = telecomManager?.getCallCapablePhoneAccounts()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching active subscriptions or telecom accounts: ${e.message}", e)
                    }
                }

                val projection = arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_ID
                )

                val sortOrder = "${CallLog.Calls.DATE} DESC"

                val cursor: Cursor? = appContext.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )

                cursor?.use {
                    val idIndex = it.getColumnIndex(CallLog.Calls._ID)
                    val nameIndex = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
                    val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                    val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)
                    val accountIdIndex = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                    while (it.moveToNext() && logs.size < 100) {
                        val id = if (idIndex != -1) it.getLong(idIndex) else 0L
                        val name = if (nameIndex != -1) it.getString(nameIndex) else null
                        val number = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""
                        val dateMillis = if (dateIndex != -1) it.getLong(dateIndex) else 0L
                        val typeCode = if (typeIndex != -1) it.getInt(typeIndex) else 0
                        val durationSec = if (durationIndex != -1) it.getLong(durationIndex) else 0L
                        val phoneAccountId = if (accountIdIndex != -1) it.getString(accountIdIndex) else null

                        val formattedTime = formatCallDate(appContext, dateMillis)
                        val callTypeString = mapCallType(typeCode)
                        val formattedDuration = formatDuration(durationSec)
                        val simInfo = resolveSimInfo(phoneAccountId, telecomManager, activeSubscriptions, callCapableHandles)

                        logs.add(
                            CallLogItem(
                                id = id,
                                name = name,
                                number = number,
                                time = formattedTime,
                                callType = callTypeString,
                                duration = formattedDuration,
                                simInfo = simInfo
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    // Assign to immutable/separate originalCallLogs master list
                    originalCallLogs = logs.toList()
                    applyFilter(currentFilterOption, announce = false)
                }

            } catch (securityEx: SecurityException) {
                Log.e(TAG, "SecurityException while accessing call logs: ${securityEx.message}", securityEx)
                withContext(Dispatchers.Main) {
                    showEmptyState(true)
                    context?.let { ctx ->
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.permission_call_log_required),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Unexpected error fetching call logs: ${ex.message}", ex)
                withContext(Dispatchers.Main) {
                    showEmptyState(true)
                }
            }
        }
    }

    private fun deleteCallLogEntry(item: CallLogModel) {
        if (item.id <= 0) return
        originalCallLogs = originalCallLogs.filterNot { it.id == item.id }
        applyFilter(currentFilterOption, announce = false)

        val displayName = item.name ?: item.number
        binding.root.announceForAccessibility("$displayName deleted from call log")

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uri = ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, item.id)
                context?.contentResolver?.delete(uri, null, null)
            } catch (ignored: Exception) {
                // Handled gracefully if WRITE_CALL_LOG is not available
            }
        }
    }

    private fun formatCallDate(context: Context, epochMillis: Long): String {
        return if (DateUtils.isToday(epochMillis)) {
            val timeFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
            "Today, " + timeFormat.format(Date(epochMillis))
        } else {
            val fullDateFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.ENGLISH)
            fullDateFormat.format(Date(epochMillis))
        }
    }

    private fun mapCallType(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE -> "Incoming"
            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
            CallLog.Calls.MISSED_TYPE -> "Missed"
            CallLog.Calls.REJECTED_TYPE -> "Rejected"
            else -> "Incoming"
        }
    }

    /**
     * Resolves the SIM slot for a call log entry:
     * 1. Matches CallLog.Calls.PHONE_ACCOUNT_ID with TelecomManager.getCallCapablePhoneAccounts() handle.id
     *    or SubscriptionInfo.subscriptionId / iccId.
     * 2. Inspects telecomManager.getPhoneAccount(handle) label or SubscriptionInfo.simSlotIndex:
     *    - simSlotIndex == 0 -> "SIM 1"
     *    - simSlotIndex == 1 -> "SIM 2"
     * 3. Fallback: If mapping fails entirely, returns "Unknown SIM" (never blank).
     */
    private fun resolveSimInfo(
        phoneAccountId: String?,
        telecomManager: TelecomManager?,
        activeSubscriptions: List<SubscriptionInfo>?,
        callCapableHandles: List<PhoneAccountHandle>?
    ): String {
        if (phoneAccountId.isNullOrBlank()) {
            return "Unknown SIM"
        }

        val trimmedAccountId = phoneAccountId.trim()

        try {
            // Step 2 & 3: Match PHONE_ACCOUNT_ID exactly with handle.id from TelecomManager.getCallCapablePhoneAccounts()
            if (callCapableHandles != null && telecomManager != null) {
                val matchedHandleIndex = callCapableHandles.indexOfFirst {
                    it.id == trimmedAccountId || trimmedAccountId.contains(it.id) || it.id.contains(trimmedAccountId)
                }

                if (matchedHandleIndex != -1) {
                    val handle = callCapableHandles[matchedHandleIndex]

                    // Step 4: Use telecomManager.getPhoneAccount(handle) to determine SIM
                    val phoneAccount = try {
                        telecomManager.getPhoneAccount(handle)
                    } catch (_: SecurityException) {
                        null
                    }

                    val label = phoneAccount?.label?.toString().orEmpty()
                    if (label.contains("SIM 1", ignoreCase = true) || label.contains("Slot 1", ignoreCase = true)) {
                        return "SIM 1"
                    }
                    if (label.contains("SIM 2", ignoreCase = true) || label.contains("Slot 2", ignoreCase = true)) {
                        return "SIM 2"
                    }

                    // Check SubscriptionManager slot index for this handle
                    if (!activeSubscriptions.isNullOrEmpty()) {
                        val sub = activeSubscriptions.firstOrNull {
                            it.subscriptionId.toString() == handle.id ||
                                    (it.iccId != null && (handle.id.contains(it.iccId) || it.iccId.contains(handle.id)))
                        }
                        if (sub != null) {
                            // Step 5: If simSlotIndex == 0 -> 'SIM 1'. If simSlotIndex == 1 -> 'SIM 2'
                            return when (sub.simSlotIndex) {
                                0 -> "SIM 1"
                                1 -> "SIM 2"
                                else -> "SIM ${sub.simSlotIndex + 1}"
                            }
                        }
                    }

                    // Position fallback in callCapableHandles list (Slot 0 -> SIM 1, Slot 1 -> SIM 2)
                    return when (matchedHandleIndex) {
                        0 -> "SIM 1"
                        1 -> "SIM 2"
                        else -> "SIM ${matchedHandleIndex + 1}"
                    }
                }
            }

            // Direct check against active subscriptions if handle match was inconclusive
            if (!activeSubscriptions.isNullOrEmpty()) {
                val matchingSub = activeSubscriptions.firstOrNull {
                    it.subscriptionId.toString() == trimmedAccountId ||
                            (it.iccId != null && (trimmedAccountId.contains(it.iccId) || it.iccId.contains(trimmedAccountId)))
                }
                if (matchingSub != null) {
                    return when (matchingSub.simSlotIndex) {
                        0 -> "SIM 1"
                        1 -> "SIM 2"
                        else -> "SIM ${matchingSub.simSlotIndex + 1}"
                    }
                }
            }

            // Fallback for direct slot indices
            if (trimmedAccountId == "0") return "SIM 1"
            if (trimmedAccountId == "1") {
                val isSlot1 = activeSubscriptions?.any { it.simSlotIndex == 1 && it.subscriptionId.toString() == "1" } == true
                return if (isSlot1) "SIM 2" else "SIM 1"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error resolving SIM info: ${e.message}", e)
        }

        // Step 6: Crucial Fallback: If mapping fails entirely, return 'Unknown SIM'
        return "Unknown SIM"
    }

    private fun formatDuration(durationSeconds: Long): String {
        val minutes = durationSeconds / 60
        val remainingSeconds = durationSeconds % 60
        return when {
            minutes > 0 && remainingSeconds > 0 -> "$minutes min $remainingSeconds sec"
            minutes > 0 -> "$minutes min"
            else -> "$remainingSeconds sec"
        }
    }

    private fun showEmptyState(isEmpty: Boolean) {
        if (_binding != null) {
            binding.emptyStateTextView.isVisible = isEmpty
            binding.callLogRecyclerView.isVisible = !isEmpty
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterCallLogObserver()
        resumeRefreshHandler.removeCallbacks(refreshRunnable)
        _binding = null
    }

    companion object {
        private const val TAG = "DialFragment"
        fun newInstance(): DialFragment = DialFragment()
    }
}
