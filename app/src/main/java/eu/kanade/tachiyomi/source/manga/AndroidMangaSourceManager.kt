package eu.kanade.tachiyomi.source.manga

import android.content.Context
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.source.manga.model.MangaSourceData
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.source.local.entries.manga.LocalMangaSource
import java.util.concurrent.ConcurrentHashMap

class AndroidMangaSourceManager(
    private val context: Context,
    private val extensionManager: MangaExtensionManager,
) : MangaSourceManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, MangaSource>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubMangaSource>()

    private val localSource by lazy { LocalMangaSource(context) }

    private val initializedFlow = MutableStateFlow(false)

    override val isInitialized: Boolean
        get() = initializedFlow.value

    override val catalogueSources: Flow<List<CatalogueSource>> =
        sourcesMapFlow.map { it.values.filterIsInstance<CatalogueSource>() }

    init {
        // Filled synchronously from what the extension manager already holds: a screen restored
        // after process death (source settings, reader) asks for its source right away, and the
        // map used to be empty until a background collector ran -> NPE / "source not found".
        rebuild(extensionManager.installedExtensionsFlow.value, extensionManager.isInitialized)
        scope.launch {
            extensionManager.installedExtensionsFlow.collect { extensions ->
                rebuild(extensions, extensionManager.isInitialized)
            }
        }
        scope.launch {
            extensionManager.awaitInitialized()
            rebuild(extensionManager.installedExtensionsFlow.value, true)
        }
    }

    @Synchronized
    private fun rebuild(extensions: List<MangaExtension.Installed>, markInitialized: Boolean) {
        val map = ConcurrentHashMap<Long, MangaSource>()
        map[LocalMangaSource.ID] = localSource
        extensions.forEach { extension ->
            extension.sources.forEach {
                map[it.id] = it
                registerStubSource(it.toSourceData())
            }
        }
        sourcesMapFlow.value = map
        if (markInitialized) initializedFlow.value = true
    }

    override suspend fun awaitInitialized() {
        if (initializedFlow.value) return
        initializedFlow.first { it }
    }

    override fun get(sourceKey: Long): MangaSource? {
        return sourcesMapFlow.value[sourceKey]
            // The map can lag the extension list by a moment; the list is the truth.
            ?: extensionManager.installedExtensionsFlow.value
                .firstNotNullOfOrNull { ext -> ext.sources.firstOrNull { it.id == sourceKey } }
    }

    override fun getOrStub(sourceKey: Long): MangaSource {
        return get(sourceKey) ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    override fun getCatalogueSources() =
        sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>()

    override fun getStubSources(): List<StubMangaSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(sourceData: MangaSourceData) {

    }

    private fun createStubSource(id: Long): StubMangaSource {
        return StubMangaSource(MangaSourceData(id, "", ""))
    }
}
