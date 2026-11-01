package com.sona.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sona.player.MainActivity

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val engine = AutomixPlaybackEngine.getInstance(applicationContext)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val session = MediaSession.Builder(this, engine.sessionPlayer)
            .setSessionActivity(pendingIntent)
            .build()

        engine.attachMediaSession(session)
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        val engine = AutomixPlaybackEngine.getInstance(applicationContext)
        mediaSession?.let { session ->
            engine.detachMediaSession(session)
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
