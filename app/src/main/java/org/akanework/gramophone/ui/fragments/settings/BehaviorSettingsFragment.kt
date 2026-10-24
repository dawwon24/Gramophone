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

package org.akanework.gramophone.ui.fragments.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.KBackup
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.GramophoneK
import org.akanework.gramophone.logic.hasImagePermission
import org.akanework.gramophone.logic.hasScopedStorageWithMediaTypes
import org.akanework.gramophone.logic.utils.Flags
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity


class BehaviorSettingsActivity : BaseSettingsActivity(
    R.string.settings_category_behavior,
    { BehaviorSettingsFragment() })

class BehaviorSettingsFragment : BasePreferenceFragment() {

    // Gramophone K: backup / restore
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
            it?.let { uri -> runBackup(true, uri) }
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { uri -> runBackup(false, uri) }
        }

    private fun runBackup(export: Boolean, uri: Uri) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val msg = try {
                val r = withContext(Dispatchers.IO) {
                    if (export) KBackup.export(ctx, uri) else KBackup.import(ctx, uri)
                }
                if (export) getString(R.string.k_backup_export_done, r.playlists)
                else getString(R.string.k_backup_import_done, r.playlists, r.skippedPlaylists)
            } catch (e: Exception) {
                getString(R.string.k_backup_failed, e.javaClass.simpleName + ": " + e.message)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasScopedStorageWithMediaTypes()) {
            val preference = findPreference<SwitchPreferenceCompat>("album_covers")!!
            preference.isPersistent = false
            if (!Flags.REMOVE_IMAGE_PERMISSION)
                preference.isChecked = requireContext().hasImagePermission()
            else
                preference.isVisible = false
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
        // Gramophone K: labels for album filter values ("1" = show all)
        findPreference<ListPreference>(GramophoneK.PREF_ALBUM_MIN_SONGS)?.let { p ->
            p.entries = p.entryValues.map {
                val n = it.toString().toInt()
                if (n <= 1) getString(R.string.k_album_min_all) else getString(R.string.k_album_min_n, n)
            }.toTypedArray()
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "k_backup_export") {
            exportLauncher.launch("gramophone-k-backup.json")
        }
        if (preference.key == "k_backup_import") {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        if (preference.key == "blacklist") {
            startActivity(BlacklistSettingsActivity::class.java)
        }
        // Prior to Android 13, this changes a setting which changes MediaStoreUtils behaviour
        // Android 13 and later, this displays state of images permission granted/denied
        if (hasScopedStorageWithMediaTypes() && !Flags.REMOVE_IMAGE_PERMISSION &&
            preference.key == "album_covers") {
            Toast.makeText(
                requireActivity(), if (requireContext().hasImagePermission())
                    R.string.deny_images else R.string.grant_images, Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData("package:${requireContext().packageName}".toUri())
            startActivity(intent)
        }
        return super.onPreferenceTreeClick(preference)
    }
}
