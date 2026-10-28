package tachiyomi.domain.source.manga.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.manga.model.StubMangaSource

interface MangaSourceManager {

    val catalogueSources: Flow<List<CatalogueSource>>

    fun get(sourceKey: Long): MangaSource?

    fun getOrStub(sourceKey: Long): MangaSource

    fun getOnlineSources(): List<HttpSource>

    fun getCatalogueSources(): List<CatalogueSource>

    /**
     * True once the source map reflects the extension manager's loaded extensions. Before that,
     * [get] can return null for an installed source (e.g. right after a process restore).
     */
    val isInitialized: Boolean get() = true

    /** Suspends until [isInitialized]. */
    suspend fun awaitInitialized() {}

    fun getStubSources(): List<StubMangaSource>
}
