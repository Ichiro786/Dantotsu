package eu.kanade.tachiyomi.source.anime

import android.content.Context
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.source.anime.model.AnimeSourceData
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import java.util.concurrent.ConcurrentHashMap

class AndroidAnimeSourceManager(
    private val context: Context,
    private val extensionManager: AnimeExtensionManager,
) : AnimeSourceManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, AnimeSource>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubAnimeSource>()

    private val localSource by lazy { LocalAnimeSource(context) }

    private val initializedFlow = MutableStateFlow(false)

    override val isInitialized: Boolean
        get() = initializedFlow.value

    override val catalogueSources: Flow<List<AnimeCatalogueSource>> =
        sourcesMapFlow.map { it.values.filterIsInstance<AnimeCatalogueSource>() }

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
    private fun rebuild(extensions: List<AnimeExtension.Installed>, markInitialized: Boolean) {
        val map = ConcurrentHashMap<Long, AnimeSource>()
        map[LocalAnimeSource.ID] = localSource
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

    override fun get(sourceKey: Long): AnimeSource? {
        return sourcesMapFlow.value[sourceKey]
            // The map can lag the extension list by a moment; the list is the truth.
            ?: extensionManager.installedExtensionsFlow.value
                .firstNotNullOfOrNull { ext -> ext.sources.firstOrNull { it.id == sourceKey } }
    }

    override fun getOrStub(sourceKey: Long): AnimeSource {
        return get(sourceKey) ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<AnimeHttpSource>()

    override fun getCatalogueSources() =
        sourcesMapFlow.value.values.filterIsInstance<AnimeCatalogueSource>()

    override fun getStubSources(): List<StubAnimeSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(sourceData: AnimeSourceData) {

    }

    private fun createStubSource(id: Long): StubAnimeSource {
        return StubAnimeSource(id = id, name = "")
    }
}
