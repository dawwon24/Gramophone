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
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray

/**
 * Gramophone K: user defined song order for a folder or an artist.
 * Stored as file paths (stable across MediaStore rescans), in its own preferences file so it
 * can be backed up with the rest.
 */
object KCustomOrder {
    const val PREFS_NAME = "k_custom_order"

    private val version = MutableStateFlow(0)
    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }

    fun folderKey(songs: List<MediaItem>): String? =
        songs.firstOrNull()?.getFile()?.parentFile?.absolutePath?.let { "folder:$it" }

    fun artistKey(name: String?): String? =
        name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "artist:$it" }

    fun has(context: Context, key: String?): Boolean =
        key != null && prefs(context).contains(key)

    fun get(context: Context, key: String): List<String>? {
        val raw = prefs(context).getString(key, null) ?: return null
        return try {
            val a = JSONArray(raw)
            List(a.length()) { a.getString(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, key: String, songs: List<MediaItem>) {
        val a = JSONArray()
        songs.forEach { s -> s.getFile()?.absolutePath?.let { a.put(it) } }
        prefs(context).edit { putString(key, a.toString()) }
        version.value++
    }

    fun reset(context: Context, key: String) {
        prefs(context).edit { remove(key) }
        version.value++
    }

    /** Called after a restore replaced the stored orders. */
    fun notifyChanged() {
        version.value++
    }

    /** Songs in the saved order (if any); songs not in the saved order keep their order at the end. */
    fun apply(context: Context, key: String?, songs: List<MediaItem>): List<MediaItem> {
        if (key == null) return songs
        val order = get(context, key) ?: return songs
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { i, p -> rank.putIfAbsent(p, i) }
        return songs.withIndex().sortedWith(compareBy(
            { rank[it.value.getFile()?.absolutePath] ?: Int.MAX_VALUE }, { it.index }
        )).map { it.value }
    }

    fun Flow<List<MediaItem>?>.withCustomOrder(
        context: Context,
        keyOf: (List<MediaItem>) -> String?
    ): Flow<List<MediaItem>?> = combine(version) { list, _ ->
        list?.let { apply(context, keyOf(it), it) }
    }
}
