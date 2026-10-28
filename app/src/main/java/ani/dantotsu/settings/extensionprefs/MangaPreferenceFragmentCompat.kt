package ani.dantotsu.settings.extensionprefs

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.forEach
import androidx.preference.getOnBindEditTextListener
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.PreferenceScreen
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.manga.getPreferenceKey
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaSourcePreferencesFragment : PreferenceFragmentCompat() {

    private val sourceId: Long
        get() = arguments?.getLong(SOURCE_ID, NO_SOURCE) ?: NO_SOURCE

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val source = findSource(sourceId)
        if (source != null) {
            preferenceScreen = buildScreen(source)
            return
        }
        // Restored after process death (or opened while extensions were still loading): the
        // source map isn't filled yet. Show an empty screen and wait for it instead of crashing.
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val id = sourceId
        lifecycleScope.launch {
            val found = withTimeoutOrNull(SOURCE_WAIT_MS) {
                withContext(Dispatchers.IO) {
                    Injekt.get<MangaExtensionManager>().awaitInitialized()
                    Injekt.get<MangaSourceManager>().awaitInitialized()
                }
                findSource(id)
            }
            if (!isAdded || context == null) return@launch
            if (found != null) {
                preferenceScreen = buildScreen(found)
            } else {
                Toast.makeText(requireContext(), R.string.extension_source_unavailable, Toast.LENGTH_SHORT).show()
                if (!parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
            }
        }
    }

    override fun onDestroyView() {
        // Only a real close (popped/removed), not a rotation or process-death teardown.
        if (isRemoving && !requireActivity().isChangingConfigurations) {
            try {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY_CLOSED, bundleOf(SOURCE_ID to sourceId)
                )
            } catch (e: IllegalStateException) {
                // Host already gone.
            }
        }
        super.onDestroyView()
    }

    private fun findSource(id: Long): ConfigurableSource? {
        if (id == NO_SOURCE) return null
        return Injekt.get<MangaSourceManager>().get(id) as? ConfigurableSource
            ?: Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value
                .asSequence()
                .flatMap { it.sources.asSequence() }
                .firstOrNull { it.id == id } as? ConfigurableSource
    }

    private fun buildScreen(source: ConfigurableSource): PreferenceScreen {
        return try {
            populateMangaPreferenceScreen(source)
        } catch (e: Exception) {
            Logger.log(e)
            snackString(e.message ?: "Unknown error")
            preferenceManager.createPreferenceScreen(requireContext())
        }
    }

    private fun populateMangaPreferenceScreen(source: ConfigurableSource): PreferenceScreen {
        val sharedPreferences =
            requireContext().getSharedPreferences(source.getPreferenceKey(), Context.MODE_PRIVATE)
        val dataStore = SharedPreferencesDataStore(sharedPreferences)
        preferenceManager.preferenceDataStore = dataStore
        val sourceScreen = preferenceManager.createPreferenceScreen(requireContext())
        source.setupPreferenceScreen(sourceScreen)
        sourceScreen.forEach { pref ->
            pref.isIconSpaceReserved = false
            if (pref is DialogPreference) {
                pref.dialogTitle = pref.title
            }

            // Apply incognito IME for EditTextPreference
            if (pref is EditTextPreference) {
                val setListener = pref.getOnBindEditTextListener()
                pref.setOnBindEditTextListener {
                    setListener?.onBindEditText(it)
                    it.setIncognito(lifecycleScope)
                }
            }
        }

        return sourceScreen
    }

    companion object {
        private const val SOURCE_ID = "source_id"
        private const val NO_SOURCE = -1L
        private const val SOURCE_WAIT_MS = 5_000L

        /** Fragment result posted on the host's FragmentManager when the screen is closed. */
        const val REQUEST_KEY_CLOSED = "manga_source_preferences_closed"

        fun newInstance(sourceId: Long): MangaSourcePreferencesFragment =
            MangaSourcePreferencesFragment().apply {
                arguments = bundleOf(SOURCE_ID to sourceId)
            }
    }
}
