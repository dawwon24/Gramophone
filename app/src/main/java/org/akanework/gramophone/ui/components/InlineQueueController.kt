/*
 *     Gramophone K customizations.
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

package org.akanework.gramophone.ui.components

import android.annotation.SuppressLint
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.replaceAllSupport
import org.akanework.gramophone.ui.MainActivity

/**
 * Gramophone K: shows the play queue inside the full player, where the album cover used to be.
 * Tap to play. Read-only: reordering / removing is done in [PlaylistQueueSheet].
 */
class InlineQueueController(
    private val activity: MainActivity,
    private val recyclerView: RecyclerView
) : Player.Listener {
    private val instance
        get() = activity.getPlayer()
    private val adapter = QueueAdapter()
    private val touchHelper = ItemTouchHelper(adapter.PlaylistCardMoveCallback())

    init {
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = adapter
        // Gramophone K: read-only here; reordering / removing only in the full queue sheet
        activity.controllerViewModel.addRecreationalPlayerListener(activity.lifecycle, this) {
            adapter.reload()
            adapter.currentIsPlaying = it.isPlaying
            scrollToCurrent(false)
        }
    }

    /** Called when the full player becomes visible. */
    fun scrollToCurrent(smooth: Boolean) {
        val pos = adapter.currentMediaItemIndex ?: return
        if (smooth) {
            recyclerView.smoothScrollToPosition(pos)
        } else {
            (recyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                pos, recyclerView.resources.getDimensionPixelOffset(R.dimen.list_height) / 2
            )
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: @Player.TimelineChangeReason Int) {
        // Changes we made ourselves (drag / remove) are already reflected, so only reload when
        // the queue actually differs from what we show.
        if (adapter.reloadIfChanged()) scrollToCurrent(false)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (adapter.reloadIfChanged()) scrollToCurrent(false)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: @Player.MediaItemTransitionReason Int) {
        adapter.updateCurrent()
        scrollToCurrent(true)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        adapter.currentIsPlaying = isPlaying
    }

    private inner class QueueAdapter : EditSongAdapter(activity, true) {
        // first: queue indexes in play order (respects shuffle), second: items by queue index
        var playlist: Pair<MutableList<Int>, MutableList<MediaItem>> =
            Pair(mutableListOf(), mutableListOf())
        var currentMediaItemIndex: Int? = null
            set(value) {
                if (field != value) {
                    val oldValue = field
                    field = value
                    if (oldValue != null) notifyItemChanged(oldValue, true)
                    if (value != null) notifyItemChanged(value, true)
                }
            }
        var currentIsPlaying: Boolean? = null
            set(value) {
                if (field != value) {
                    field = value
                    if (value != null) currentMediaItemIndex?.let { notifyItemChanged(it, false) }
                }
            }

        private fun dumpPlaylist(): Pair<MutableList<Int>, MutableList<MediaItem>> {
            val instance = instance ?: return Pair(mutableListOf(), mutableListOf())
            val items = ArrayList<MediaItem>(instance.mediaItemCount)
            for (i in 0 until instance.mediaItemCount) {
                items.add(instance.getMediaItemAt(i))
            }
            val indexes = ArrayList<Int>(items.size)
            val s = instance.shuffleModeEnabled
            var i = instance.currentTimeline.getFirstWindowIndex(s)
            while (i != C.INDEX_UNSET) {
                indexes.add(i)
                i = instance.currentTimeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, s)
            }
            return Pair(indexes, items)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun reload() {
            playlist = dumpPlaylist()
            currentMediaItemIndex = null
            notifyDataSetChanged()
            updateCurrent()
        }

        fun reloadIfChanged(): Boolean {
            val new = dumpPlaylist()
            if (new.first == playlist.first &&
                new.second.map { it.mediaId } == playlist.second.map { it.mediaId }
            ) return false
            reload()
            return true
        }

        fun updateCurrent() {
            val i = instance?.currentMediaItemIndex
            currentMediaItemIndex = i?.let { playlist.first.indexOf(i).takeIf { it != -1 } }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any?>) {
            holder.dragHandle.visibility = View.GONE
            holder.closeButton.visibility = View.GONE
            if (payloads.isNotEmpty()) {
                if (payloads.none { it is Boolean && it }) {
                    holder.nowPlaying.drawable?.level = if (currentIsPlaying == true) 1 else 0
                    return
                }
                if (currentMediaItemIndex == null || position != currentMediaItemIndex) {
                    (holder.nowPlaying.drawable as? NowPlayingDrawable?)?.level2Done = Runnable {
                        holder.nowPlaying.visibility = View.GONE
                        holder.nowPlaying.setImageDrawable(null)
                    }
                    holder.nowPlaying.drawable?.level = 2
                    return
                }
            } else {
                super.onBindViewHolder(holder, position, payloads)
                if (currentMediaItemIndex == null || position != currentMediaItemIndex)
                    return
            }
            if (holder.nowPlaying.visibility != View.VISIBLE) {
                holder.nowPlaying.setImageDrawable(
                    NowPlayingDrawable(holder.itemView.context)
                        .also { it.level = if (currentIsPlaying == true) 1 else 0 })
                holder.nowPlaying.visibility = View.VISIBLE
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            (holder.nowPlaying.drawable as? NowPlayingDrawable?)?.level2Done = null
            holder.nowPlaying.setImageDrawable(null)
            holder.nowPlaying.visibility = View.GONE
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = playlist.first.size

        override fun onClick(pos: Int) {
            instance?.seekToDefaultPosition(playlist.first[pos])
            instance?.play()
        }

        override fun onRowMoved(from: Int, to: Int) {
            val from1 = playlist.first.removeAt(from)
            playlist.first.replaceAllSupport { if (it > from1) it - 1 else it }
            val movedItem = playlist.second.removeAt(from1)
            val to1 = if (to > 0) playlist.first[to - 1] + 1 else 0
            playlist.first.replaceAllSupport { if (it >= to1) it + 1 else it }
            playlist.first.add(to, to1)
            playlist.second.add(to1, movedItem)
            instance?.moveMediaItem(from1, to1)
            notifyItemMoved(from, to)
            val currentIndex = currentMediaItemIndex
            if (currentIndex != null) {
                if (currentIndex == from)
                    currentMediaItemIndex = to
                else if (from < to && from < currentIndex && currentIndex <= to)
                    currentMediaItemIndex = currentIndex - 1
                else if (from > to && to <= currentIndex && currentIndex < from)
                    currentMediaItemIndex = currentIndex + 1
            }
        }

        override fun removeItem(pos: Int) {
            val idx = playlist.first.removeAt(pos)
            playlist.first.replaceAllSupport { if (it > idx) it - 1 else it }
            playlist.second.removeAt(idx)
            notifyItemRemoved(pos)
            val current = currentMediaItemIndex
            if (pos == current) {
                currentMediaItemIndex = null
            } else if (current != null && pos < current) {
                currentMediaItemIndex = current - 1
            }
            instance?.removeMediaItem(idx)
        }

        override fun getItem(pos: Int) = playlist.second[playlist.first[pos]]

        override fun startDrag(holder: ViewHolder) {
            touchHelper.startDrag(holder)
        }
    }
}
