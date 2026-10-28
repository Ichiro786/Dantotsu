package ani.dantotsu.settings.extensionprefs

import android.content.Context
import android.content.SharedPreferences
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
import ani.dantotsu.getThemeColor
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.source.anime.getPreferenceKey
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeSourcePreferencesFragment : PreferenceFragmentCompat() {

    private val sourceId: Long
        get() = arguments?.getLong(SOURCE_ID, NO_SOURCE) ?: NO_SOURCE

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val source = findSource(sourceId)
        if (source != null) {
            preferenceScreen = buildScreen(source)
            return
        }
        // Restored after process death (or opened while extensions were still loading): wait for
        // the source map instead of failing.
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val id = sourceId
        lifecycleScope.launch {
            val found = withTimeoutOrNull(SOURCE_WAIT_MS) {
                withContext(Dispatchers.IO) {
                    Injekt.get<AnimeExtensionManager>().awaitInitialized()
                    Injekt.get<AnimeSourceManager>().awaitInitialized()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(requireActivity()).applyTheme()
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

    private fun findSource(id: Long): ConfigurableAnimeSource? {
        if (id == NO_SOURCE) return null
        return Injekt.get<AnimeSourceManager>().get(id) as? ConfigurableAnimeSource
            ?: Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value
                .asSequence()
                .flatMap { it.sources.asSequence() }
                .firstOrNull { it.id == id } as? ConfigurableAnimeSource
    }

    private fun buildScreen(source: ConfigurableAnimeSource): PreferenceScreen {
        return try {
            populateAnimePreferenceScreen(source)
        } catch (e: Exception) {
            Logger.log(e)
            snackString(e.message ?: "Unknown error")
            preferenceManager.createPreferenceScreen(requireContext())
        }
    }

    private fun populateAnimePreferenceScreen(source: ConfigurableAnimeSource): PreferenceScreen {
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

            if (pref is EditTextPreference) {
                val setListener = pref.getOnBindEditTextListener()
                pref.setOnBindEditTextListener {
                    setListener?.onBindEditText(it)
                    it.setIncognito(lifecycleScope)
                }
            }
        }
        val downloadResPref = androidx.preference.Preference(requireContext()).apply {
            key = "pref_download_resolution_priority"
            title = requireContext().getString(R.string.custom_extension_resolution_priority)
            val current = ani.dantotsu.settings.saving.PrefManager.getPreferredDownloadResolutions(source.name)
            summary = current.joinToString(", ")
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                ani.dantotsu.settings.ResolutionPriorityDialog.show(requireContext(), source.name) { updated ->
                    summary = updated.joinToString(", ")
                }
                true
            }
        }
        sourceScreen.addPreference(downloadResPref)
        return sourceScreen
    }

    companion object {
        private const val SOURCE_ID = "source_id"
        private const val NO_SOURCE = -1L
        private const val SOURCE_WAIT_MS = 5_000L

        /** Fragment result posted on the host's FragmentManager when the screen is closed. */
        const val REQUEST_KEY_CLOSED = "anime_source_preferences_closed"

        fun newInstance(sourceId: Long): AnimeSourcePreferencesFragment =
            AnimeSourcePreferencesFragment().apply {
                arguments = bundleOf(SOURCE_ID to sourceId)
            }
    }
}

class InitialAnimeSourcePreferencesFragment(
    val sharedPreferences: SharedPreferences,
    val source: ConfigurableAnimeSource,
    val currContext: Context
) : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = try {
            populateAnimePreferenceScreen()
        } catch (e: Exception) {
            snackString(e.message ?: "Unknown error")
            preferenceManager.createPreferenceScreen(requireContext())
        }
        //set background color
        val color =
            requireContext().getThemeColor(com.google.android.material.R.attr.backgroundColor)
        view?.setBackgroundColor(color)
    }


    fun populateAnimePreferenceScreen(): PreferenceScreen {
        val dataStore = SharedPreferencesDataStore(sharedPreferences)
        preferenceManager.preferenceDataStore = dataStore
        val sourceScreen = preferenceManager.createPreferenceScreen(requireContext())
        source.setupPreferenceScreen(sourceScreen)
        val downloadResPref = androidx.preference.Preference(requireContext()).apply {
            key = "pref_download_resolution_priority"
            title = requireContext().getString(R.string.custom_extension_resolution_priority)
            val current = ani.dantotsu.settings.saving.PrefManager.getPreferredDownloadResolutions(source.name)
            summary = current.joinToString(", ")
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                ani.dantotsu.settings.ResolutionPriorityDialog.show(requireContext(), source.name) { updated ->
                    summary = updated.joinToString(", ")
                }
                true
            }
        }
        sourceScreen.addPreference(downloadResPref)
        return sourceScreen
    }
}
