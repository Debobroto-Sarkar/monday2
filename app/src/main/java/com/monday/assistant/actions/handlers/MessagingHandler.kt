package com.monday.assistant.actions.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.JsonObject
import com.monday.assistant.actions.ActionResult
import com.monday.assistant.core.ContactResolver
import com.monday.assistant.core.ResolvedContact

/**
 * MESSAGING HANDLER
 * ─────────────────────────────────────────────────────────────────────
 * Sends messages and makes calls.
 *
 * Supports: WhatsApp, Messenger, Telegram, Instagram DM, SMS
 *
 * WhatsApp: Uses wa.me URL scheme (official, works reliably)
 * Messenger: Uses messenger:// URL scheme
 * SMS: Uses tel: and smsto: URI schemes
 * Others: Opens app + Accessibility handles the navigation
 *
 * HOW TO ADD A NEW MESSAGING APP:
 * Add a case in sendMessage() and implement the intent scheme
 */
class MessagingHandler(private val context: Context) {

    companion object {
        private const val TAG = "MessagingHandler"
    }

    private val contactResolver = ContactResolver(context)

    fun sendMessage(action: JsonObject): ActionResult {
        val app = action.get("app")?.asString?.lowercase() ?: "whatsapp"
        val contactName = action.get("contact")?.asString ?: return ActionResult.error("No contact specified")
        val message = action.get("message")?.asString ?: ""

        val contact = contactResolver.resolve(contactName)
            ?: return ActionResult.error("Could not find contact: $contactName")

        return when (app) {
            "whatsapp", "wa" -> sendWhatsApp(contact, message)
            "messenger", "fb" -> sendMessenger(contact, message)
            "telegram", "tg" -> sendTelegram(contact, message)
            "sms", "text" -> sendSms(contact, message)
            "instagram", "ig" -> sendInstagram(contact, message)
            else -> sendWhatsApp(contact, message) // Default to WhatsApp
        }
    }

    private fun sendWhatsApp(contact: ResolvedContact, message: String): ActionResult {
        return try {
            val phone = contact.phone?.replace(Regex("[^0-9+]"), "") ?: ""
            if (phone.isBlank()) return ActionResult.error("No phone number for ${contact.name}")

            // Use international format — add Bangladesh code if needed
            val intlPhone = if (phone.startsWith("+")) phone
            else if (phone.startsWith("0")) "+88${phone.substring(1)}"
            else "+88$phone"

            val uri = Uri.parse("https://wa.me/${intlPhone.replace("+", "")}").let {
                if (message.isNotBlank()) Uri.parse("https://wa.me/${intlPhone.replace("+", "")}?text=${Uri.encode(message)}")
                else it
            }

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "WhatsApp opened for: ${contact.name}")
            ActionResult.success("WhatsApp ${contact.name} ke open korা হয়েছে")
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp error: ${e.message}")
            ActionResult.error("WhatsApp open করতে পারিনি")
        }
    }

    private fun sendMessenger(contact: ResolvedContact, message: String): ActionResult {
        return try {
            // Try deep link first
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("messenger://user/${contact.messengerId ?: contact.name}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Fall back to opening Messenger app
                val fallback = context.packageManager
                    .getLaunchIntentForPackage("com.facebook.orca")
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ?: return ActionResult.error("Messenger installed nei")
                context.startActivity(fallback)
            }

            ActionResult.success("Messenger open হয়েছে — ${contact.name} ke message diye dao")
        } catch (e: Exception) {
            ActionResult.error("Messenger error: ${e.message}")
        }
    }

    private fun sendTelegram(contact: ResolvedContact, message: String): ActionResult {
        return try {
            val username = contact.telegramUsername ?: contact.name
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("tg://resolve?domain=$username&text=${Uri.encode(message)}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            ActionResult.success("Telegram open হয়েছে")
        } catch (e: Exception) {
            ActionResult.error("Telegram error: ${e.message}")
        }
    }

    private fun sendSms(contact: ResolvedContact, message: String): ActionResult {
        val phone = contact.phone ?: return ActionResult.error("No phone number for ${contact.name}")
        return try {
            val intent = Intent(Intent.ACTION_SENDTO,
                Uri.parse("smsto:$phone")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.success("SMS open হয়েছে")
        } catch (e: Exception) {
            ActionResult.error("SMS error: ${e.message}")
        }
    }

    private fun sendInstagram(contact: ResolvedContact, message: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("instagram://user?username=${contact.name}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Open Instagram main app
                context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ?.let { context.startActivity(it) }
            }
            ActionResult.success("Instagram open হয়েছে")
        } catch (e: Exception) {
            ActionResult.error("Instagram error: ${e.message}")
        }
    }

    fun makeCall(action: JsonObject): ActionResult {
        val contactName = action.get("contact")?.asString ?: return ActionResult.error("No contact")
        val contact = contactResolver.resolve(contactName)
            ?: return ActionResult.error("Contact not found: $contactName")
        val phone = contact.phone ?: return ActionResult.error("No phone number for ${contact.name}")

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.success("Calling ${contact.name}…")
        } catch (e: Exception) {
            ActionResult.error("Call error: ${e.message}")
        }
    }
}
