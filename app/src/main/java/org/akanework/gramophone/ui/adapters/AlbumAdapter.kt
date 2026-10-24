/*
 *     Copyright (C) 2024 Akane Foundation
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

package org.akanework.gramophone.ui.adapters

import android.net.Uri
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.iterator
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.GramophoneK.filterAlbumsForTab
import org.akanework.gramophone.logic.KTagEditor
import org.akanework.gramophone.logic.getFile
import org.akanework.gramophone.logic.requireMediaStoreId
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.fragments.GeneralSubFragment
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.manipulator.ItemManipulator
import java.util.GregorianCalendar

class AlbumAdapter(
    fragment: Fragment,
    val queueTitle: Flow<String>,
    // Gramophone K: album tab hides loose singles (see GramophoneK.filterAlbumsForTab)
    liveData: Flow<List<Album>?> = (fragment.requireActivity() as MainActivity).reader.albumListFlow
        .filterAlbumsForTab(fragment.requireContext()),
    isSubFragment: Int? = null
) : BaseAdapter<Album>
    (
    fragment,
    liveData = liveData,
    sortHelper = StoreAlbumHelper,
    naturalOrderHelper = null,
    initialSortType = Sorter.Type.ByTitleAscending,
    pluralStr = R.plurals.albums,
    defaultLayoutType = LayoutType.GRID,
    isSubFragment = isSubFragment
) {

    // Gramophone K: plain list without covers
    override val canChangeLayout = false
    override val kHideListCover = true

    init {
        lateInit()
        layoutType = LayoutType.LIST
    }

    fun getAlbumList() = list?.second ?: emptyList()

    fun getActivity() = mainActivity

    override fun virtualTitleOf(item: Album): String {
        return context.getString(R.string.unknown_album)
    }

    // Gramophone K: no album artist because the songs have different artists (soundtracks,
    // compilations) -> "various artists" instead of "unknown artist"
    override fun kNoArtistText(item: Album): String =
        if (item.songList.mapNotNullTo(HashSet()) { it.mediaMetadata.artist?.toString() }.size > 1)
            context.getString(R.string.k_various_artists)
        else super.kNoArtistText(item)

    override fun onClick(item: Album, position: Int) {
        mainActivity.startFragment(GeneralSubFragment()) {
            putString("Id", item.id?.toString())
            putInt("Item", R.id.album)
        }
    }

    override fun onMenu(item: Album, popupMenu: PopupMenu) {
        popupMenu.inflate(R.menu.more_menu)
        popupMenu.menu.iterator().forEach {
            it.isVisible = it.itemId == R.id.play_next || it.itemId == R.id.add_to_queue
                    || it.itemId == R.id.delete
                    // Gramophone K: album rename etc. (writes the tags of all songs)
                    || (it.itemId == R.id.k_edit_tags && KTagEditor.isSupported)
        }
        popupMenu.menu.findItem(R.id.k_edit_tags).setTitle(R.string.k_edit_album)
        popupMenu.setOnMenuItemClickListener { it1 ->
            when (it1.itemId) {
                R.id.play_next -> {
                    val mediaController = mainActivity.getPlayer()
                    mediaController?.addMediaItems(
                        mediaController.currentMediaItemIndex + 1,
                        item.songList,
                    )
                }

                R.id.add_to_queue -> {
                    val mediaController = mainActivity.getPlayer()
                    mediaController?.addMediaItems(
                        item.songList,
                    )
                }

                R.id.k_edit_tags -> {
                    KTagEditor.showDialog(
                        mainActivity, context.getString(R.string.k_edit_album),
                        item.songList, KTagEditor.ALBUM_FIELDS
                    )
                }

                R.id.delete -> {
                    CoroutineScope(Dispatchers.Default).launch {
                        val res = ItemManipulator.deleteSongs(
                            mainActivity,
                            item.songList.map { it.getFile()!! to it.requireMediaStoreId() }
                        )
                        if (res != null) {
                            withContext(Dispatchers.Main) {
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.delete)
                                    .setMessage(
                                        context.getString(
                                            R.string.delete_really,
                                            item.title
                                        )
                                    )
                                    .setPositiveButton(R.string.delete) { _, _ ->
                                        res.invoke()
                                    }
                                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                                    .show()
                            }
                        }
                    }
                }

                /*
				R.id.share -> {
					val builder = ShareCompat.IntentBuilder(mainActivity)
					val mimeTypes = mutableSetOf<String>()
					builder.addStream(viewModel.fileUriList.value?.get(songList[holder.bindingAdapterPosition].mediaId.toLong())!!)
					mimeTypes.add(viewModel.mimeTypeList.value?.get(songList[holder.bindingAdapterPosition].mediaId.toLong())!!)
					builder.setType(mimeTypes.singleOrNull() ?: "audio/*").startChooser()
				 } */
				 */
            }
            true
        }
    }

    object StoreAlbumHelper : StoreItemHelper<Album>(
        setOf(
            Sorter.Type.ByTitleDescending, Sorter.Type.ByTitleAscending,
            Sorter.Type.ByArtistDescending, Sorter.Type.ByArtistAscending,
            Sorter.Type.ByArtistYearDescending, Sorter.Type.ByArtistYearAscending,
            Sorter.Type.BySizeDescending, Sorter.Type.BySizeAscending,
            Sorter.Type.ByReleaseDateAscending, Sorter.Type.ByReleaseDateDescending,
            Sorter.Type.ByAddDateAscending, Sorter.Type.ByAddDateDescending,
            Sorter.Type.ByModifiedDateAscending, Sorter.Type.ByModifiedDateDescending
        )
    ) {
        override fun getArtist(item: Album): String? {
            return item.albumArtist
        }

        override fun getCover(item: Album): Uri? {
            return item.cover
        }

        override fun getReleaseDate(item: Album): Long {
            return GregorianCalendar(item.albumYear ?: 0, 0, 0, 0, 0, 0).timeInMillis
        }

        override fun getAddDate(item: Album): Long {
            return item.albumAddDate ?: -1
        }

        override fun getModifiedDate(item: Album): Long {
            return item.albumModifiedDate ?: -1
        }
    }
}
