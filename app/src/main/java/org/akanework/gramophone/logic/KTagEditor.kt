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

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.StringRes
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.MainActivity
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.id3.valuepair.TextEncoding
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume

/**
 * Gramophone K: edit the tags in the music files (album rename, song details).
 *
 * The file is copied to the cache, edited there with jaudiotagger and written back through
 * MediaStore (Android 11+ asks the user once per batch for write access).
 */
object KTagEditor {
    private const val TAG = "KTagEditor"

    enum class Field(val key: FieldKey, @StringRes val label: Int, val numeric: Boolean = false) {
        TITLE(FieldKey.TITLE, R.string.dialog_title),
        ARTIST(FieldKey.ARTIST, R.string.dialog_artist),
        ALBUM(FieldKey.ALBUM, R.string.dialog_album),
        ALBUM_ARTIST(FieldKey.ALBUM_ARTIST, R.string.dialog_album_artist),
        GENRE(FieldKey.GENRE, R.string.dialog_genre),
        YEAR(FieldKey.YEAR, R.string.dialog_year, true),
        TRACK(FieldKey.TRACK, R.string.dialog_track_number, true),
        DISC(FieldKey.DISC_NO, R.string.dialog_disc_number, true),
    }

    val SONG_FIELDS = Field.entries.toList()
    val ALBUM_FIELDS = listOf(Field.ALBUM, Field.ALBUM_ARTIST, Field.GENRE, Field.YEAR)

    // needs java.nio.file (API 26) and MediaStore.createWriteRequest (API 30)
    val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private var initDone = false

    fun valueOf(item: MediaItem, field: Field): String {
        val m = item.mediaMetadata
        return when (field) {
            Field.TITLE -> m.title
            Field.ARTIST -> m.artist
            Field.ALBUM -> m.albumTitle
            Field.ALBUM_ARTIST -> m.albumArtist
            Field.GENRE -> m.genre
            Field.YEAR -> (m.releaseYear ?: m.recordingYear)?.toString()
            Field.TRACK -> m.trackNumber?.toString()
            Field.DISC -> m.discNumber?.toString()
        }?.toString() ?: ""
    }

    /**
     * Dialog with one text box per field. A field whose songs have different values starts
     * empty and stays untouched unless something is typed. [then] gets true after saving.
     */
    fun showDialog(
        activity: MainActivity, title: CharSequence, songs: List<MediaItem>,
        fields: List<Field>, then: ((Boolean) -> Unit)? = null
    ) {
        if (songs.isEmpty()) return
        activity.lifecycleScope.launch {
            // MediaStore fills in missing tags (album = folder name, track 0) and misses some
            // (year of ID3v2.4), so read the files too; the library values are preferred as they
            // include the mojibake repair
            val raw = withContext(Dispatchers.IO) {
                init()
                songs.map { readFields(it, fields) }
            }
            if (raw.any { it == null }) {
                Toast.makeText(activity, R.string.k_tag_unsupported_format, Toast.LENGTH_LONG).show()
                return@launch
            }
            showDialog(activity, title, songs, fields, raw.map { it!! }, then)
        }
    }

    private fun readFields(song: MediaItem, fields: List<Field>): Map<Field, String>? = try {
        val tag = AudioFileIO.read(song.getFile()!!).tag
        fields.associateWith { tag?.getFirst(it.key)?.trim() ?: "" }
    } catch (e: Exception) {
        Log.w(TAG, "can't read tags of ${song.getFile()}", e)
        null
    }

