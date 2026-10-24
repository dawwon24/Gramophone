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

package org.akanework.gramophone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.PopupTextProvider
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.KCustomOrder
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.DefaultItemHeightHelper
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.ui.adapters.SongAdapter
import org.akanework.gramophone.ui.adapters.Sorter

/**
 * ArtistSubFragment:
 *   Separated from GeneralSubFragment and will be
 * merged into it in future development.
 *
 * @author nift4
 * @see BaseFragment
 * @see GeneralSubFragment
 */
class ArtistSubFragment : BaseFragment(true), PopupTextProvider {
    companion object {
        private const val TAG = "ArtistSubFragment"
    }

    private lateinit var songAdapter: SongAdapter
    private var recyclerView: MyRecyclerView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_general_sub, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()

        val id = requireArguments().getString("Id")?.toLong()
        val itemType = requireArguments().getInt("Item")
        recyclerView = rootView.findViewById(R.id.recyclerview)

        val item = mainActivity.reader.let {
            if (itemType == R.id.album_artist)
                it.albumArtistListFlow else it.artistListFlow
        }.map { it.find { it.id == id } }
        val title = item.map { it?.title ?: requireContext().getString(R.string.unknown_artist) }
        // Gramophone K: songs only (no album section), with user defined order
        songAdapter = SongAdapter(
            this,
            title,
            item.map { it?.songList },
            isSubFragment = itemType,
            rawOrderExposed = Sorter.Type.NaturalOrder,
            kOrderKey = { songs ->
                KCustomOrder.artistKey(
                    if (itemType == R.id.album_artist) songs.firstNotNullOfOrNull {
                        it.mediaMetadata.albumArtist?.toString() } ?: songs.firstOrNull()
                        ?.mediaMetadata?.artist?.toString()
                    else songs.firstOrNull()?.mediaMetadata?.artist?.toString())
            }
        )
        recyclerView!!.enableEdgeToEdgePaddingListener()
        recyclerView!!.adapter = songAdapter.concatAdapter
        recyclerView!!.setAppBar(appBarLayout)
        recyclerView!!.fastScroll(this, songAdapter.itemHeightHelper)

        topAppBar.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        lifecycleScope.launch(Dispatchers.Default) {
            title.collect {
                withContext(Dispatchers.Main) {
                    // Show title text.
                    topAppBar.title = it
                }
            }
        }

        return rootView
    }

    override fun getPopupText(view: View, position: Int): CharSequence {
        return songAdapter.getPopupText(view, position)
    }
}
