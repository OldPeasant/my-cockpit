package ch.sonensei.cockpit.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shows reminders as notifications on the reminders channel; a tap opens the reminded item in the app. */
@Singleton
class ReminderNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val manager = NotificationManagerCompat.from(context)

        /** Whether a notification can be shown: the permission (Android 13+) is granted and notifications are on. */
        fun canNotify(): Boolean = hasPermission() && manager.areNotificationsEnabled()

        /** Shows [reminder]; does nothing when notifications are not allowed (the prompt in the app asks for them). */
        fun show(reminder: ScheduledReminder) {
            if (!hasPermission()) return
            ensureChannel()
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_reminder)
                    .setContentTitle(reminder.title ?: context.getString(R.string.reminder_untitled))
                    .setContentText(context.getString(R.string.reminder_notification_text))
                    .setWhen(reminder.remindAtMillis)
                    .setShowWhen(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    // The item title is the user's data: hidden on the lock screen unless the user allows it.
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setContentIntent(openItemPendingIntent(reminder))
                    .setAutoCancel(true)
                    .build()
            try {
                manager.notify(reminder.id, NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Revoked between the check and the call.
            }
        }

        fun dismiss(id: String) = manager.cancel(id, NOTIFICATION_ID)

        /** Removes every reminder notification still shown, also those of reminders a sync has dropped since. */
        fun dismissAll() {
            context
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .filter { it.id == NOTIFICATION_ID && it.notification.channelId == CHANNEL_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }

        private fun hasPermission(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun ensureChannel() {
            val channel =
                NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.reminder_channel_description) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun openItemPendingIntent(reminder: ScheduledReminder): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                openItemIntent(context, reminder),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        internal companion object {
            const val CHANNEL_ID = "reminders"

            /** Notifications are told apart by their tag (the reminder id); the id is the same for all. */
            const val NOTIFICATION_ID = 1
        }
    }

/** Opens the app on the reminded item; the data URI keeps the intents of two reminders apart. */
internal fun openItemIntent(
    context: Context,
    reminder: ScheduledReminder,
): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ReminderOpenRequests.ACTION_OPEN_ITEM)
        .setData(android.net.Uri.fromParts(REMINDER_URI_SCHEME, reminder.id, null))
        .putExtra(ReminderOpenRequests.EXTRA_ITEM_TYPE, reminder.itemType)
        .putExtra(ReminderOpenRequests.EXTRA_ITEM_ID, reminder.itemId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
