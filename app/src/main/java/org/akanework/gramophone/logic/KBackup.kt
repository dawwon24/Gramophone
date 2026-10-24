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
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import uk.akane.libphonograph.manipulator.ItemManipulator
import uk.akane.libphonograph.manipulator.PlaylistSerializer
import uk.akane.libphonograph.toUriCompat
import java.io.File

/**
 * Gramophone K: back up / restore settings, user defined song orders and playlists to one
 * JSON file chosen by the user.
 */
object KBackup {
    private const val TAG = "KBackup"
    private const val FORMAT = "gramophone-k-backup"

    data class Result(val playlists: Int, val skippedPlaylists: Int)

    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in prefs.all) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    private fun jsonToPrefs(o: JSONObject, prefs: SharedPreferences) {
        val ed = prefs.edit().clear()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "ss" -> e.getJSONArray("v").let { a ->
                    ed.putStringSet(k, (0 until a.length()).mapTo(HashSet()) { a.getString(it) })
                }
            }
        }
        ed.commit()
    }

    suspend fun export(context: Context, out: Uri): Result {
        val root = JSONObject()
        root.put("format", FORMAT).put("version", 1)
        root.put("settings", prefsToJson(PreferenceManager.getDefaultSharedPreferences(context)))
        root.put("customOrder", prefsToJson(
            context.getSharedPreferences(KCustomOrder.PREFS_NAME, Context.MODE_PRIVATE)))
        val playlists = JSONArray()
        context.gramophoneApplication.reader.playlistListFlow.first()
            .filter { it.path != null && it.title != null } // skip dynamic ones (recently added)
            .forEach { p ->
                playlists.put(JSONObject()
                    .put("name", p.title)
                    .put("path", p.path!!.absolutePath)
                    .put("songs", JSONArray(p.songList.mapNotNull { it.getFile()?.absolutePath })))
            }
        root.put("playlists", playlists)
        context.contentResolver.openOutputStream(out, "wt")!!.use {
            it.write(root.toString(2).toByteArray())
        }
        return Result(playlists.length(), 0)
    }

    /** Restores everything; playlists which already exist are left alone. */
    suspend fun import(context: Context, uri: Uri): Result {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            .toString(Charsets.UTF_8)
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT)
            throw IllegalArgumentException("not a Gramophone K backup")
        jsonToPrefs(root.getJSONObject("settings"),
            PreferenceManager.getDefaultSharedPreferences(context))
        jsonToPrefs(root.getJSONObject("customOrder"),
            context.getSharedPreferences(KCustomOrder.PREFS_NAME, Context.MODE_PRIVATE))
        KCustomOrder.notifyChanged()

        val existing = context.gramophoneApplication.reader.playlistListFlow.first()
            .mapNotNull { it.title }.toSet()
        var created = 0
        var skipped = 0
        val playlists = root.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until playlists.length()) {
            val p = playlists.getJSONObject(i)
            val name = p.getString("name")
            val file = p.optString("path").takeIf { it.isNotEmpty() }?.let { File(it) }
                ?: ItemManipulator.getDefaultPlaylistFile(name)
            if (name in existing || file.exists()) {
                skipped++
                continue
            }
            try {
                val songs = p.getJSONArray("songs")
                val entries = (0 until songs.length()).map {
                    PlaylistSerializer.Entry(listOf(File(songs.getString(it)).toUriCompat()))
                }
                val out = ItemManipulator.createPlaylist(context, file)
                ItemManipulator.setPlaylistContent(context, out,
                    PlaylistSerializer.Playlist.create().copy(entries = entries), true)
                created++
            } catch (e: Exception) {
                Log.e(TAG, "failed to restore playlist $name", e)
                skipped++
            }
        }
        return Result(created, skipped)
    }
}
