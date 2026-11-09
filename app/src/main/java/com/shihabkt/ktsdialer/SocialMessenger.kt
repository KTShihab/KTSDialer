package com.shihabkt.ktsdialer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Utility for launching direct messaging in WhatsApp and multi-client Telegram.
 * Supports selecting between multiple installed Telegram clients (BlindGram, Nekogram, Telegram, etc.).
 */
object SocialMessenger {

    data class TelegramClient(
        val packageName: String,
        val appName: String
    )

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
