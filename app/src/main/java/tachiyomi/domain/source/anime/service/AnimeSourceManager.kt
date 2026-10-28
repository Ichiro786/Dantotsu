package tachiyomi.domain.source.anime.service

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.anime.model.StubAnimeSource

interface AnimeSourceManager {

    val catalogueSources: Flow<List<AnimeCatalogueSource>>

    fun get(sourceKey: Long): AnimeSource?

    fun getOrStub(sourceKey: Long): AnimeSource

    fun getOnlineSources(): List<AnimeHttpSource>

    fun getCatalogueSources(): List<AnimeCatalogueSource>

    /**
     * True once the source map reflects the extension manager's loaded extensions. Before that,
     * [get] can return null for an installed source (e.g. right after a process restore).
     */
    val isInitialized: Boolean get() = true

    /** Suspends until [isInitialized]. */
    suspend fun awaitInitialized() {}

    fun getStubSources(): List<StubAnimeSource>
}
