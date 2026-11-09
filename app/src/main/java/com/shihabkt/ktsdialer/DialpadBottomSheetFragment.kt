package com.shihabkt.ktsdialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.shihabkt.ktsdialer.databinding.FragmentDialpadBottomSheetBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accessible Dialpad Bottom Sheet supporting:
 * 1. Double Tap Mode: Standard Android button activation via double-tap.
 * 2. Single Tap Mode: Immediate typing on MotionEvent.ACTION_HOVER_ENTER.
 * 3. Lift Mode:
 *    - On MotionEvent.ACTION_HOVER_ENTER: ONLY announces the button's content description
 *      using view.announceForAccessibility(). Does NOT append the number.
 *    - On MotionEvent.ACTION_HOVER_EXIT: Appends the number to the dialer display exactly upon
 *      finger release on this key (with bounds check to prevent typing while sliding)
 *      and triggers short haptic feedback and audio.
 * 4. Letters Toggle: Dynamically sets contentDescription with or without letters based on user preference.
 * 5. Contact Suggestion: Shows matching system contact above dialed number display as user types.
 * 6. Bottom Action Row:
 *    - Left: 'Clear All' button to immediately clear the entire number.
 *    - Center: 'Call' button to initiate a call.
 *    - Right: 'Add to Contact' button (visible/enabled when the dialed number does not exist in contacts).
 */
class DialpadBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentDialpadBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val dialedNumber = StringBuilder()
    private var toneGenerator: ToneGenerator? = null
    private lateinit var settingsManager: SettingsManager

    // Shared global Handler and pending Runnable for Lift Mode debounce
    private val globalHandler = Handler(Looper.getMainLooper())
    private var pendingTypeRunnable: Runnable? = null

    private var loadedContacts: List<ContactModel> = emptyList()
    private var currentSuggestedContact: ContactModel? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialpadBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsManager = SettingsManager(requireContext())

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 70)
        } catch (_: Exception) {
            toneGenerator = null
        }

        setupDialpadKeys()
        setupDeleteButton()
        setupClearAllButton()
        setupCallButton()
        setupAddContactButton()
        setupContactSuggestion()
        loadSystemContacts()
    }

    /**
     * Wires the 'Clear All' button located on the left of the call action row.
     */
    private fun setupClearAllButton() {
        binding.btnClearAll.setOnClickListener {
            clearAllDigits()
        }
    }

    /**
     * Wires the 'Add to Contact' button located on the right of the call action row.
     */
    private fun setupAddContactButton() {
        binding.btnAddContact.setOnClickListener {
            val number = dialedNumber.toString().trim()
            if (number.isNotEmpty()) {
                try {
                    val insertIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, number)
                    }
                    startActivity(insertIntent)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Unable to open contacts app", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Sets up TextWatcher on dialed number display and click listener on the suggestion container.
     */
    private fun setupContactSuggestion() {
        binding.contactSuggestionContainer.setOnClickListener {
            val contact = currentSuggestedContact ?: return@setOnClickListener
            dialedNumber.clear()
            dialedNumber.append(contact.number)
            binding.numberDisplayTextView.text = contact.number

            binding.contactSuggestionContainer.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.contactSuggestionContainer.playSoundEffect(SoundEffectConstants.CLICK)
            binding.contactSuggestionContainer.announceForAccessibility(
                getString(R.string.suggestion_applied_announcement, contact.number)
            )
        }

        // Add TextWatcher to the dialed number display to dynamically query suggestions & update Add Contact button
        binding.numberDisplayTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateContactSuggestion(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Loads system contacts asynchronously to provide instant suggestions and check existence.
     */
    private fun loadSystemContacts() {
        val context = context ?: return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = mutableListOf<ContactModel>()
            try {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone._ID,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.STARRED
                )
                val cursor: Cursor? = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
                )
                cursor?.use {
                    val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
                    val contactIdIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val starredIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                    while (it.moveToNext()) {
                        val id = if (idIdx >= 0) it.getLong(idIdx) else 0L
                        val contactId = if (contactIdIdx >= 0) it.getLong(contactIdIdx) else 0L
                        val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                        val number = if (numberIdx >= 0) it.getString(numberIdx) ?: "" else ""
                        val starred = if (starredIdx >= 0) it.getInt(starredIdx) == 1 else false
                        list.add(ContactModel(id, contactId, name, number, starred))
                    }
                }
            } catch (_: Exception) {
            }

            withContext(Dispatchers.Main) {
                loadedContacts = list
                updateContactSuggestion(dialedNumber.toString())
            }
        }
    }

    /**
     * Matches currently dialed number against loaded contacts:
     * - Shows matching contact suggestion in the top suggestion area.
     * - Toggles 'Add to Contact' visibility: visible/enabled if number DOES NOT exist in contacts, hidden if it exists.
     */
    private fun updateContactSuggestion(query: String) {
        val cleanQuery = query.filter { it.isDigit() || it == '+' }
        if (cleanQuery.isEmpty()) {
            binding.contactSuggestionContainer.visibility = View.GONE
            currentSuggestedContact = null
            binding.btnAddContact.visibility = View.GONE
            binding.btnAddContact.isEnabled = false
            return
        }

        // 1. Suggestion matching
        val matchingContact = if (loadedContacts.isNotEmpty()) {
            loadedContacts.firstOrNull { contact ->
                val cleanContactNumber = contact.number.filter { it.isDigit() || it == '+' }
                cleanContactNumber.contains(cleanQuery) || cleanContactNumber.endsWith(cleanQuery)
            }
        } else null

        if (matchingContact != null) {
            currentSuggestedContact = matchingContact
            binding.contactSuggestionContainer.visibility = View.VISIBLE
            binding.contactSuggestionTextView.text = "${matchingContact.name} • ${matchingContact.number}"
            binding.contactSuggestionContainer.contentDescription = getString(
                R.string.suggestion_content_description,
                matchingContact.name,
                matchingContact.number
            )
        } else {
            binding.contactSuggestionContainer.visibility = View.GONE
            currentSuggestedContact = null
        }

        // 2. 'Add to Contact' check:
        // If the currently dialed full number already exists in system contacts -> hide it.
        // If it DOES NOT exist -> make 'Add to Contact' visible and enabled.
        val exactNumberExists = if (loadedContacts.isNotEmpty()) {
            loadedContacts.any { contact ->
                val cleanContact = contact.number.filter { it.isDigit() || it == '+' }
                cleanContact == cleanQuery ||
                        (cleanQuery.length >= 7 && cleanContact.endsWith(cleanQuery)) ||
                        (cleanContact.length >= 7 && cleanQuery.endsWith(cleanContact))
            }
        } else false

        if (exactNumberExists) {
            binding.btnAddContact.visibility = View.GONE
            binding.btnAddContact.isEnabled = false
        } else {
            binding.btnAddContact.visibility = View.VISIBLE
            binding.btnAddContact.isEnabled = true
        }
    }

    private fun setupDialpadKeys() {
        val tapMode = settingsManager.dialpadTapMode
        val announceLetters = settingsManager.announceLettersWithNumbers

        // Configure all standard keys (0-9, *, #)
        configureKey(binding.btn1, "1", "1", "1", ToneGenerator.TONE_DTMF_1, tapMode, announceLetters)
        configureKey(binding.btn2, "2", "2, A B C", "2", ToneGenerator.TONE_DTMF_2, tapMode, announceLetters)
        configureKey(binding.btn3, "3", "3, D E F", "3", ToneGenerator.TONE_DTMF_3, tapMode, announceLetters)
        configureKey(binding.btn4, "4", "4, G H I", "4", ToneGenerator.TONE_DTMF_4, tapMode, announceLetters)
        configureKey(binding.btn5, "5", "5, J K L", "5", ToneGenerator.TONE_DTMF_5, tapMode, announceLetters)
        configureKey(binding.btn6, "6", "6, M N O", "6", ToneGenerator.TONE_DTMF_6, tapMode, announceLetters)
        configureKey(binding.btn7, "7", "7, P Q R S", "7", ToneGenerator.TONE_DTMF_7, tapMode, announceLetters)
        configureKey(binding.btn8, "8", "8, T U V", "8", ToneGenerator.TONE_DTMF_8, tapMode, announceLetters)
        configureKey(binding.btn9, "9", "9, W X Y Z", "9", ToneGenerator.TONE_DTMF_9, tapMode, announceLetters)
        configureKey(binding.btn0, "0", "0, plus on long press", "0", ToneGenerator.TONE_DTMF_0, tapMode, announceLetters)
        configureKey(binding.btnStar, "*", getString(R.string.key_star), getString(R.string.key_star), ToneGenerator.TONE_DTMF_S, tapMode, announceLetters)
        configureKey(binding.btnHash, "#", getString(R.string.key_hash), getString(R.string.key_hash), ToneGenerator.TONE_DTMF_P, tapMode, announceLetters)

        // Long press on 0 types '+'
        binding.btn0.setOnLongClickListener {
            appendDigit("+", ToneGenerator.TONE_DTMF_0, binding.btn0)
            true
        }
    }

    /**
     * Configures a dialpad number key:
     * - Dynamically assigns contentDescription depending on announceLetters preference.
     * - Configures OnClickListener and OnHoverListener according to tap mode:
     *   - Double Tap Mode: standard OnClickListener.
     *   - Single Tap Mode: appends digit on ACTION_HOVER_ENTER.
     *   - Lift Mode:
     *       * ACTION_HOVER_ENTER: ONLY announces button contentDescription using view.announceForAccessibility().
     *         Does NOT append the number.
     *       * ACTION_HOVER_EXIT: Appends number exactly on release over the key (ignoring sliding exits),
     *         triggering haptic feedback and sound.
     */
    private fun configureKey(
        button: View,
        digit: String,
        lettersDescription: String,
        numberOnlyDescription: String,
        tone: Int,
        tapMode: String,
        announceLetters: Boolean
    ) {
        val description = if (announceLetters) lettersDescription else numberOnlyDescription
        button.contentDescription = description

        // Standard click listener for double tap mode or accessibility activations
        button.setOnClickListener {
            appendDigit(digit, tone, button)
        }

        when (tapMode) {
            SettingsManager.MODE_SINGLE_TAP -> {
                button.setOnHoverListener { v, event ->
                    if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                        appendDigit(digit, tone, v)
                        true
                    } else {
                        false
                    }
                }
            }
            SettingsManager.MODE_LIFT_MODE -> {
                button.setOnHoverListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_HOVER_ENTER -> {
                            // Immediately call pendingTypeRunnable?.let { globalHandler.removeCallbacks(it) } to strictly cancel any pending type action
                            pendingTypeRunnable?.let { globalHandler.removeCallbacks(it) }
                            pendingTypeRunnable = null

                            // Then, call view.announceForAccessibility()
                            val announceText = v.contentDescription?.toString() ?: digit
                            v.announceForAccessibility(announceText)
                            true
                        }
                        MotionEvent.ACTION_HOVER_EXIT -> {
                            // Cancel any existing pending runnable before scheduling a new one
                            pendingTypeRunnable?.let { globalHandler.removeCallbacks(it) }

                            // Assign pendingTypeRunnable to a new Runnable that appends the current button's number and triggers haptic feedback
                            pendingTypeRunnable = Runnable {
                                appendDigit(digit, tone, v)
                                pendingTypeRunnable = null
                            }

                            // Post it: globalHandler.postDelayed(pendingTypeRunnable!!, 150)
                            globalHandler.postDelayed(pendingTypeRunnable!!, 150L)
                            true
                        }
                        else -> false
                    }
                }
            }
            else -> {
                // Standard Double Tap Mode: no custom hover listener
                button.setOnHoverListener(null)
            }
        }
    }

    private fun appendDigit(char: String, tone: Int, sourceView: View) {
        dialedNumber.append(char)
        binding.numberDisplayTextView.text = dialedNumber.toString()

        // Haptic feedback and click / DTMF audio feedback
        sourceView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        sourceView.playSoundEffect(SoundEffectConstants.CLICK)
        toneGenerator?.startTone(tone, 120)
    }

    private fun setupDeleteButton() {
        binding.btnDelete.setOnClickListener {
            deleteLastDigit()
        }

        binding.btnDelete.setOnLongClickListener {
            clearAllDigits()
            true
        }

        // Add custom accessibility action for 'Clear all digits'
        ViewCompat.setAccessibilityDelegate(binding.btnDelete, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_clear_all,
                        getString(R.string.action_clear_all)
                    )
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == R.id.action_clear_all) {
                    clearAllDigits()
                    return true
                }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    private fun deleteLastDigit() {
        if (dialedNumber.isNotEmpty()) {
            // 1. Safely extract that last character before removing
            val deletedChar = dialedNumber.last()

            // 2. Remove the character and update the dialer display TextView
            dialedNumber.deleteCharAt(dialedNumber.length - 1)
            binding.numberDisplayTextView.text = dialedNumber.toString()

            binding.btnDelete.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.btnDelete.playSoundEffect(SoundEffectConstants.CLICK)

            // 3. Immediately announce for accessibility
            binding.btnDelete.announceForAccessibility("Deleted $deletedChar")
        }
    }

    private fun clearAllDigits() {
        if (dialedNumber.isNotEmpty()) {
            dialedNumber.clear()
            binding.numberDisplayTextView.text = ""
            binding.btnDelete.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            binding.btnDelete.announceForAccessibility(getString(R.string.action_clear_all))
        }
    }

    private fun setupCallButton() {
        // 1. Standard single tap: initiate call using default SIM via ACTION_CALL
        binding.btnCall.setOnClickListener {
            initiateCall(simIndex = null)
        }

        // 2. Dual SIM custom accessibility actions for TalkBack & Jieshuo
        ViewCompat.setAccessibilityDelegate(binding.btnCall, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_call_sim_1,
                        getString(R.string.action_call_sim_1)
                    )
                )
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_call_sim_2,
                        getString(R.string.action_call_sim_2)
                    )
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                return when (action) {
                    R.id.action_call_sim_1 -> {
                        initiateCall(simIndex = 0)
                        true
                    }
                    R.id.action_call_sim_2 -> {
                        initiateCall(simIndex = 1)
                        true
                    }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        })
    }

    /**
     * Initiates a call to the dialed number.
     * @param simIndex null for default SIM, 0 for SIM 1, 1 for SIM 2.
     */
    private fun initiateCall(simIndex: Int?) {
        val number = dialedNumber.toString().trim()
        if (number.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.dialpad_empty_number_message),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCallPermission) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
            startActivity(dialIntent)
            dismiss()
            return
        }

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))

        if (simIndex != null) {
            val hasPhoneStatePermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPhoneStatePermission) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.permission_phone_state_required),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                try {
                    val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    val accounts: List<PhoneAccountHandle>? = telecomManager?.getCallCapablePhoneAccounts()

                    if (accounts != null && accounts.size > simIndex) {
                        val handle = accounts[simIndex]
                        callIntent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.sim_not_available),
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                } catch (e: SecurityException) {
                    Log.e("DialpadBottomSheet", "SecurityException fetching PhoneAccountHandles", e)
                }
            }
        }

        try {
            startActivity(callIntent)
            dismiss()
        } catch (_: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
            startActivity(dialIntent)
            dismiss()
        }
    }

    override fun onDestroyView() {
        pendingTypeRunnable?.let { globalHandler.removeCallbacks(it) }
        pendingTypeRunnable = null
        toneGenerator?.release()
        toneGenerator = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): DialpadBottomSheetFragment = DialpadBottomSheetFragment()
    }
}
