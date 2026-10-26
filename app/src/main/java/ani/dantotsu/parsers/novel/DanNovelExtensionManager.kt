package ani.dantotsu.parsers.novel

import android.content.Context
import android.graphics.drawable.Drawable
import ani.dantotsu.media.MediaType
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.Observable
import tachiyomi.core.util.lang.withUIContext

/**
 * Dedicated manager for APK-based Dantotsu Novel extensions.
 * Handles scanning installed APKs, fetching available APK extensions from GitHub,
 * and installing/updating/uninstalling APK packages.
 */
class DanNovelExtensionManager(private val context: Context) {

    var isInitialized = false
        private set

    private val _isInitializedFlow = MutableStateFlow(false)
    val isInitializedFlow = _isInitializedFlow.asStateFlow()

    suspend fun awaitInitialized() {
        if (isInitialized) return
        _isInitializedFlow.first { it }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val api = ExtensionGithubApi()
    private val installer by lazy { ExtensionInstaller(context) }
    private val iconMap = mutableMapOf<String, Drawable>()

    private val _installedNovelExtensionsFlow =
        MutableStateFlow(emptyList<NovelExtension.Installed>())
    val installedExtensionsFlow = _installedNovelExtensionsFlow.asStateFlow()

    private val _availableNovelExtensionsFlow =
        MutableStateFlow(emptyList<NovelExtension.Available>())
    val availableExtensionsFlow = _availableNovelExtensionsFlow.asStateFlow()

    private var availableNovelExtensionsSourcesData: Map<Long, NovelSourceData> = emptyMap()

    fun getAppIcon(pkgName: String): Drawable? {
        return iconMap[pkgName]
            ?: try {
                iconMap.getOrPut(pkgName) { context.packageManager.getApplicationIcon(pkgName) }
            } catch (_: Exception) {
                null
            }
    }

    init {
        scope.launch {
            initNovelExtensions()
            ExtensionInstallReceiver().setNovelListener(NovelInstallationListener()).register(context)
        }
    }

    private fun initNovelExtensions() {
        val novelExtensions = ExtensionLoader.loadNovelExtensions(context)
        _installedNovelExtensionsFlow.value = novelExtensions
            .filterIsInstance<NovelLoadResult.Success>()
            .map { it.extension }
        isInitialized = true
        _isInitializedFlow.value = true
        updatedInstalledNovelExtensionsStatuses(_availableNovelExtensionsFlow.value)
    }

    /** Rescans the installed novel extensions off the main thread. */
    suspend fun refreshInstalledExtensions() = withContext(Dispatchers.IO) {
        initNovelExtensions()
    }

    fun getSourceData(id: Long) = availableNovelExtensionsSourcesData[id]

    suspend fun findAvailableExtensions(): List<NovelExtension.Available> {
        val extensions: List<NovelExtension.Available> = try {
            val list = api.findNovelExtensions()
            list.filter { it.pkgName.isNotBlank() && it.versionName.isNotBlank() }
                .map { ext ->
                    val detectedLang = ext.sources.firstOrNull()?.lang ?: ext.lang.ifBlank { "all" }
                    ext.copy(lang = detectedLang)
                }
        } catch (e: Exception) {
            Logger.log("DanNovelExtensionManager: Error finding extensions: ${e.message}")
            withUIContext { snackString("Failed to get Novel extensions list") }
            emptyList()
        }
        // The update statuses are applied to the installed list, so wait until it has been loaded.
        awaitInitialized()
        _availableNovelExtensionsFlow.value = extensions
        updatedInstalledNovelExtensionsStatuses(extensions)
        setupAvailableNovelExtensionsSourcesDataMap(extensions)
        return extensions
    }

    private fun setupAvailableNovelExtensionsSourcesDataMap(novelExtensions: List<NovelExtension.Available>) {
        if (novelExtensions.isEmpty()) return
        availableNovelExtensionsSourcesData = novelExtensions
            .flatMap { ext -> ext.sources.map { it.toNovelSourceData() } }
            .associateBy { it.id }
    }

    private fun updatedInstalledNovelExtensionsStatuses(
        availableNovelExtensions: List<NovelExtension.Available>
    ) {
        if (availableNovelExtensions.isEmpty()) return
        val reposToSave = mutableMapOf<String, Pair<String, String?>>()
        _installedNovelExtensionsFlow.update { installed ->
            reposToSave.clear()
            installed.map { ext ->
                val avail = availableNovelExtensions.find {
                    isSameNovelExtension(it.pkgName, it.name, ext.pkgName, ext.name)
                }
                if (avail == null && !ext.isObsolete) {
                    ext.copy(isObsolete = true)
                } else if (avail != null) {
                    val hasUpdate = ext.updateExists(avail)
                    val newRepo = avail.repository
                    val newRepoName = avail.repoName

                    // Only persist the repo when it actually changed.
                    if (ext.repository != newRepo || ext.repoName != newRepoName) {
                        reposToSave[ext.pkgName] = newRepo to newRepoName
                    }
                    if (ext.hasUpdate != hasUpdate ||
                        ext.repository != newRepo ||
                        ext.repoName != newRepoName ||
                        ext.isUnofficial
                    ) {
                        ext.copy(
                            hasUpdate = hasUpdate,
                            isUnofficial = false,
                            repository = newRepo,
                            repoName = newRepoName,
                        )
                    } else {
                        ext
                    }
                } else {
                    ext
                }
            }
        }
        reposToSave.forEach { (pkgName, repo) ->
            ani.dantotsu.parsers.ExtensionRepoMetaHelper.saveInstalledExtensionRepo(
                pkgName,
                repo.first,
                repo.second
            )
        }
    }

    fun installExtension(extension: NovelExtension.Available): Observable<InstallStep> {
        ani.dantotsu.parsers.ExtensionRepoMetaHelper.saveInstalledExtensionRepo(
            extension.pkgName,
            extension.repository,
            extension.repoName
        )
        return installer.downloadAndInstall(
            api.getNovelApkUrl(extension), extension.pkgName,
            extension.name, MediaType.NOVEL
        )
    }

    fun updateExtension(extension: NovelExtension.Installed): Observable<InstallStep> {
        val avail = _availableNovelExtensionsFlow.value.find {
            isSameNovelExtension(it.pkgName, it.name, extension.pkgName, extension.name)
        } ?: return Observable.empty()
        return installExtension(avail)
    }

    fun cancelInstallUpdateExtension(extension: NovelExtension) =
        installer.cancelInstall(extension.pkgName)

    fun setInstalling(downloadId: Long) =
        installer.updateInstallStep(downloadId, InstallStep.Installing)

    fun updateInstallStep(downloadId: Long, step: InstallStep) =
        installer.updateInstallStep(downloadId, step)

    fun uninstallExtension(pkgName: String) = installer.uninstallApk(pkgName)

    private fun registerNewExtension(ext: NovelExtension.Installed) {
        _installedNovelExtensionsFlow.update { installed ->
            val mut = installed.toMutableList()
            mut.removeAll { isSameNovelExtension(it.pkgName, it.name, ext.pkgName, ext.name) }
            mut.add(ext)
            mut
        }
    }

    private fun registerUpdatedExtension(ext: NovelExtension.Installed) {
        _installedNovelExtensionsFlow.update { installed ->
            val mut = installed.toMutableList()
            val index = mut.indexOfFirst { isSameNovelExtension(it.pkgName, it.name, ext.pkgName, ext.name) }
            if (index != -1) {
                mut[index] = ext
            } else {
                mut.add(ext)
            }
            mut
        }
    }

    private fun unregisterNovelExtension(pkgName: String) {
        _installedNovelExtensionsFlow.update { installed ->
            installed.filterNot { isSameNovelExtension(it.pkgName, it.name, pkgName, "") }
        }
    }

    private inner class NovelInstallationListener : ExtensionInstallReceiver.NovelListener {
        override fun onExtensionInstalled(extension: NovelExtension.Installed) =
            registerNewExtension(extension.withUpdateCheck())

        override fun onExtensionUpdated(extension: NovelExtension.Installed) =
            registerUpdatedExtension(extension.withUpdateCheck())

        override fun onPackageUninstalled(pkgName: String) =
            unregisterNovelExtension(pkgName)
    }

    private fun NovelExtension.Installed.withUpdateCheck(): NovelExtension.Installed =
        if (updateExists()) copy(hasUpdate = true) else this

    private fun NovelExtension.Installed.updateExists(
        avail: NovelExtension.Available? = null
    ): Boolean {
        val a = avail ?: _availableNovelExtensionsFlow.value.find {
            isSameNovelExtension(it.pkgName, it.name, pkgName, name)
        }
        return a != null && a.versionCode > versionCode
    }
}