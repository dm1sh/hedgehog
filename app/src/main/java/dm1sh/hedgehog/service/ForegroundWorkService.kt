package dm1sh.hedgehog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dm1sh.hedgehog.R

class ForegroundWorkService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:work").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val max = intent?.getIntExtra(EXTRA_MAX, 0) ?: 0
        startForeground(NOTIFICATION_ID, buildNotification(title, text, progress, max))
        return START_NOT_STICKY
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int
    ): Notification {
        val label = applicationInfo.loadLabel(packageManager)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, label, NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val contentTitle = if (title.isNotBlank()) title else label
        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (max > 0) {
            builder.setProgress(max, progress.coerceIn(0, max), false)
        }

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "work"
        private const val COMPLETION_CHANNEL_ID = "work_completed"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MAX = "max"
        private const val NOTIFICATION_ID = 1
        private const val COMPLETION_NOTIFICATION_ID = 2

        fun setActive(
            context: Context,
            active: Boolean,
            text: String = "",
            progress: Int = 0,
            max: Int = 0,
            title: String = ""
        ) {
            try {
                val intent = Intent(context, ForegroundWorkService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                    putExtra(EXTRA_PROGRESS, progress)
                    putExtra(EXTRA_MAX, max)
                }
                if (active) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun updateProgress(
            context: Context,
            text: String,
            progress: Int,
            max: Int,
            title: String = ""
        ) {
            setActive(
                context = context,
                active = true,
                text = text,
                progress = progress,
                max = max,
                title = title
            )
        }

        fun showCompletionNotification(
            context: Context,
            bookTitle: String,
            completedLabel: String,
            partsProcessed: String,
            elapsedTime: String
        ) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val appLabel = context.applicationInfo.loadLabel(context.packageManager)
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            COMPLETION_CHANNEL_ID,
                            completedLabel.ifBlank { appLabel },
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                    Notification.Builder(context, COMPLETION_CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context).setPriority(Notification.PRIORITY_DEFAULT)
                }

                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                val pendingIntent = if (launchIntent != null) {
                    PendingIntent.getActivity(
                        context,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else null

                val titleText = bookTitle.ifBlank { appLabel.toString() }
                val summaryLine = buildString {
                    append(completedLabel)
                    if (partsProcessed.isNotBlank()) {
                        append(" • ")
                        append(partsProcessed)
                    }
                    if (elapsedTime.isNotBlank()) {
                        append(" • ")
                        append(elapsedTime)
                    }
                }
                val bigText = buildString {
                    append(completedLabel)
                    if (partsProcessed.isNotBlank()) {
                        append("\n")
                        append(partsProcessed)
                    }
                    if (elapsedTime.isNotBlank()) {
                        append(" (")
                        append(elapsedTime)
                        append(")")
                    }
                }

                val notification = builder
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(titleText)
                    .setContentText(summaryLine)
                    .setStyle(Notification.BigTextStyle().bigText(bigText))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()

                nm.notify(COMPLETION_NOTIFICATION_ID, notification)
            } catch (_: Exception) {
            }
        }
    }
}
