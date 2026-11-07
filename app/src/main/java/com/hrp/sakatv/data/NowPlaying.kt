package com.hrp.sakatv.data

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/**
 * Boş bildirim dinleyicisi. Tek amacı, sistemden "şu an çalan" medya oturumlarını okuma iznini almak.
 * İzin TV'de ADB ile verilir: adb shell cmd notification allow_listener com.hrp.sakatv/com.hrp.sakatv.data.NowPlayingListener
 */
class NowPlayingListener : NotificationListenerService()

data class NowPlaying(val title: String, val artist: String, val app: String)

object NowPlayingReader {
    /** İzin yoksa ya da bir şey çalmıyorsa null döner. */
    fun read(ctx: Context): NowPlaying? = runCatching {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = msm.getActiveSessions(ComponentName(ctx, NowPlayingListener::class.java))
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return@runCatching null
        val md = c.metadata ?: return@runCatching null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return@runCatching null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val app = if (c.packageName.contains("spotify")) "Spotify" else ""
        NowPlaying(title, artist, app)
    }.getOrNull()
}
