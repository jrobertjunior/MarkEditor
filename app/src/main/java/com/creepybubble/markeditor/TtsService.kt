package com.creepybubble.markeditor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Serviço em foreground que mantém a leitura de TTS viva em segundo plano e exibe uma
 * notificação de mídia com controles (anterior, tocar/pausar, próximo, parar).
 * O motor em si vive no [TtsManager] singleton — o serviço só orquestra notificação/sessão.
 */
class TtsService : Service() {

    private lateinit var tts: TtsManager
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        tts = TtsManager.get(this)
        createChannel()
        mediaSession = MediaSessionCompat(this, "MarkEditorTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { tts.resume() }
                override fun onPause() { tts.pause() }
                override fun onSkipToNext() { tts.next() }
                override fun onSkipToPrevious() { tts.previous() }
                override fun onStop() { tts.stop() }
            })
            isActive = true
        }
        tts.onStateChanged = { updateOrStop() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> tts.resume()
            ACTION_PAUSE -> tts.pause()
            ACTION_NEXT -> tts.next()
            ACTION_PREV -> tts.previous()
            ACTION_STOP -> tts.stop()
        }
        // Precisa entrar em foreground logo após ser iniciado.
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        updateOrStop()
        return START_NOT_STICKY
    }

    private fun updateOrStop() {
        if (!tts.isSpeaking && !tts.isPaused) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val playing = tts.isSpeaking

        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )

        val playPause = if (playing) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pausar", servicePending(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Tocar", servicePending(ACTION_PLAY))
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(tts.currentTitle)
            .setContentText(if (playing) "Lendo em voz alta" else "Pausado")
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", servicePending(ACTION_PREV))
            .addAction(playPause)
            .addAction(android.R.drawable.ic_media_next, "Próximo", servicePending(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", servicePending(ACTION_STOP))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePending(action: String): PendingIntent {
        val intent = Intent(this, TtsService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Leitura em voz alta", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        tts.onStateChanged = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "tts_playback"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY = "com.creepybubble.markeditor.PLAY"
        const val ACTION_PAUSE = "com.creepybubble.markeditor.PAUSE"
        const val ACTION_NEXT = "com.creepybubble.markeditor.NEXT"
        const val ACTION_PREV = "com.creepybubble.markeditor.PREV"
        const val ACTION_STOP = "com.creepybubble.markeditor.STOP"
    }
}
