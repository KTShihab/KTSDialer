package com.shihabkt.ktsdialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.util.LruCache
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Utility for launching direct messaging in WhatsApp and multi-client Telegram.
 * Supports selecting between multiple installed Telegram clients (BlindGram, Nekogram, Telegram, etc.)
 * and checking whether a contact or phone number has an active account on WhatsApp or Telegram.
 */
object SocialMessenger {

    data class TelegramClient(
        val packageName: String,
        val appName: String
    )

    data class SocialAvailability(
        val hasWhatsApp: Boolean,
        val hasTelegram: Boolean
    )

    // Cache of contact ID / phone number to social messaging availability
    private val socialCache = LruCache<String, SocialAvailability>(500)

    /**
     * Clears the cached social availability status (e.g. when contacts change).
     */
    fun clearCache() {
        synchronized(socialCache) {
            socialCache.evictAll()
        }
    }

    /**
     * Checks whether WhatsApp is installed on the device.
     */
    fun isWhatsAppInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo("com.whatsapp", 0)
            true
        } catch (_: Exception) {
            try {
                pm.getPackageInfo("com.whatsapp.w4b", 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Checks whether any Telegram client is installed on the device.
     */
    fun isTelegramInstalled(context: Context): Boolean {
        return getInstalledTelegramClients(context).isNotEmpty()
    }

    /**
     * Checks whether a contact or phone number has WhatsApp and/or Telegram registered in ContactsContract.
     *
     * @param context Context for querying ContentResolver.
     * @param contactId Optional contact ID if already known.
     * @param rawNumber Phone number string.
     * @return SocialAvailability indicating whether WhatsApp and Telegram accounts exist for this number.
     */
    fun checkSocialAvailability(
        context: Context,
        contactId: Long?,
        rawNumber: String
    ): SocialAvailability {
        val checkWhatsApp = isWhatsAppInstalled(context)
        val checkTelegram = isTelegramInstalled(context)

        // If neither app is installed on the phone, neither can be used
        if (!checkWhatsApp && !checkTelegram) {
            return SocialAvailability(hasWhatsApp = false, hasTelegram = false)
        }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return SocialAvailability(hasWhatsApp = false, hasTelegram = false)
        }

        val digits = rawNumber.replace("[^0-9]".toRegex(), "")
        val cacheKey = if (contactId != null && contactId > 0L) {
            "id_${contactId}_$digits"
        } else {
            "num_$digits"
        }

        synchronized(socialCache) {
            socialCache.get(cacheKey)?.let { return it }
        }

        var resolvedContactId = if (contactId != null && contactId > 0L) contactId else null
        if (resolvedContactId == null && rawNumber.isNotBlank()) {
            resolvedContactId = lookupContactIdByNumber(context, rawNumber)
        }

        var whatsAppFound = false
        var telegramFound = false

        if (resolvedContactId != null && resolvedContactId > 0L) {
            val contactIdStr = resolvedContactId.toString()

            // 1. Query RawContacts for account_type
            try {
                val rawUri = ContactsContract.RawContacts.CONTENT_URI
                val rawProj = arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE)
                val rawSel = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
                val rawArgs = arrayOf(contactIdStr)

                context.contentResolver.query(rawUri, rawProj, rawSel, rawArgs, null)?.use { cursor ->
                    val accIdx = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    while (cursor.moveToNext()) {
                        val accType = if (accIdx != -1) cursor.getString(accIdx)?.lowercase().orEmpty() else ""
                        if (checkWhatsApp && !whatsAppFound && accType.contains("whatsapp")) {
                            whatsAppFound = true
                        }
                        if (checkTelegram && !telegramFound && isTelegramIdentifier(accType)) {
                            telegramFound = true
                        }
                        if (whatsAppFound && telegramFound) break
                    }
                }
            } catch (_: Exception) {}

            // 2. Query Data for mimetype if either is still not found
            if ((checkWhatsApp && !whatsAppFound) || (checkTelegram && !telegramFound)) {
                try {
                    val dataUri = ContactsContract.Data.CONTENT_URI
                    val dataProj = arrayOf(ContactsContract.Data.MIMETYPE)
                    val dataSel = "${ContactsContract.Data.CONTACT_ID} = ?"
                    val dataArgs = arrayOf(contactIdStr)

                    context.contentResolver.query(dataUri, dataProj, dataSel, dataArgs, null)?.use { cursor ->
                        val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                        while (cursor.moveToNext()) {
                            val mime = if (mimeIdx != -1) cursor.getString(mimeIdx)?.lowercase().orEmpty() else ""
                            if (checkWhatsApp && !whatsAppFound && mime.contains("whatsapp")) {
                                whatsAppFound = true
                            }
                            if (checkTelegram && !telegramFound && isTelegramIdentifier(mime)) {
                                telegramFound = true
                            }
                            if (whatsAppFound && telegramFound) break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Fallback: Query Data directly by phone number suffix if contactId lookup yielded nothing
        if ((checkWhatsApp && !whatsAppFound) || (checkTelegram && !telegramFound)) {
            if (digits.length >= 7) {
                val searchSuffix = if (digits.length > 10) digits.takeLast(10) else digits
                try {
                    val dataUri = ContactsContract.Data.CONTENT_URI
                    val dataProj = arrayOf(ContactsContract.Data.MIMETYPE)
                    val dataSel = "${ContactsContract.Data.DATA1} LIKE ?"
                    val dataArgs = arrayOf("%$searchSuffix%")

                    context.contentResolver.query(dataUri, dataProj, dataSel, dataArgs, null)?.use { cursor ->
                        val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                        while (cursor.moveToNext()) {
                            val mime = if (mimeIdx != -1) cursor.getString(mimeIdx)?.lowercase().orEmpty() else ""
                            if (checkWhatsApp && !whatsAppFound && mime.contains("whatsapp")) {
                                whatsAppFound = true
                            }
                            if (checkTelegram && !telegramFound && isTelegramIdentifier(mime)) {
                                telegramFound = true
                            }
                            if (whatsAppFound && telegramFound) break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val result = SocialAvailability(
            hasWhatsApp = checkWhatsApp && whatsAppFound,
            hasTelegram = checkTelegram && telegramFound
        )

        synchronized(socialCache) {
            socialCache.put(cacheKey, result)
            if (resolvedContactId != null && resolvedContactId > 0L) {
                socialCache.put("id_$resolvedContactId", result)
            }
            if (digits.isNotBlank()) {
                socialCache.put("num_$digits", result)
            }
        }

        return result
    }

    private fun lookupContactIdByNumber(context: Context, rawNumber: String): Long? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(rawNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup._ID)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                    if (idx != -1) cursor.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isTelegramIdentifier(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("telegram") ||
            lower.contains("nekogram") ||
            lower.contains("blindgram") ||
            lower.contains("challegram") ||
            lower.contains("neko")
    }

    /**
     * Formats the phone number for WhatsApp with country code (defaults to India 91 for 10-digit numbers).
     */
    fun formatForWhatsApp(rawNumber: String): String {
        val digits = rawNumber.replace("[^0-9]".toRegex(), "")
        return when {
            digits.length == 10 -> "91$digits"
            digits.startsWith("0") && digits.length == 11 -> "91" + digits.substring(1)
            else -> digits
        }
    }

    /**
     * Opens direct chat with the specified number on WhatsApp.
     */
    fun openWhatsApp(context: Context, rawNumber: String) {
        val formattedNumber = formatForWhatsApp(rawNumber)
        if (formattedNumber.isBlank()) {
            Toast.makeText(context, "Invalid phone number", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$formattedNumber")
        val whatsappIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(whatsappIntent)
        } catch (_: Exception) {
            // Try WhatsApp Business or system browser fallback
            try {
                val businessIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp.w4b")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(businessIntent)
            } catch (_: Exception) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, context.getString(R.string.whatsapp_not_installed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Finds all installed Telegram client applications on the device.
     */
    fun getInstalledTelegramClients(context: Context): List<TelegramClient> {
        val pm = context.packageManager
        val knownPackages = linkedMapOf(
            "ir.blindgram.messenger" to "BlindGram",
            "tw.nekomimi.nekogram" to "Nekogram",
            "org.telegram.messenger" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X",
            "org.telegram.plus" to "Plus Messenger",
            "nekox.messenger" to "NekoX"
        )

        val clients = mutableListOf<TelegramClient>()
        val addedPackages = mutableSetOf<String>()

        for ((pkg, defaultLabel) in knownPackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString().ifBlank { defaultLabel }
                if (addedPackages.add(pkg)) {
                    clients.add(TelegramClient(pkg, label))
                }
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed
            } catch (_: Exception) {}
        }

        // Also check any other app responding to t.me links
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/test"))
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }

            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("android") || pkg == "com.vivo.browser") {
                    continue
                }
                if (addedPackages.add(pkg)) {
                    val label = try {
                        info.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    clients.add(TelegramClient(pkg, label))
                }
            }
        } catch (_: Exception) {}

        return clients
    }

    /**
     * Opens Telegram messaging for the specified number.
     * If multiple Telegram clients are installed (e.g. Telegram, Nekogram, BlindGram),
     * presents an accessible dialog allowing the user to select their desired client.
     */
    fun openTelegram(context: Context, rawNumber: String) {
        val digits = rawNumber.replace("[^0-9]".toRegex(), "")
        if (digits.isBlank()) {
            Toast.makeText(context, "Invalid phone number", Toast.LENGTH_SHORT).show()
            return
        }

        val international = if (digits.length == 10) "+91$digits" else "+$digits"
        val telegramUri = Uri.parse("https://t.me/$international")

        val clients = getInstalledTelegramClients(context)

        if (clients.isEmpty()) {
            // Fallback: try generic action view
            try {
                val genericIntent = Intent(Intent.ACTION_VIEW, telegramUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.telegram_not_installed), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (clients.size == 1) {
            launchTelegramClient(context, clients[0].packageName, telegramUri)
            return
        }

        // Show accessible selection dialog for multiple clients
        val clientNames = clients.map { it.appName }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_choose_telegram_client)
            .setItems(clientNames) { _, which ->
                val selectedClient = clients[which]
                launchTelegramClient(context, selectedClient.packageName, telegramUri)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun launchTelegramClient(context: Context, packageName: String, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch $packageName", Toast.LENGTH_SHORT).show()
        }
    }
}
