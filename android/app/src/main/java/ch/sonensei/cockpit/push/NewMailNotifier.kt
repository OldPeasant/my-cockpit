package ch.sonensei.cockpit.push

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
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.reminders.ReminderSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shows that new mail arrived; one notification, updated by every new-mail push, without any mail contents. */
interface NewMailNotifier {
    fun show()

    fun dismiss()
}

@Singleton
class SystemNewMailNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : NewMailNotifier {
        private val manager = NotificationManagerCompat.from(context)

        override fun show() {
            if (!hasPermission()) return
            val channel =
                NotificationChannel(CHANNEL_ID, context.getString(R.string.push_mail_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.push_mail_channel_description) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_mail)
                    .setContentTitle(context.getString(R.string.push_mail_title))
                    .setContentText(context.getString(R.string.push_mail_text))
                    .setCategory(NotificationCompat.CATEGORY_EMAIL)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            try {
                manager.notify(TAG, NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Revoked between the check and the call.
            }
        }

        override fun dismiss() = manager.cancel(TAG, NOTIFICATION_ID)

        private fun hasPermission(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        internal companion object {
            const val CHANNEL_ID = "mail"
            const val TAG = "new-mail"
            const val NOTIFICATION_ID = 2
        }
    }

/** Runs the reminder sync once, now (as soon as there is network), next to its periodic runs. */
class WorkManagerPushSyncTrigger
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : PushSyncTrigger {
        override fun syncReminders() {
            val request =
                OneTimeWorkRequestBuilder<ReminderSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            // REPLACE: a sync that started before the push may have missed what the push announces.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        internal companion object {
            const val WORK_NAME = "push-reminders-sync"
        }
    }