    private fun showDialog(
        activity: MainActivity, title: CharSequence, songs: List<MediaItem>,
        fields: List<Field>, raw: List<Map<Field, String>>, then: ((Boolean) -> Unit)?
    ) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (resources.displayMetrics.density * 24).toInt()
            setPadding(p, p / 3, p, 0)
        }
        val inflater = LayoutInflater.from(activity)
        val boxes = fields.map { field ->
            val values = songs.indices.mapTo(LinkedHashSet()) {
                val fileValue = raw[it][field]!!
                if (fileValue.isEmpty()) "" else valueOf(songs[it], field).ifEmpty { fileValue }
            }
            val initial = values.singleOrNull() ?: ""
            val til = inflater.inflate(R.layout.k_tag_field, container, false) as TextInputLayout
            til.hint = activity.getString(field.label)
            if (values.size > 1) til.helperText = activity.getString(R.string.k_tag_mixed)
            val et = til.editText!!
            if (field.numeric) et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(initial)
            container.addView(til)
            Triple(field, initial, til)
        }
        val scroll = NestedScrollView(activity).apply { addView(container) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.k_tag_save) { _, _ ->
                val changes = boxes.mapNotNull { (field, initial, til) ->
                    val v = til.editText!!.text.toString().trim()
                    if (v != initial.trim()) field to v else null
                }.toMap()
                edit(activity, songs, changes, then)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun edit(
        activity: MainActivity, songs: List<MediaItem>, changes: Map<Field, String>,
        then: ((Boolean) -> Unit)? = null
    ) {
        if (changes.isEmpty() || songs.isEmpty()) {
            then?.invoke(false)
            return
        }
        if (!isSupported) {
            Toast.makeText(activity, R.string.k_tag_unsupported, Toast.LENGTH_LONG).show()
            then?.invoke(false)
            return
        }
        val uris = songs.map {
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.getContentUri("external"), it.requireMediaStoreId()
            )
        }
        val missing = uris.filter {
            activity.checkUriPermission(
                it, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            write(activity, songs, uris, changes, then)
            return
        }
        val pendingIntent = MediaStore.createWriteRequest(activity.contentResolver, missing)
        activity.kTagWriteCallback = { ok ->
            if (ok) write(activity, songs, uris, changes, then) else then?.invoke(false)
        }
        activity.kTagWriteRequest.launch(
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        )
    }

    private fun init() {
        if (initDone) return
        initDone = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        TagOptionSingleton.getInstance().apply {
            isAndroid = true
            iD3V2Version = ID3V2Version.ID3_V23
            // ISO-8859-1 can't hold Korean / Japanese text
            id3v23DefaultTextEncoding = TextEncoding.UTF_16
            id3v24DefaultTextEncoding = TextEncoding.UTF_8
        }
    }

    private fun write(
        activity: MainActivity, songs: List<MediaItem>, uris: List<Uri>,
        changes: Map<Field, String>, then: ((Boolean) -> Unit)?
    ) {
        val app = activity.applicationContext
        if (songs.size > 1) Toast.makeText(app, R.string.k_tag_saving, Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            init()
            var failed = 0
            val paths = ArrayList<String>()
            songs.zip(uris).forEach { (song, uri) ->
                try {
                    writeOne(activity, song, uri, changes)
                    song.getFile()?.path?.let { paths.add(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "failed to edit tags of ${song.getFile()}", e)
                    failed++
                }
            }
            if (paths.isNotEmpty()) {
                // writing through MediaStore already rescans the file, this is just to be sure
                withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine { cont ->
                        var left = paths.size
                        MediaScannerConnection.scanFile(app, paths.toTypedArray(), null) { _, _ ->
                            synchronized(paths) {
                                if (--left == 0 && cont.isActive) cont.resume(Unit)
                            }
                        }
                    }
                }
                activity.gramophoneApplication.reader.refresh()
            }
            withContext(Dispatchers.Main) {
                val msg = if (failed == 0)
                    app.resources.getQuantityString(R.plurals.k_tag_saved, songs.size, songs.size)
                else app.resources.getQuantityString(R.plurals.k_tag_failed, failed, failed)
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                then?.invoke(failed == 0)
            }
        }
    }

    private fun writeOne(activity: MainActivity, song: MediaItem, uri: Uri, changes: Map<Field, String>) {
        val cr = activity.contentResolver
        val ext = song.getFile()?.extension?.ifEmpty { null }
            ?: throw IllegalArgumentException("no file extension")
        val dir = File(activity.cacheDir, "k_tag_edit").apply { mkdirs() }
        val tmp = File(dir, "edit.$ext")
        val backup = File(dir, "backup.$ext")
        var keepBackup = false
        try {
            cr.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.copyTo(backup, overwrite = true)
            val audioFile = AudioFileIO.read(tmp)
            if (audioFile is MP3File && audioFile.hasID3v2Tag() && audioFile.iD3v2Tag !is ID3v23Tag) {
                // Android's media scanner doesn't read the year of ID3v2.4 (TDRC), only v2.3 (TYER)
                audioFile.tag = ID3v23Tag(audioFile.iD3v2Tag)
            }
            val tag = audioFile.tagOrCreateAndSetDefault
            for ((field, value) in changes) {
                if (value.isEmpty()) tag.deleteField(field.key) else tag.setField(field.key, value)
            }
            audioFile.commit()
            try {
                cr.openOutputStream(uri, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            } catch (e: Exception) {
                // don't leave a half written file behind
                try {
                    cr.openOutputStream(uri, "wt")!!.use { o ->
                        backup.inputStream().use { it.copyTo(o) }
                    }
                } catch (e2: Exception) {
                    keepBackup = true
                    val kept = File(activity.getExternalFilesDir("k_tag_backup"),
                        song.getFile()!!.name)
                    backup.copyTo(kept, overwrite = true)
                    Log.e(TAG, "failed to restore $uri, original kept at $kept", e2)
                }
                throw e
            }
        } finally {
            tmp.delete()
            if (!keepBackup) backup.delete()
        }
    }
}
