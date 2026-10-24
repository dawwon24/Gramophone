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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.MutableStateFlow
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.ui.ItemHeightHelper
import org.akanework.gramophone.logic.ui.MyRecyclerView

/**
 * Gramophone K: search box shown as the first row of a list; writes into [query].
 */
class KSearchHeaderAdapter(private val query: MutableStateFlow<String>) :
    MyRecyclerView.Adapter<KSearchHeaderAdapter.ViewHolder>(), ItemHeightHelper {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: EditText = view.findViewById(R.id.k_search_text)
        val clear: ImageButton = view.findViewById(R.id.k_search_clear)
        var binding = false
        // view holders can be shared between tabs (shared view pool), so bind the target here
        var query: MutableStateFlow<String>? = null
    }

    override fun getItemCount() = 1
    override fun getItemViewType(position: Int) = R.layout.k_search_header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val holder = ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.k_search_header, parent, false))
        holder.text.doAfterTextChanged {
            val s = it?.toString() ?: ""
            holder.clear.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
            if (!holder.binding) holder.query?.value = s
        }
        holder.text.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                v.context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else false
        }
        holder.clear.setOnClickListener { holder.text.setText("") }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.query = query
        if (holder.text.text.toString() != query.value) {
            holder.binding = true
            holder.text.setText(query.value)
            holder.binding = false
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.query = null
        super.onViewRecycled(holder)
    }

    /** Call after [query] was changed from outside (e.g. cleared). */
    fun refresh() = notifyItemChanged(0)

    override fun getItemHeightFromZeroTo(to: Int): Int =
        if (to > 0) _height else 0

    private var _height = 0
    override fun onAttachedToRecyclerView(recyclerView: MyRecyclerView) {
        _height = recyclerView.resources.getDimensionPixelSize(R.dimen.k_search_header_height)
    }
}
