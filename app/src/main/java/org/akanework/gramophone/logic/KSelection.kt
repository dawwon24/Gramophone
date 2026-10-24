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

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.adapters.SongAdapter

/**
 * Gramophone K: multi selection in song lists. Long press a song to start, tap songs to
 * (de)select them. A bar over the top app bar offers play / add to playlist / queue actions.
 * Only one list can be in selection mode at a time; leaving the list (back, tab switch, opening
 * another page) ends it.
 */
object KSelection {
    /** RecyclerView payload: only the selection highlight changed. */
    val PAYLOAD = Any()

    private var adapter: SongAdapter? = null
    private var activity: MainActivity? = null
    private var lifecycle: Lifecycle? = null
    private val selected = LinkedHashMap<String, MediaItem>()
    private var bar: View? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = end()
    }
    private val backStackListener = FragmentManager.OnBackStackChangedListener { end() }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) = end()
    }

    fun isActive(adapter: SongAdapter) = this.adapter === adapter

    fun isSelected(adapter: SongAdapter, item: MediaItem) =
        this.adapter === adapter && selected.containsKey(item.mediaId)

    /** Long press: start selection mode with this song (or toggle it if already selecting). */
    fun start(adapter: SongAdapter, lifecycleOwner: LifecycleOwner, item: MediaItem) {
        if (this.adapter !== adapter) {
            end()
            val activity = adapter.getActivity()
            this.adapter = adapter
            this.activity = activity
            lifecycle = lifecycleOwner.lifecycle.also { it.addObserver(lifecycleObserver) }
            showBar(activity)
            backCallback.remove()
            activity.onBackPressedDispatcher.addCallback(activity, backCallback)
            backCallback.isEnabled = true
            activity.supportFragmentManager.addOnBackStackChangedListener(backStackListener)
        }
        toggle(item)
    }

    fun toggle(item: MediaItem) {
        if (selected.remove(item.mediaId) == null) selected[item.mediaId] = item
        if (selected.isEmpty()) end() else changed()
    }

    fun end() {
        val adapter = adapter ?: return
        this.adapter = null
        selected.clear()
        backCallback.isEnabled = false
        backCallback.remove()
        lifecycle?.removeObserver(lifecycleObserver)
        lifecycle = null
        activity?.supportFragmentManager?.removeOnBackStackChangedListener(backStackListener)
        activity = null
        bar?.let { b -> (b.parent as? ViewGroup)?.let { withoutTransition(it) { it.removeView(b) } } }
        bar = null
        adapter.kNotifySelectionChanged()
    }

    private fun changed() {
        bar?.findViewById<TextView>(R.id.k_sel_count)?.text = activity?.resources
            ?.getQuantityString(R.plurals.k_selection_count, selected.size, selected.size)
        adapter?.kNotifySelectionChanged()
    }

    /** Selected songs: in the order of the list as shown, then those not shown (e.g. filtered). */
    private fun selectedSongs(): List<MediaItem> {
        val shown = adapter?.getSongList().orEmpty().filter { selected.containsKey(it.mediaId) }
            .distinctBy { it.mediaId }
        val shownIds = shown.mapTo(HashSet()) { it.mediaId }
        return shown + selected.values.filter { it.mediaId !in shownIds }
    }

    private fun selectAll() {
        val shown = adapter?.getSongList().orEmpty()
        if (shown.all { selected.containsKey(it.mediaId) }) {
            shown.forEach { selected.remove(it.mediaId) }
            if (selected.isEmpty()) { end(); return }
        } else {
            shown.forEach { selected[it.mediaId] = it }
        }
        changed()
    }

    private fun play(shuffle: Boolean) {
        val adapter = adapter ?: return
        val controller = adapter.getActivity().getPlayer() ?: return
        val songs = selectedSongs().let { if (shuffle) it.shuffled() else it }
        controller.setMediaItemsWithTitle(songs, 0, adapter.kQueueTitle())
        controller.prepare()
        controller.play()
        end()
    }

    // the root has animateLayoutChanges; its change animation would move the player sheet
    private inline fun withoutTransition(parent: ViewGroup, block: () -> Unit) {
        val transition = parent.layoutTransition
        parent.layoutTransition = null
        try { block() } finally { parent.layoutTransition = transition }
    }

    private fun showBar(activity: MainActivity) {
        val root = activity.findViewById<CoordinatorLayout>(R.id.rootView)
        val bar = activity.layoutInflater.inflate(R.layout.k_selection_bar, root, false)
        // below the player sheet, so an opened player still covers it
        val index = root.indexOfChild(activity.playerBottomSheet).takeIf { it >= 0 } ?: root.childCount
        withoutTransition(root) {
            root.addView(bar, index, CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.TOP })
        }
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = i.top)
            insets
        }
        ViewCompat.requestApplyInsets(bar)
        this.bar = bar

        bar.findViewById<View>(R.id.k_sel_close).setOnClickListener { end() }
        bar.findViewById<View>(R.id.k_sel_all).setOnClickListener { selectAll() }
        bar.findViewById<View>(R.id.k_sel_play).setOnClickListener { play(false) }
        bar.findViewById<View>(R.id.k_sel_playlist).setOnClickListener {
            activity.addToPlaylistDialog(selectedSongs())
            end()
        }
        bar.findViewById<View>(R.id.k_sel_more).setOnClickListener { v ->
            PopupMenu(activity, v).apply {
                menu.add(0, 1, 0, R.string.shuffle)
                menu.add(0, 2, 1, R.string.play_next)
                menu.add(0, 3, 2, R.string.add_to_queue)
                setOnMenuItemClickListener {
                    val controller = activity.getPlayer()
                    val songs = selectedSongs()
                    when (it.itemId) {
                        1 -> { play(true); return@setOnMenuItemClickListener true }
                        2 -> controller?.addMediaItems(controller.currentMediaItemIndex + 1, songs)
                        3 -> controller?.addMediaItems(songs)
                    }
                    end()
                    true
                }
                show()
            }
        }
    }
}
