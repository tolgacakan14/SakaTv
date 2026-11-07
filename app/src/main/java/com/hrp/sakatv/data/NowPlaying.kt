package com.hrp.sakatv.data

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Boş bildirim dinleyicisi. Tek amacı, sistemden "şu an çalan" medya oturumlarını okuma iznini almak.
 * İzin TV'de ADB ile verilir: adb shell cmd notification allow_listener com.hrp.sakatv/com.hrp.sakatv.data.NowPlayingListener
 */
class NowPlayingListener : NotificationListenerService()

data class NowPlaying(val title: String, val artist: String, val app: String)

object NowPlayingReader {
    /** İzin yoksa 10 dakika boyunca hiç denemeyiz: her denemede sistem bir istisna üretip atıyor. */
    private const val DENIED_RETRY_MS = 10 * 60 * 1000L
    @Volatile private var deniedAt = 0L

    /** İzin yoksa ya da bir şey çalmıyorsa null döner. */
    fun read(ctx: Context): NowPlaying? {
        val d = deniedAt
        if (d != 0L && SystemClock.elapsedRealtime() - d < DENIED_RETRY_MS) return null
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            val sessions = msm.getActiveSessions(ComponentName(ctx, NowPlayingListener::class.java))
            deniedAt = 0L
            val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return null
            val md = c.metadata ?: return null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val app = if (c.packageName.contains("spotify")) "Spotify" else ""
            NowPlaying(title, artist, app)
        } catch (e: SecurityException) {
            deniedAt = SystemClock.elapsedRealtime()
            null
        } catch (e: Exception) {
            null
        }
    }

    /** İzin yeni verildiyse beklemeden yeniden denemek için. */
    fun resetPermissionCache() { deniedAt = 0L }
}
