package com.monday.assistant.services

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.RemoteInput

/**
 * ═══════════════════════════════════════════════════════════════════════
 * MONDAY NOTIFICATION SERVICE — Reads & Replies to Notifications
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Listens to ALL notifications on the phone.
 *
 * FEATURES:
 * - Captures sender, message, app for each notification
 * - Direct Reply: sends replies without opening the app
 *   Works with: WhatsApp, Messenger, Telegram, Instagram, SMS
 * - Provides notification queue for "ke message dise?" queries
 *
 * USER SETUP: Settings → Apps → Special app access → Notification access → Monday
 *
 * HOW TO DEBUG:
 * - Set LOG_ALL_NOTIFS = true to see every notification in logcat
 * - Call dumpPendingNotifications() to see current queue
 *
 * HOW TO ADD A NEW APP:
 * - Add package name to SUPPORTED_REPLY_APPS
 * - The direct reply will work automatically if the app supports RemoteInput
 */
class MondayNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationService"
        private const val LOG_ALL_NOTIFS = false // Set true to debug all notifications
        private const val MAX_STORED = 50 // Max notifications to keep in memory

        // Apps that support Android Direct Reply (no need to open app)
        private val SUPPORTED_MESSAGING_APPS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.facebook.orca",          // Messenger
            "org.telegram.messenger",
            "com.instagram.android",
            "com.discord",
            "com.viber.voip",
            "com.snapchat.android",
            "com.google.android.apps.messaging", // SMS
            "com.samsung.android.messaging"      // Samsung SMS
        )

        // Singleton reference for other classes to access notification data
        @Volatile
        var instance: MondayNotificationService? = null
            private set
    }

    // In-memory queue of recent notifications
    private val pendingNotifications = mutableListOf<CapturedNotification>()

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Monday Notification Service connected ✓")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    // ─── Notification Events ──────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val packageName = sbn.packageName
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return

        // Skip empty or system notifications
        if (title.isBlank() || text.isBlank()) return
        if (packageName == "android") return

        val appName = getAppName(packageName)  // FIX: declare before use

        val captured = CapturedNotification(
            id = sbn.key,
            packageName = packageName,
            appName = appName,
            sender = title,
            message = text,
            timestamp = sbn.postTime,
            isRead = false,
            supportsReply = supportsDirectReply(sbn)
        )

        // Add to queue (most recent first)
        synchronized(pendingNotifications) {
            // Remove older notification from same sender in same app
            pendingNotifications.removeAll {
                it.packageName == packageName && it.sender == title
            }
            pendingNotifications.add(0, captured)

            // Keep queue bounded
            if (pendingNotifications.size > MAX_STORED) {
                pendingNotifications.removeAt(pendingNotifications.lastIndex)
            }
        }

        if (LOG_ALL_NOTIFS) {
            Log.d(TAG, "Notification: [$appName] $title: $text")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(pendingNotifications) {
            pendingNotifications.removeAll { it.id == sbn.key }
        }
    }

    // ─── Query Methods ────────────────────────────────────────────────────────

    /**
     * Get all pending unread notifications.
     * Returns formatted string for Gemini context.
     */
    fun getPendingNotificationsContext(): String {
        val notifications = synchronized(pendingNotifications) {
            pendingNotifications.filter { !it.isRead }.toList()
        }

        if (notifications.isEmpty()) return "No pending notifications."

        return notifications.joinToString("\n") {
            "[${it.appName}] ${it.sender}: \"${it.message}\" (${it.timeSince()})" +
                    if (it.supportsReply) " [can reply]" else ""
        }
    }

    /**
     * Get the most recent notification (for "ke message dise?" queries).
     */
    fun getMostRecent(): CapturedNotification? =
        synchronized(pendingNotifications) {
            pendingNotifications.firstOrNull()
        }

    /**
     * Find notification by sender name (fuzzy match).
     */
    fun findBySender(name: String): CapturedNotification? {
        val lower = name.lowercase()
        return synchronized(pendingNotifications) {
            pendingNotifications.firstOrNull {
                it.sender.lowercase().contains(lower)
            }
        }
    }

    /**
     * Get notifications from a specific app.
     */
    fun getByApp(appName: String): List<CapturedNotification> {
        val lower = appName.lowercase()
        return synchronized(pendingNotifications) {
            pendingNotifications.filter {
                it.appName.lowercase().contains(lower) ||
                        it.packageName.contains(lower)
            }.toList()
        }
    }

    // ─── Reply Actions ────────────────────────────────────────────────────────

    /**
     * Send a direct reply to a notification WITHOUT opening the app.
     * Works for WhatsApp, Messenger, Telegram, Instagram, SMS, etc.
     *
     * @param notificationKey The notification key (from CapturedNotification.id)
     * @param replyText The text to send as reply
     * @return true if reply was sent successfully
     */
    fun replyToNotification(notificationKey: String, replyText: String): Boolean {
        val sbn = activeNotifications?.find { it.key == notificationKey }
            ?: return false.also { Log.w(TAG, "Notification not found: $notificationKey") }

        val replyAction = findReplyAction(sbn.notification) ?: run {
            Log.w(TAG, "No reply action found for: ${sbn.packageName}")
            return false
        }

        val remoteInputs = replyAction.remoteInputs ?: return false
        if (remoteInputs.isEmpty()) return false

        // Build the reply intent with the text
        val intent = android.content.Intent()
        val bundle = Bundle()
        for (remoteInput in remoteInputs) {
            bundle.putCharSequence(remoteInput.resultKey, replyText)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

        return try {
            replyAction.actionIntent.send(applicationContext, 0, intent)
            Log.d(TAG, "Reply sent to ${sbn.packageName}: '$replyText'")

            // Mark as read and remove from queue
            markAsRead(notificationKey)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply: ${e.message}", e)
            false
        }
    }

    /**
     * Dismiss (clear) a specific notification.
     */
    fun dismissNotification(notificationKey: String) {
        cancelNotification(notificationKey)
        markAsRead(notificationKey)
    }

    /**
     * Dismiss all current notifications.
     */
    fun dismissAll() {
        cancelAllNotifications()
        synchronized(pendingNotifications) { pendingNotifications.clear() }
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────

    private fun markAsRead(key: String) {
        synchronized(pendingNotifications) {
            pendingNotifications.find { it.id == key }?.isRead = true
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        return notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
    }

    private fun supportsDirectReply(sbn: StatusBarNotification): Boolean {
        val actions = sbn.notification?.actions ?: return false
        return actions.any { it.remoteInputs?.isNotEmpty() == true }
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.facebook.orca" -> "Messenger"
            "org.telegram.messenger" -> "Telegram"
            "com.instagram.android" -> "Instagram"
            "com.discord" -> "Discord"
            "com.viber.voip" -> "Viber"
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging" -> "SMS"
            else -> try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (e: Exception) {
                packageName.substringAfterLast(".")
            }
        }
    }

    fun dumpPendingNotifications() {
        Log.d(TAG, "═══ PENDING NOTIFICATIONS ═══")
        synchronized(pendingNotifications) {
            pendingNotifications.forEach {
                Log.d(TAG, "[${it.appName}] ${it.sender}: ${it.message} | reply=${it.supportsReply}")
            }
        }
    }
}

// ─── Data Model ───────────────────────────────────────────────────────────────

data class CapturedNotification(
    val id: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val message: String,
    val timestamp: Long,
    var isRead: Boolean,
    val supportsReply: Boolean
) {
    fun timeSince(): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            else -> "${diff / 3_600_000}h ago"
        }
    }
}
