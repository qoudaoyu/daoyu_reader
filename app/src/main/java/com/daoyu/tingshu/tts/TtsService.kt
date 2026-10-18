package com.daoyu.tingshu.tts

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat.MediaStyle
import com.daoyu.tingshu.App
import com.daoyu.tingshu.MainActivity
import com.daoyu.tingshu.R
import kotlinx.coroutines.launch

/** Keeps listening alive in the background and shows lock-screen / notification controls. */
class TtsService : LifecycleService() {
    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) App.instance.tts.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val tts = App.instance.tts
        session = MediaSessionCompat(this, "DaoyuTingshu").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = tts.resume()
                override fun onPause() = tts.pause()
                override fun onSkipToNext() { tts.next() }
                override fun onSkipToPrevious() { tts.prev() }
                override fun onStop() { tts.stop() }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daoyu:listen").apply { setReferenceCounted(false) }

        lifecycleScope.launch {
            tts.state.collect { st ->
                if (!st.active) {
                    if (started) {
                        wakeLock?.release()
                        ServiceCompat.stopForeground(this@TtsService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@collect
                }
                if (st.playing) wakeLock?.acquire(3 * 60 * 60 * 1000L) else wakeLock?.release()
                session.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st.chapterTitle.ifEmpty { st.bookTitle })
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, st.bookTitle)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "刀鱼听书 · ${Engines.label(st.engine)}")
                        .build()
                )
                session.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                        )
                        .setState(if (st.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build()
                )
                showNotification(st)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_TOGGLE -> App.instance.tts.togglePause()
            ACTION_NEXT -> App.instance.tts.next()
            ACTION_PREV -> App.instance.tts.prev()
            ACTION_STOP -> App.instance.tts.stop()
        }
        androidx.media.session.MediaButtonReceiver.handleIntent(session, intent)
        if (!started) showNotification(App.instance.tts.state.value)
        return START_NOT_STICKY
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openListening", true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(act: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, TtsService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun showNotification(st: TtsState) {
        val n: Notification = NotificationCompat.Builder(this, App.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle(st.chapterTitle.ifEmpty { "刀鱼听书" })
            .setContentText(if (st.bookTitle.isEmpty()) "准备朗读…" else "${st.bookTitle} · ${Engines.label(st.engine)}")
            .setContentIntent(openAppIntent())
            .setDeleteIntent(action(ACTION_STOP, 4))
            .setOnlyAlertOnce(true)
            .setOngoing(st.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "上一句", action(ACTION_PREV, 1))
            .addAction(
                if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.playing) "暂停" else "播放", action(ACTION_TOGGLE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "下一句", action(ACTION_NEXT, 3))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        started = true
    }

    override fun onDestroy() {
        wakeLock?.release()
        unregisterReceiver(noisy)
        session.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 7
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
    }
}
