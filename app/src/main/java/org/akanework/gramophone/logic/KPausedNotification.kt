/*
 *     Copyright (C) 2026 Gramophone K
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.utils.MediaButtonReceiver
import org.akanework.gramophone.ui.MainActivity

/**
 * One UI hides the media player in the quick panel 60s after playback is paused (SystemUI timer,
 * not controllable by apps). After the same delay this posts a plain "paused" notification with
 * cover, position and icon buttons, so the song can still be resumed from the notification shade.
 * It stays after the service is stopped; its buttons go through [MediaButtonReceiver].
 */
class KPausedNotification(
    private val context: Context,
    private val handler: Handler,
    private val bitmapLoader: () -> BitmapLoader?,
) {

    companion object {
        private const val CHANNEL_ID = "k_paused"
        private const val NOTIFY_ID = 1100
        private const val PENDING_INTENT_OPEN_ID = 1100
        private const val PENDING_INTENT_KEY_ID = 1101 // + index
        private const val DELAY_MS = 60_000L

    }

    private val nm = NotificationManagerCompat.from(context)
    private var player: Player? = null
    private var shown = false
    private var scheduled = false
    private var released = false
    private var showSequence = 0
    private val showRunnable = Runnable {
        scheduled = false
        show()
    }

    init {
        // left over from a previous service instance; the media player is visible again now
        nm.cancel(NOTIFY_ID)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.k_paused_channel))
                .setShowBadge(false)
                .build()
        )
    }

    /** Call when play state, playback state, queue, position or metadata changed. */
    fun update(player: Player) {
        if (released) return
        this.player = player
        val paused = !player.playWhenReady && player.currentMediaItem != null &&
                player.playbackState == Player.STATE_READY
        if (!paused) {
            handler.removeCallbacks(showRunnable)
            scheduled = false
            if (shown) {
                showSequence++
                nm.cancel(NOTIFY_ID)
                shown = false
            }
        } else if (shown) {
            show() // refresh after a track change or seek while paused
        } else if (!scheduled) {
            handler.postDelayed(showRunnable, DELAY_MS)
            scheduled = true
        }
    }

    /**
     * Service is going away: stop the timer but keep a notification that is already shown.
     * Previous/next only work while the service runs (Media3 only restarts it for play), so the
     * notification is posted again without them.
     */
    fun release() {
        if (shown) show(withSkip = false)
        released = true
        handler.removeCallbacks(showRunnable)
        scheduled = false
        player = null
    }

    private fun show(withSkip: Boolean = true) {
        val player = player ?: return
        if (!context.hasNotificationPermission() || !nm.areNotificationsEnabled()) return
        val state = State(
            player.mediaMetadata,
            player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            withSkip,
            withSkip,
        )
        val sequence = ++showSequence
        notify(state, null)
        shown = true
        val future = bitmapLoader()?.loadBitmapFromMetadata(state.metadata) ?: return
        future.addListener({
            val bitmap = try {
                future.get()
            } catch (_: Exception) {
                null
            }
            if (bitmap != null && sequence == showSequence && shown) notify(state, bitmap)
        }, { handler.post(it) })
    }

    private class State(
        val metadata: MediaMetadata,
        val position: Long,
        val duration: Long,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    )

    private fun keyIntent(index: Int, keyCode: Int) = PendingIntent.getBroadcast(
        context, PENDING_INTENT_KEY_ID + index,
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun timeText(state: State): String {
        val position = DateUtils.formatElapsedTime(state.position / 1000)
        if (state.duration <= 0) return position
        return position + " / " + DateUtils.formatElapsedTime(state.duration / 1000)
    }

    @SuppressLint("MissingPermission")
    private fun notify(state: State, cover: Bitmap?) {
        val open = PendingIntent.getActivity(
            context, PENDING_INTENT_OPEN_ID,
            Intent(context, MainActivity::class.java).putExtra(GramophoneK.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = state.metadata.title ?: state.metadata.displayTitle
        val subText = context.getString(R.string.k_paused_state) + " · " + timeText(state)
        val max = (state.duration / 1000).toInt()
        val progress = (state.position / 1000).toInt().coerceAtMost(max)
        // Custom views: One UI shows standard notification actions as text only, and the
        // standard progress bar would be added below the custom expanded view.
        fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.k_paused_title, title)
            setProgressBar(R.id.k_paused_progress, max, progress, false)
            setViewVisibility(R.id.k_paused_progress, if (max > 0) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.k_paused_play, keyIntent(1, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
        val big = views(R.layout.k_paused_notification_big).apply {
            setTextViewText(R.id.k_paused_artist, state.metadata.artist)
            setViewVisibility(R.id.k_paused_previous, if (state.hasPrevious) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.k_paused_next, if (state.hasNext) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.k_paused_previous, keyIntent(0, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            setOnClickPendingIntent(R.id.k_paused_next, keyIntent(2, KeyEvent.KEYCODE_MEDIA_NEXT))
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gramophone_monochrome)
            .setContentTitle(title)
            .setContentText(state.metadata.artist)
            .setSubText(subText)
            .setContentIntent(open)
            .setLargeIcon(cover)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.k_paused_notification).apply {
                setTextViewText(R.id.k_paused_time, subText)
            })
            .setCustomBigContentView(big)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setSilent(true)
        nm.notify(NOTIFY_ID, builder.build())
    }
}
