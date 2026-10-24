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

package org.akanework.gramophone.logic

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.MainActivity
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.manipulator.PlaylistSerializer
import uk.akane.libphonograph.utils.KTagFix

object GramophoneK {
    const val PREF_SONG_CLICK_MODE = "k_song_click_mode"
    const val PREF_CONFIRM_REPLACE_QUEUE = "k_confirm_replace_queue"
    const val PREF_ALBUM_MIN_SONGS = "k_album_min_songs"

    private const val CLICK_MODE_SINGLE = "single"

    /**
     * Album tab filter: hide albums which have fewer songs than configured, and (if filtering at
     * all) albums without an album tag. This keeps loose singles out of the album tab.
     */
    fun Flow<List<Album>?>.filterAlbumsForTab(context: Context): Flow<List<Album>?> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        // not getStringStrict(): this runs on a background thread
        fun minSongs() = prefs.getString(PREF_ALBUM_MIN_SONGS, "2")?.toIntOrNull() ?: 2
        val minSongsFlow = callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == PREF_ALBUM_MIN_SONGS) trySend(minSongs())
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            send(minSongs())
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()
        return combine(minSongsFlow) { list, minSongs ->
            if (list == null || minSongs <= 1) list
            else {
                // A folder only counts as an album if all of its songs are that one album
                val albumsPerFolder = list.groupingBy { albumFolderOf(it) }.eachCount()
                list.filter {
                    val folder = albumFolderOf(it)
                    folder != null && albumsPerFolder[folder] == 1 &&
                            it.songList.size >= minSongs && isRealAlbum(it)
                }
            }
        }
    }

    /** The album folder of an album, or null if its songs are spread over several folders. */
    private fun albumFolderOf(album: Album): String? {
        val folders = album.songList.mapTo(HashSet()) { song ->
            song.getFile()?.parentFile?.let { KTagFix.albumFolder(it).absolutePath }
        }
        return folders.singleOrNull()
    }

    /**
     * Android uses the parent folder name as album name when a file has no album tag. So a folder
     * of unrelated, untagged songs shows up as an "album" named after the folder. Treat an album
     * as fake when its name is just the folder name and none of its songs has a track number
     * (properly tagged album rips practically always have track numbers).
     */
    private fun isRealAlbum(album: Album): Boolean {
        val title = album.title?.trim()
        if (title.isNullOrEmpty() || title == "<unknown>") return false
        val looksLikeFolderFallback = album.songList.all {
            it.getFile()?.parentFile?.name?.trim() == title
        }
        if (!looksLikeFolderFallback) return true
        return album.songList.any { (it.mediaMetadata.trackNumber ?: 0) > 0 }
    }

    /**
     * True if tapping a song asks what to do (play only it / list from here / play next / add to
     * queue). The song menu then leaves those entries out.
     */
    fun songTapAsks(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getBooleanStrict(PREF_CONFIRM_REPLACE_QUEUE, true)

    /**
     * Handles a click on a song in a list.
     *  - "single" mode: queue is replaced by only the clicked song
     *  - "list" mode: queue is replaced by the whole list (upstream behaviour)
     * Unless turned off, ask first, also when nothing is playing.
     */
    fun playFromList(
        activity: MainActivity,
        songList: List<MediaItem>,
        position: Int,
        title: String,
        forceList: Boolean = false
    ) {
        val controller = activity.getPlayer() ?: return
        val clicked = songList[position]
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity.applicationContext)
        val single = !forceList &&
                prefs.getStringStrict(PREF_SONG_CLICK_MODE, CLICK_MODE_SINGLE) == CLICK_MODE_SINGLE
        val isCurrent = controller.currentMediaItem?.mediaId == clicked.mediaId

        if (single && isCurrent) {
            // Already playing this song, just show the player and keep the queue.
            activity.playerBottomSheet.open()
            return
        }

        fun replace(onlyThisSong: Boolean) {
            if (onlyThisSong) {
                controller.setMediaItemsWithTitle(listOf(clicked), 0, title)
            } else {
                controller.setMediaItemsSeamlessly(songList, position, title)
            }
            controller.prepare()
            controller.play()
            if (isCurrent) {
                activity.playerBottomSheet.open()
            }
        }

        // An explicit "play whole list from here" never asks.
        if (forceList || !songTapAsks(activity)) {
            replace(single)
            return
        }

        val songTitle = clicked.mediaMetadata.title ?: ""
        val count = controller.mediaItemCount
        val items = if (count == 0) arrayOf(
            activity.getString(R.string.k_song_click_single),
            activity.getString(R.string.k_play_list_from_here)
        ) else arrayOf(
            activity.getString(R.string.k_replace_queue_only_this, count),
            activity.getString(R.string.k_replace_queue_list_from_here, count),
            activity.getString(R.string.play_next),
            activity.getString(R.string.add_to_queue)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.k_replace_queue_title, songTitle))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> replace(true)
                    1 -> replace(false)
                    2 -> controller.addMediaItem(controller.currentMediaItemIndex + 1, clicked)
                    3 -> controller.addMediaItem(clicked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Playlist entries without duplicates (same file), keeping the first occurrence. Used when
     * adding songs to a playlist, so adding a song twice doesn't list it twice.
     */
    fun distinctPlaylistEntries(
        entries: List<PlaylistSerializer.Entry>
    ): List<PlaylistSerializer.Entry> = entries.distinctBy { it.locations.firstOrNull()?.toString() }
}
