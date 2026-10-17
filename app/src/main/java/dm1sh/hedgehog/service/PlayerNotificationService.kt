package dm1sh.hedgehog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dm1sh.hedgehog.MainActivity
import dm1sh.hedgehog.R

class PlayerNotificationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:player").apply {
            setReferenceCounted(false)
        }
        mediaSession = MediaSession(this, "HedgehogPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    actionListener?.onPlayPause()
                }

                override fun onPause() {
                    actionListener?.onPlayPause()
                }

                override fun onSkipToPrevious() {
                    actionListener?.onPrevious()
                }

                override fun onSkipToNext() {
                    actionListener?.onNext()
                }
            })
            isActive = true
        }
    }

    override fun onDestroy() {
        isNotificationVisible = false
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> {
                actionListener?.onPrevious()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> {
                actionListener?.onPlayPause()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                actionListener?.onNext()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                isNotificationVisible = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val sentenceText = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, false) ?: false
        val prevLabel = intent?.getStringExtra(EXTRA_PREV_LABEL).orEmpty().ifBlank { "Previous" }
        val playPauseLabel = intent?.getStringExtra(EXTRA_PLAY_PAUSE_LABEL).orEmpty().ifBlank {
            if (isPlaying) "Pause" else "Play"
        }
        val nextLabel = intent?.getStringExtra(EXTRA_NEXT_LABEL).orEmpty().ifBlank { "Next" }

        try {
            if (isPlaying) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            } else {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
            }
        } catch (_: Exception) {
        }

        updateMediaSessionState(title = title, sentenceText = sentenceText, isPlaying = isPlaying)
        val notification = buildPlayerNotification(
            title = title,
            sentenceText = sentenceText,
            isPlaying = isPlaying,
            prevLabel = prevLabel,
            playPauseLabel = playPauseLabel,
            nextLabel = nextLabel
        )

        isNotificationVisible = true
        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // Keep notification visible with Play control while paused, but allow swipe-dismiss
            try {
                startForeground(NOTIFICATION_ID, notification)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    private fun updateMediaSessionState(
        title: String,
        sentenceText: String,
        isPlaying: Boolean
    ) {
        val session = mediaSession ?: return
        try {
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sentenceText)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, sentenceText)
                .build()
            session.setMetadata(metadata)

            val stateCode = if (isPlaying) {
                PlaybackState.STATE_PLAYING
            } else {
                PlaybackState.STATE_PAUSED
            }
            val playbackState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_NEXT
                )
                .setState(stateCode, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            session.setPlaybackState(playbackState)
        } catch (_: Exception) {
        }
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlayerNotificationService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildPlayerNotification(
        title: String,
        sentenceText: String,
        isPlaying: Boolean,
        prevLabel: String,
        playPauseLabel: String,
        nextLabel: String
    ): Notification {
        val appLabel = applicationInfo.loadLabel(packageManager)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "$appLabel Player",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }

        val openPlayerIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER_TAB, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            200,
            openPlayerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(
            android.R.drawable.ic_media_previous,
            prevLabel,
            serviceActionPendingIntent(ACTION_PREV, 201)
        ).build()

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseAction = Notification.Action.Builder(
            playPauseIcon,
            playPauseLabel,
            serviceActionPendingIntent(ACTION_PLAY_PAUSE, 202)
        ).build()

        val nextAction = Notification.Action.Builder(
            android.R.drawable.ic_media_next,
            nextLabel,
            serviceActionPendingIntent(ACTION_NEXT, 203)
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        mediaSession?.sessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }

        val contentTitle = title.ifBlank { appLabel.toString() }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(sentenceText)
            .setContentIntent(contentPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(mediaStyle)
            .build()
    }

    interface ActionListener {
        fun onPrevious()
        fun onPlayPause()
        fun onNext()
    }

    companion object {
        private const val CHANNEL_ID = "player_playback"
        private const val NOTIFICATION_ID = 3

        const val ACTION_UPDATE = "dm1sh.hedgehog.player.UPDATE"
        const val ACTION_PREV = "dm1sh.hedgehog.player.PREV"
        const val ACTION_PLAY_PAUSE = "dm1sh.hedgehog.player.PLAY_PAUSE"
        const val ACTION_NEXT = "dm1sh.hedgehog.player.NEXT"
        const val ACTION_DISMISS = "dm1sh.hedgehog.player.DISMISS"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_PREV_LABEL = "extra_prev_label"
        const val EXTRA_PLAY_PAUSE_LABEL = "extra_play_pause_label"
        const val EXTRA_NEXT_LABEL = "extra_next_label"
        const val EXTRA_OPEN_PLAYER_TAB = "extra_open_player_tab"

        @Volatile
        var actionListener: ActionListener? = null

        @Volatile
        var isNotificationVisible: Boolean = false
            private set

        fun showOrUpdate(
            context: Context,
            title: String,
            sentenceText: String,
            isPlaying: Boolean,
            prevLabel: String,
            playPauseLabel: String,
            nextLabel: String
        ) {
            try {
                val intent = Intent(context, PlayerNotificationService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, sentenceText)
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                    putExtra(EXTRA_PREV_LABEL, prevLabel)
                    putExtra(EXTRA_PLAY_PAUSE_LABEL, playPauseLabel)
                    putExtra(EXTRA_NEXT_LABEL, nextLabel)
                }
                if (isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun dismiss(context: Context) {
            isNotificationVisible = false
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancel(NOTIFICATION_ID)
                context.stopService(Intent(context, PlayerNotificationService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
