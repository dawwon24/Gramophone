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

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.KCustomOrder
import org.akanework.gramophone.logic.ui.MyRecyclerView

/**
 * Gramophone K: dialog to drag songs of a folder / artist into a custom order.
 */
object KOrderEditor {
    fun show(context: Context, key: String, songs: List<MediaItem>, onSaved: () -> Unit) {
        val list = songs.toMutableList()
        val recyclerView = MyRecyclerView(context)
        lateinit var touchHelper: ItemTouchHelper
        val adapter = object : EditSongAdapter(context, false) {
            override fun getItemCount() = list.size
            override fun getItem(pos: Int) = list[pos]
            override fun onClick(pos: Int) {}
            override fun removeItem(pos: Int) {}
            override fun startDrag(holder: ViewHolder) {
                touchHelper.startDrag(holder)
            }

            override fun onRowMoved(from: Int, to: Int) {
                list.add(to, list.removeAt(from))
                notifyItemMoved(from, to)
            }

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.closeButton.visibility = View.GONE
            }
        }
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = true
            override fun isItemViewSwipeEnabled() = false
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.onRowMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
        touchHelper.attachToRecyclerView(recyclerView)

        val container = FrameLayout(context)
        container.addView(recyclerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        ))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.k_edit_order)
            .setMessage(R.string.k_edit_order_hint)
            .setView(container)
            .setPositiveButton(R.string.k_save) { _, _ ->
                KCustomOrder.save(context, key, list)
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
