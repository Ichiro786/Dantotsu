package ani.dantotsu.parsers

import android.content.Context
import ani.dantotsu.FileUrl
import ani.dantotsu.currContext
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.media.manga.ImageData
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.NO_HOSTER_LIST
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.copyFrom
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.anime.getPreferenceKey
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.copyFrom
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.awaitSingle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ani.dantotsu.parsers.StreamHeaders.toFlatMap
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import okhttp3.Request
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

private const val MAX_PARALLEL_HOSTERS = 4

class DynamicAnimeParser(extension: AnimeExtension.Installed) : AnimeParser() {
    val extension: AnimeExtension.Installed
    var sourceLanguage = 0
        set(value) {
            field = if (extension.sources.isNotEmpty()) {
                value.coerceIn(0, extension.sources.size - 1)
            } else {
                0
            }
        }

    init {
        this.extension = extension
    }

    override val name = extension.name
    override val saveName = extension.name
    override val hostUrl =
        (extension.sources.firstOrNull() as? AnimeHttpSource)?.baseUrl ?: extension.sources.firstOrNull()?.name ?: ""
    override val isNSFW = extension.isNsfw
    override val icon = extension.icon

    private var userSelectDub: Boolean? = null

    override var selectDub: Boolean
        get() = userSelectDub ?: getDub()
        set(value) {
            userSelectDub = value
            setDub(value)
        }

    private val isDubAvailableCache = mutableMapOf<Int, Boolean>()

    private fun getDub(): Boolean {
        val configurableSource = extension.sources.getOrNull(sourceLanguage) as? ConfigurableAnimeSource
            ?: return false
        currContext()?.let { context ->
            val sharedPreferences =
                context.getSharedPreferences(
                    configurableSource.getPreferenceKey(),
                    Context.MODE_PRIVATE
                )
            sharedPreferences.all.filterValues { MediaNameAdapter.getSubDub(it.toString()) != MediaNameAdapter.SubDubType.NULL }
                .forEach { value ->
                    return when (MediaNameAdapter.getSubDub(value.value.toString())) {
                        MediaNameAdapter.SubDubType.SUB -> false
                        MediaNameAdapter.SubDubType.DUB -> true
                        MediaNameAdapter.SubDubType.NULL -> false
                    }
                }
        }
        return false
    }

    // Sources that publish dubs as separate entries are handled by remapping which
    // entry backs the title; ones that keep every dub inside a single entry can only
    // be steered through their own preference. setSubDub is anchored, so only whole
    // "sub"/"dub" values match and every other preference is left alone.
    private fun setDub(setDub: Boolean) {
        val configurableSource = extension.sources.getOrNull(sourceLanguage) as? ConfigurableAnimeSource
            ?: return
        val context = currContext() ?: return
        val target = if (setDub) MediaNameAdapter.SubDubType.DUB else MediaNameAdapter.SubDubType.SUB
        val preferences = context.getSharedPreferences(
            configurableSource.getPreferenceKey(),
            Context.MODE_PRIVATE
        )
        isDubAvailableCache.clear()
        preferences.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val replacement = MediaNameAdapter.setSubDub(value, target) ?: return@forEach
            if (replacement == value) return@forEach
            preferences.edit().putString(key, replacement).apply()
            Logger.log("setDub: $key -> $replacement")
        }
    }

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean {
        val targetLang = (sourceLang ?: sourceLanguage).coerceAtLeast(0)
        isDubAvailableCache[targetLang]?.let { return it }
        val configurableSource = extension.sources.getOrNull(targetLang) as? ConfigurableAnimeSource
            ?: return false
        currContext()?.let { context ->
            val sharedPreferences =
                context.getSharedPreferences(
                    configurableSource.getPreferenceKey(),
                    Context.MODE_PRIVATE
                )
            val available = sharedPreferences.all.any { (_, value) ->
                MediaNameAdapter.setSubDub(
                    value.toString(),
                    MediaNameAdapter.SubDubType.NULL
                ) != null
            }
            isDubAvailableCache[targetLang] = available
            return available
        }
        return false
    }


    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> = withContext(Dispatchers.IO) {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? AnimeSource
            ?: return@withContext emptyList()
        try {
            val networkAnime = runCatching {
                source.getAnimeDetails(sAnime)
            }.getOrNull()
            if (networkAnime != null) {
                runCatching {
                    sAnime.copyFrom(networkAnime)
                }
            }
            val seasons = runCatching {
                source.getSeasonListCompat(sAnime)
            }.getOrNull()

            val res = if (!seasons.isNullOrEmpty()) {
                val semaphore = Semaphore(3)
                val allEpisodes = coroutineScope {
                    seasons.map { season ->
                        async {
                            semaphore.withPermit {
                                val seasonAnime = runCatching {
                                    if (source is AnimeHttpSource) source.getAnimeDetails(season) else season
                                }.getOrDefault(season)
                                val seasonEpisodes = runCatching {
                                    source.getEpisodeListCompat(seasonAnime)
                                }.getOrDefault(emptyList())
                                seasonEpisodes.forEach { ep ->
                                    if (ep.scanlator.isNullOrBlank()) {
                                        ep.scanlator = seasonAnime.title.ifBlank { null } ?: season.title
                                    }
                                }
                                seasonEpisodes
                            }
                        }
                    }.awaitAll().flatten()
                }
                if (allEpisodes.isEmpty()) {
                    source.getEpisodeListCompat(sAnime)
                } else {
                    allEpisodes
                }
            } else {
                source.getEpisodeListCompat(sAnime)
            }



            if (res.isEmpty()) return@withContext emptyList()

            // Pre-process episodes: extract folder hierarchy for scanlator/season chips and detect true episode numbers
            res.forEach { ep ->
                val normalizedName = ep.name.replace('\\', '/')
                if (normalizedName.contains('/')) {
                    val parts = normalizedName.split('/').filter { it.isNotBlank() }
                    if (parts.size > 1) {
                        if (ep.scanlator.isNullOrBlank()) {
                            ep.scanlator = parts.dropLast(1).joinToString(" / ")
                        }
                        ep.name = parts.last()
                    }
                }
                val detected = MediaNameAdapter.findEpisodeNumber(ep.name)
                    ?: MediaNameAdapter.findEpisodeNumber(ep.url)
                if (detected != null && (ep.episode_number <= 0f || ep.episode_number == -1f)) {
                    ep.episode_number = detected
                }
            }

            val sortedEpisodes = if (res.all { it.episode_number > 0f }) {
                res.sortedBy { it.episode_number }
            } else if (res[0].episode_number == -1f) {
                val sortedByStringNumber = res.sortedBy {
                    val number = it.episode_number.takeIf { n -> n > 0f }
                        ?: MediaNameAdapter.findEpisodeNumber(it.name)
                        ?: MediaNameAdapter.findEpisodeNumber(it.url)
                        ?: Float.MAX_VALUE
                    it.episode_number = number
                    number
                }
                var incrementingNumber = 1f
                sortedByStringNumber.map {
                    if (it.episode_number == Float.MAX_VALUE) {
                        it.episode_number = incrementingNumber++
                    }
                    it
                }
            } else if (episodesAreIncrementing(res)) {
                res.sortedBy { it.episode_number }
            } else {
                var episodeCounter = 1f
                val seasonGroups = res.groupBy { MediaNameAdapter.findSeasonNumber(it.name) ?: 0 }
                seasonGroups.keys.sortedBy { it }.flatMap { season ->
                    seasonGroups[season]?.sortedBy { it.episode_number }?.map { episode ->
                        if (episode.episode_number <= 0f) {
                            val potentialNumber = MediaNameAdapter.findEpisodeNumber(episode.name)
                                ?: MediaNameAdapter.findEpisodeNumber(episode.url)
                            if (potentialNumber != null) {
                                episode.episode_number = potentialNumber
                            } else {
                                episode.episode_number = episodeCounter
                            }
                            episodeCounter++
                        }
                        episode
                    } ?: emptyList()
                }
            }
            return@withContext sortedEpisodes.map { sEpisodeToEpisode(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("Exception: $e")
            reportSourceError(source, e, animePageUrl(source, sAnime))
        }
        return@withContext emptyList()
    }

    private fun episodesAreIncrementing(episodes: List<SEpisode>): Boolean {
        val sortedEpisodes = episodes.sortedBy { it.episode_number }
        val takenNumbers = mutableListOf<Float>()
        sortedEpisodes.forEach {
            if (it.episode_number !in takenNumbers) {
                takenNumbers.add(it.episode_number)
            } else {
                return false
            }
        }
        return true
    }
    private fun currentAnimeSource(): AnimeSource? =
        (extension.sources.getOrNull(sourceLanguage) ?: extension.sources.firstOrNull()) as? AnimeSource

    /** source.headers of an http source; extensions can throw from headersBuilder(). */
    private fun sourceHeadersOf(source: AnimeSource?): Map<String, String> =
        runCatching { (source as? AnimeHttpSource)?.headers.toFlatMap() }.getOrNull() ?: emptyMap()

    private fun sourceClientOf(source: AnimeSource?): okhttp3.OkHttpClient? =
        runCatching { (source as? AnimeHttpSource)?.client }.getOrNull()

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> = withContext(Dispatchers.IO) {
        val source = currentAnimeSource() ?: return@withContext emptyList()

        return@withContext try {
            val videos = getVideoList(source, sEpisode)
            val sourceHeaders = sourceHeadersOf(source)
            videos.map { videoToVideoServer(it, sourceHeaders) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("Exception occurred: ${e.message}")
            emptyList()
        }
    }

    suspend fun getVideoList(
        source: AnimeSource,
        episode: SEpisode
    ): List<Video> = withContext(Dispatchers.IO) {
        val hosters = runCatching {
            source.getHosterList(episode)
        }.getOrElse { emptyList() }

        // If hosters exist (lib 16), don't call deprecated getVideoList(episode) which throws
        val directVideos = if (hosters.isEmpty()) {
            runCatching {
                source.getVideoList(episode)
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val sortedHosters = runCatching {
            if (source is AnimeHttpSource) source.run { hosters.sortHosters() } else hosters
        }.getOrElse { hosters }

        // Filter out lazy hosters from initial extraction; if all are lazy, prioritize the first (preferred) hoster
        val activeHosters = sortedHosters.filterNot { it.lazy }
        val hostersToFetch = if (activeHosters.isEmpty() && sortedHosters.isNotEmpty()) {
            listOf(sortedHosters.first())
        } else {
            activeHosters
        }

        val semaphore = Semaphore(MAX_PARALLEL_HOSTERS)
        val hosterVideos = if (hostersToFetch.isNotEmpty()) {
            coroutineScope {
                hostersToFetch.map { hoster ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            fetchHosterVideos(source, hoster).map { video ->
                                resolveVideo(source, video).copy(
                                    videoTitle = titleFor(hoster, video),
                                    initialized = true
                                )
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
        } else {
            emptyList()
        }

        val resolvedDirect = if (directVideos.isNotEmpty()) {
            coroutineScope {
                directVideos.map {
                    async(Dispatchers.IO) {
                        semaphore.withPermit { resolveVideo(source, it) }
                    }
                }.awaitAll()
            }
        } else {
            emptyList()
        }

        val allVideos = (resolvedDirect + hosterVideos)
            .distinctBy { videoKey(it) }
            .filter { hasUsableUrl(it) }

        return@withContext runCatching {
            if (source is AnimeHttpSource) source.run { allVideos.sortVideos() } else allVideos
        }.getOrElse { allVideos }
    }

    private suspend fun fetchHosterVideos(
        source: AnimeSource,
        hoster: Hoster,
        onError: ((Throwable) -> Unit)? = null,
    ): List<Video> =
        if (!hoster.videoList.isNullOrEmpty()) {
            hoster.videoList
        } else {
            try {
                source.getVideoList(hoster)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log("Hoster ${hoster.hosterName} failed: ${e.message}")
                onError?.invoke(e)
                emptyList()
            }
        }

    private fun isStubError(e: Throwable): Boolean =
        e is IllegalStateException || e is UnsupportedOperationException ||
            e is AbstractMethodError || e is NoSuchMethodError ||
            e.message == "Stub!" || e.message == "Not used"

    private fun absoluteUrl(source: AnimeHttpSource, url: String): String =
        if (url.startsWith("http", ignoreCase = true)) url else source.baseUrl.trimEnd('/') + "/" + url.trimStart('/')

    private fun episodePageUrl(source: AnimeSource, episode: SEpisode): String? =
        (source as? AnimeHttpSource)?.let { src ->
            runCatching { absoluteUrl(src, src.getEpisodeUrl(episode)) }.getOrNull()
        }

    private fun animePageUrl(source: AnimeSource, anime: SAnime): String? =
        (source as? AnimeHttpSource)?.let { src ->
            runCatching { absoluteUrl(src, src.getAnimeUrl(anime)) }.getOrNull()
                ?: runCatching { src.baseUrl }.getOrNull()
        }

    /** The extension's own message (often "open in WebView ...") with a WebView action. */
    private fun reportSourceError(source: AnimeSource, error: Throwable?, pageUrl: String?) {
        ani.dantotsu.others.webview.SourceWebViewActivity.showSourceError(
            name,
            error?.takeUnless { isStubError(it) }?.let { it.message ?: it.javaClass.simpleName },
            pageUrl,
            sourceHeadersOf(source),
        )
    }

    private fun titleFor(hoster: Hoster?, video: Video): String =
        if (hoster == null || hoster.hosterName.isBlank() || hoster.hosterName == NO_HOSTER_LIST) {
            video.videoTitle
        } else {
            "${hoster.hosterName} - ${video.videoTitle}"
        }

    private fun isRealUrl(url: String) = url.isNotBlank() && url != "null"

    private fun hasUsableUrl(video: Video) = isRealUrl(video.videoUrl) || isRealUrl(video.url)

    private fun videoKey(video: Video): String =
        if (isRealUrl(video.videoUrl)) video.videoUrl
        else if (isRealUrl(video.url)) video.url
        else video.videoTitle

    /** A video that only a resolveVideo/getVideoUrl call can turn into a playable url. */
    private fun needsResolution(video: Video): Boolean =
        !video.initialized || !isRealUrl(video.videoUrl)

    private suspend fun resolveVideo(
        source: AnimeSource,
        video: Video
    ): Video = withContext(Dispatchers.IO) {
        if (video.initialized && video.videoUrl.isNotEmpty() && video.videoUrl != "null") {
            return@withContext video
        }

        // 1. Modern lib 16 API: call resolveVideo directly (avoids throwing UnsupportedOperationException from getVideoUrl)
        val resolved = try {
            if (source is AnimeHttpSource) source.resolveVideo(video) else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

        if (resolved != null && resolved.videoUrl.isNotBlank() && resolved.videoUrl != "null") {
            return@withContext resolved
        }

        // 2. Legacy fallback for old sources: only call getVideoUrl if resolveVideo returned null and url is blank
        if (video.videoUrl == "null" || video.videoUrl.isEmpty()) {
            val newUrl = try {
                if (source is AnimeHttpSource) source.getVideoUrl(video) else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }

            if (!newUrl.isNullOrEmpty() && newUrl != "null") {
                return@withContext video.copy(videoUrl = newUrl, initialized = true)
            }
        }

        return@withContext video
    }

    /** Resolves one video for [VideoServerPassthrough] (selected-only resolution). */
    internal suspend fun resolveForPlayback(source: AnimeSource, video: Video): Video? {
        val resolved = resolveVideo(source, video)
        return resolved.takeIf { isRealUrl(it.videoUrl) }?.copy(initialized = true)
    }

    /**
     * Re-fetches the links of one server for [VideoServerPassthrough.refreshLinks]: the same
     * hoster is asked for its videos again and the same video (by title, else resolution) is
     * resolved. Hoster video lists embedded in getHosterList are refreshed through a new
     * getHosterList call, since the embedded links are the ones that expired.
     */
    internal suspend fun refetchVideo(
        source: AnimeSource,
        sEpisode: SEpisode,
        hosterName: String?,
        originalTitle: String,
        resolution: Int?,
    ): Video? = withContext(Dispatchers.IO) {
        val hosters = try {
            source.getHosterList(sEpisode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        val candidates = if (hosters.isEmpty()) {
            try {
                source.getVideoList(sEpisode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            val hoster = hosters.firstOrNull { it.hosterName == (hosterName ?: NO_HOSTER_LIST) }
                ?: hosters.firstOrNull { hosterName == null && (it.hosterName.isBlank() || it.hosterName == NO_HOSTER_LIST) }
                ?: return@withContext null
            fetchHosterVideos(source, hoster)
        }
        val match = candidates.firstOrNull { it.videoTitle == originalTitle }
            ?: resolution?.let { res -> candidates.firstOrNull { it.resolution == res } }
            ?: return@withContext null
        resolveForPlayback(source, match)
    }

    private fun newPassthrough(
        source: AnimeSource,
        sEpisode: SEpisode,
        hoster: Hoster?,
        video: Video,
        sourceHeaders: Map<String, String>,
    ): VideoServerPassthrough {
        val titled = video.copy(videoTitle = titleFor(hoster, video))
        val server = videoToVideoServer(titled, sourceHeaders)
        return VideoServerPassthrough(server).also { ext ->
            ext.sourceClient = sourceClientOf(source)
            ext.sourceHeaders = sourceHeaders
            ext.parser = this
            ext.animeSource = source
            ext.sEpisode = sEpisode
            ext.hosterName = hoster?.hosterName?.takeIf { it.isNotBlank() && it != NO_HOSTER_LIST }
            ext.originalTitle = video.videoTitle
            ext.unresolved = needsResolution(video)
            if (!ext.unresolved) ext.resolvedAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Progressive server list: each hoster's videos are handed to [callback] as soon as that
     * hoster answers (at most [MAX_PARALLEL_HOSTERS] hosters in flight), instead of waiting for
     * every hoster and resolving every video first. Lazy hosters are shown as tappable
     * placeholders ([LazyHosterExtractor]) and only fetched when picked.
     *
     * With [resolveLazily] a video that still needs the source's resolveVideo is listed
     * unresolved and resolved only when it is picked ([VideoExtractor.needsResolve]).
     */
    suspend fun streamVideoServers(
        sEpisode: SEpisode,
        resolveLazily: Boolean,
        callback: (VideoExtractor) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val source = currentAnimeSource() ?: return@withContext
        val sourceHeaders = sourceHeadersOf(source)
        val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        val semaphore = Semaphore(MAX_PARALLEL_HOSTERS)
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val recordError: (Throwable) -> Unit = { firstError.compareAndSet(null, it) }
        var hosterError: Throwable? = null

        suspend fun emit(hoster: Hoster?, videos: List<Video>) {
            val sorted = runCatching {
                if (source is AnimeHttpSource) source.run { videos.sortVideos() } else videos
            }.getOrElse { videos }
            for (raw in sorted) {
                val video = if (!resolveLazily && needsResolution(raw)) resolveVideo(source, raw) else raw
                if (!hasUsableUrl(video) && !(resolveLazily && needsResolution(video))) continue
                val key = if (isRealUrl(video.videoUrl)) video.videoUrl
                else "${hoster?.hosterName}|${video.videoTitle}|${video.url}"
                if (!seen.add(key)) continue
                val ext = newPassthrough(source, sEpisode, hoster, video, sourceHeaders)
                var loaded: VideoExtractor? = null
                try {
                    loaded = ext.load()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.log("Server ${ext.server.name} skipped: ${e.message}")
                }
                if (loaded != null) {
                    emitted.incrementAndGet()
                    callback(loaded)
                }
            }
        }

        val hosters = try {
            source.getHosterList(sEpisode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            hosterError = e
            emptyList()
        }

        if (hosters.isEmpty()) {
            val direct = try {
                source.getVideoList(sEpisode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log("getVideoList failed: ${e.message}")
                // lib < 16 sources fail getHosterList with a stub error and lib 16 ones fail
                // getVideoList(episode) that way: report whichever error is real
                recordError(if (isStubError(e)) hosterError ?: e else e)
                emptyList()
            }
            if (resolveLazily) {
                emit(null, direct)
            } else {
                val resolved = coroutineScope {
                    direct.map { v ->
                        async {
                            semaphore.withPermit {
                                // mark it resolved so emit() doesn't ask the source a second time
                                resolveVideo(source, v).let { r -> if (isRealUrl(r.videoUrl)) r.copy(initialized = true) else r }
                            }
                        }
                    }.awaitAll()
                }
                emit(null, resolved)
            }
            if (emitted.get() == 0) reportSourceError(source, firstError.get(), episodePageUrl(source, sEpisode))
            return@withContext
        }

        val sortedHosters = runCatching {
            if (source is AnimeHttpSource) source.run { hosters.sortHosters() } else hosters
        }.getOrElse { hosters }
        val eager = sortedHosters.filterNot { it.lazy }.ifEmpty { listOfNotNull(sortedHosters.firstOrNull()) }
        sortedHosters.filter { it.lazy && it !in eager }.forEach { hoster ->
            callback(LazyHosterExtractor(this@DynamicAnimeParser, source, sEpisode, hoster, resolveLazily))
        }
        coroutineScope {
            eager.map { hoster ->
                async {
                    semaphore.withPermit {
                        emit(hoster, fetchHosterVideos(source, hoster, recordError))
                    }
                }
            }.awaitAll()
        }
        if (emitted.get() == 0 && sortedHosters.none { it.lazy }) {
            reportSourceError(source, firstError.get(), episodePageUrl(source, sEpisode))
        }
    }

    /** Loads the videos of one lazy hoster (on tap) as ready-to-play extractors. */
    internal suspend fun loadLazyHoster(
        source: AnimeSource,
        sEpisode: SEpisode,
        hoster: Hoster,
        resolveLazily: Boolean,
    ): List<VideoExtractor> = withContext(Dispatchers.IO) {
        val sourceHeaders = sourceHeadersOf(source)
        val videos = runCatching {
            if (source is AnimeHttpSource) source.run { fetchHosterVideos(source, hoster).sortVideos() }
            else fetchHosterVideos(source, hoster)
        }.getOrElse { emptyList() }
        videos.mapNotNull { raw ->
            val video = if (!resolveLazily && needsResolution(raw)) resolveVideo(source, raw) else raw
            if (!hasUsableUrl(video) && !(resolveLazily && needsResolution(video))) return@mapNotNull null
            runCatching { newPassthrough(source, sEpisode, hoster, video, sourceHeaders).load() }.getOrNull()
        }
    }

    override suspend fun loadByVideoServers(
        episodeUrl: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
        callback: (VideoExtractor) -> Unit
    ) {
        loadByVideoServers(episodeUrl, extra, sEpisode, resolveLazily = false, callback = callback)
    }

    override suspend fun loadByVideoServers(
        episodeUrl: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
        resolveLazily: Boolean,
        callback: (VideoExtractor) -> Unit
    ) {
        try {
            streamVideoServers(sEpisode, resolveLazily, callback)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.log("loadByVideoServers failed: ${e.message}")
        }
    }

    /**
     * Default-server path: only the hoster the saved server belongs to is fetched (server
     * names are "<hoster> - <video title>"), not every hoster of the episode. Falls back to
     * the full list when no hoster matches, e.g. the site renamed its hosters.
     */
    override suspend fun loadSingleVideoServer(
        serverName: String,
        episodeUrl: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
        post: Boolean
    ): VideoExtractor? = withContext(Dispatchers.IO) {
        try {
            val source = currentAnimeSource() ?: return@withContext null
            val sourceHeaders = sourceHeadersOf(source)
            val hosters = try {
                source.getHosterList(sEpisode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            val hoster = hosters.firstOrNull {
                it.hosterName.isNotBlank() && it.hosterName != NO_HOSTER_LIST &&
                    serverName.startsWith("${it.hosterName} - ")
            }
            if (hoster != null) {
                val videos = runCatching {
                    if (source is AnimeHttpSource) source.run { fetchHosterVideos(source, hoster).sortVideos() }
                    else fetchHosterVideos(source, hoster)
                }.getOrElse { emptyList() }
                val target = videos.firstOrNull { titleFor(hoster, it) == serverName }
                    ?: videos.firstOrNull { it.preferred }
                    ?: videos.firstOrNull()
                if (target != null) {
                    val video = resolveVideo(source, target)
                    if (hasUsableUrl(video)) {
                        return@withContext newPassthrough(source, sEpisode, hoster, video, sourceHeaders).load()
                    }
                }
            }
            // No matching hoster (or it failed): previous behaviour over the full list.
            val servers = loadVideoServers(episodeUrl, extra, sEpisode)
            val target = servers.find { it.name == serverName }
                ?: servers.find { it.isPreferred }
                ?: servers.firstOrNull()
            target?.let { (getVideoExtractor(it) as? VideoServerPassthrough)?.load() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.log("loadSingleVideoServer failed: ${e.message}")
            null
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        val source = currentAnimeSource()
        return VideoServerPassthrough(server).also { ext ->
            ext.sourceClient = sourceClientOf(source)
            ext.sourceHeaders = sourceHeadersOf(source)
            ext.parser = this
            ext.animeSource = source
            ext.resolvedAtMs = System.currentTimeMillis()
        }
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? AnimeCatalogueSource
            ?: return emptyList()
        return try {
            val res = try {
                source.getSearchAnime(1, query, source.getFilterList())
            } catch (e: UnsupportedOperationException) {
                source.fetchSearchAnime(1, query, source.getFilterList()).awaitSingle()
            } catch (e: NoSuchMethodError) {
                source.fetchSearchAnime(1, query, source.getFilterList()).awaitSingle()
            }

            Logger.log("query: $query")
            convertAnimesPageToShowResponse(res)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a cancelled search (typing, screen closed) is not an error
        } catch (e: CloudflareBypassException) {
            Logger.log("Exception in search: $e")
            Logger.log(e)
            // solving the challenge once in the WebView clears it for the source
            val home = runCatching { (source as? AnimeHttpSource)?.baseUrl }.getOrNull()
            ani.dantotsu.others.webview.SourceWebViewActivity.showSourceError(
                name, "Failed to bypass Cloudflare", home, sourceHeadersOf(source)
            )
            emptyList()
        } catch (e: Exception) {
            Logger.log("General exception in search: $e")
            Logger.log(e)
            emptyList()
        }
    }


    private fun convertAnimesPageToShowResponse(animesPage: AnimesPage): List<ShowResponse> {
        // covers are often hotlink-protected: send the source's headers (Referer, UA)
        val coverHeaders = sourceHeadersOf(currentAnimeSource())
        return animesPage.animes.map { sAnime ->
            ShowResponse(
                sAnime.title,
                sAnime.url,
                FileUrl(sAnime.thumbnail_url ?: "", coverHeaders),
                sAnime = sAnime
            )
        }
    }

    private val episodeImageHeaders: Map<String, String> by lazy {
        sourceHeadersOf(currentAnimeSource())
    }

    private fun sEpisodeToEpisode(sEpisode: SEpisode): Episode {
        //if the float episode number is a whole number, convert it to an int
        val episodeNumberInt =
            if (sEpisode.episode_number % 1 == 0f) {
                sEpisode.episode_number.toInt()
            } else {
                sEpisode.episode_number
            }
        // Summary, preview image and filler mark from the extension are kept: the episode
        // list shows them when no tracker (Kitsu/Anify) data is there.
        val preview = sEpisode.preview_url?.takeIf { it.isNotBlank() }?.let { url ->
            FileUrl(url, episodeImageHeaders)
        }
        return Episode(
            if (episodeNumberInt.toInt() != -1) {
                if (sEpisode.episode_number % 1 == 0f) {
                    episodeNumberInt.toInt().toString()
                } else {
                    sEpisode.episode_number.toString()
                }
            } else {
                sEpisode.name
            },
            sEpisode.url,
            sEpisode.name,
            preview,
            sEpisode.summary?.takeIf { it.isNotBlank() },
            sEpisode.fillermark,
            null,
            sEpisode
        )
    }

    private fun videoToVideoServer(video: Video, sourceHeaders: Map<String, String> = emptyMap()): VideoServer {
        val targetUrl = video.videoUrl.takeIf { it.isNotBlank() && it != "null" }
            ?: video.url.takeIf { it.isNotBlank() && it != "null" }
            ?: ""
        // source.headers under video.headers (Aniyomi falls back to source.headers when a
        // video has none; Referer/Origin usually live there).
        val headersMap = StreamHeaders.merge(sourceHeaders, video.headers.toFlatMap())
        return VideoServer(
            video.videoTitle.ifBlank { video.quality },
            FileUrl(targetUrl, headersMap),
            null,
            video
        )
    }
}

class DynamicMangaParser(extension: MangaExtension.Installed) : MangaParser() {
    private val mangaCache by lazy {
        try {
            Injekt.get<MangaCache>()
        } catch (_: Throwable) {
            MangaCache()
        }
    }
    val extension: MangaExtension.Installed
    var sourceLanguage = 0
        set(value) {
            field = if (extension.sources.isNotEmpty()) {
                value.coerceIn(0, extension.sources.size - 1)
            } else {
                0
            }
        }

    init {
        this.extension = extension
    }

    override val name = extension.name
    override val saveName = extension.name
    override val hostUrl =
        (extension.sources.firstOrNull() as? HttpSource)?.baseUrl ?: extension.sources.firstOrNull()?.name ?: ""
    override val isNSFW = extension.isNsfw
    override val icon = extension.icon

    override suspend fun loadChapters(
        mangaLink: String,
        extra: Map<String, String>?,
        sManga: SManga
    ): List<MangaChapter> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? MangaSource ?: return emptyList()

        return try {
            val (networkManga, res) = fetchMangaUpdate(source, mangaLink, sManga)
            if (networkManga != null) {
                sManga.copyFrom(networkManga)
            }
            res.reversed().map { sChapterToMangaChapter(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("loadChapters Exception: $e")
            emptyList()
        }
    }

    /**
     * One getMangaUpdate per manga at a time: the details screen asks for chapters from
     * several places (cached list, refresh, source switch); a second caller now joins the
     * running request. The chapters already known for this manga are passed in (Mihon passes
     * its database chapters; some sources use them, and their memo, for incremental updates).
     * Falls back to getMangaDetails + getChapterList only when getMangaUpdate itself is
     * unsupported or failed, not when it legitimately returned an empty list.
     */
    private suspend fun fetchMangaUpdate(
        source: MangaSource,
        mangaLink: String,
        sManga: SManga,
    ): Pair<SManga?, List<SChapter>> {
        val key = "${source.id}|${sManga.url.ifBlank { mangaLink }}"
        val job = mangaUpdateJobs.compute(key) { _, running ->
            if (running != null && !running.isCompleted) running
            else mangaUpdateScope.async { fetchMangaUpdateNow(source, mangaLink, sManga) }
        }!!
        return try {
            job.await()
        } finally {
            if (job.isCompleted) mangaUpdateJobs.remove(key, job)
        }
    }

    private suspend fun fetchMangaUpdateNow(
        source: MangaSource,
        mangaLink: String,
        sManga: SManga,
    ): Pair<SManga?, List<SChapter>> {
        val known = runCatching {
            ani.dantotsu.media.manga.ChapterStorage.loadChapters(saveName.ifBlank { name }, mangaLink)
                ?.values?.map { it.sChapter }?.reversed()
        }.getOrNull().orEmpty()
        val update = try {
            source.getMangaUpdate(sManga, known, fetchDetails = true, fetchChapters = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // UnsupportedOperationException / NoSuchMethodError (older libs) or a failed request
            Logger.log("getMangaUpdate failed, using details + chapter list: ${e.message}")
            null
        }
        if (update != null) return Pair(update.manga, update.chapters)
        val details = try {
            source.getMangaDetails(sManga)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val chapters = try {
            source.getChapterList(sManga)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.log("getChapterList failed: ${e.message}")
            // the source's message often says what to do ("open in WebView ...")
            val http = source as? HttpSource
            val pageUrl = http?.let { src ->
                runCatching {
                    val u = src.getMangaUrl(sManga)
                    if (u.startsWith("http", true)) u else src.baseUrl.trimEnd('/') + "/" + u.trimStart('/')
                }.getOrNull()
            }
            val headers = runCatching { http?.headers.toFlatMap() }.getOrNull() ?: emptyMap()
            ani.dantotsu.others.webview.SourceWebViewActivity.showSourceError(name, e.message, pageUrl, headers)
            emptyList()
        }
        return Pair(details, chapters)
    }

    private companion object {
        val mangaUpdateJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Pair<SManga?, List<SChapter>>>>()
        val mangaUpdateScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }


    override suspend fun loadImages(chapterLink: String, sChapter: SChapter): List<MangaImage> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? HttpSource ?: return emptyList()
        return try {
            Logger.log("source.name " + source.name)
            // getPageList: suspend API (extensions-lib 1.5+/KeiSource) or, through the HttpSource
            // default, the Rx fetchPageList/pageListParse of older and ParsedHttpSource extensions.
            val res = source.getPageList(sChapter)
            // Page order is the list order (Mihon ignores Page.index), so re-index it.
            val reIndexedPages =
                res.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
            // Lets an expired image url be refreshed from this chapter's page list later.
            ani.dantotsu.media.manga.MangaImageFetcher.registerChapterPages(reIndexedPages, sChapter, source)

            // Image urls that need a request of their own (imageUrlParse) are no longer resolved
            // for every page before the reader opens: like Mihon, they are resolved when the page
            // is actually loaded (ImageData / MangaImageFetcher call source.getImageUrl lazily).
            reIndexedPages.map { page ->
                val image = pageToMangaImage(page, source)
                mangaCache.put(image.url.url, ImageData(page, source))
                image
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("loadImages Exception: $e")
            snackString("Failed to load images: $e")
            emptyList()
        }
    }

    suspend fun imageList(sChapter: SChapter): List<ImageData> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? HttpSource ?: return emptyList()

        return coroutineScope {
            try {
                Logger.log("source.name " + source.name)
                val res = source.getPageList(sChapter)
                val reIndexedPages =
                    res.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }

                val semaphore = Semaphore(5)
                val deferreds = reIndexedPages.map { page ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val imageUrl = if (page.imageUrl.isNullOrBlank()) {
                                runCatching { source.getImageUrl(page) }.getOrNull() ?: page.imageUrl
                            } else {
                                page.imageUrl
                            }
                            val resolvedPage = if (imageUrl != page.imageUrl) {
                                Page(page.index, page.url, imageUrl, page.uri)
                            } else {
                                page
                            }
                            ImageData(resolvedPage, source)
                        }
                    }
                }

                deferreds.awaitAll()
            } catch (e: Exception) {
                Logger.log("loadImages Exception: $e")
                snackString("Failed to load images: $e")
                emptyList()
            }
        }
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val source = (extension.sources.getOrNull(sourceLanguage)
            ?: extension.sources.firstOrNull()) as? MangaSource ?: return emptyList()

        return try {
            val res = try {
                source.getSearchManga(1, query, source.getFilterList())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // don't retry a cancelled search through the Rx fallback
            } catch (e: Throwable) {
                if (source is CatalogueSource) {
                    source.fetchSearchManga(1, query, source.getFilterList()).awaitSingle()
                } else {
                    throw e
                }
            }
            Logger.log("res observable: $res")
            convertMangasPageToShowResponse(res)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: CloudflareBypassException) {
            Logger.log("Exception in search: $e")
            val http = source as? HttpSource
            ani.dantotsu.others.webview.SourceWebViewActivity.showSourceError(
                name,
                "Failed to bypass Cloudflare",
                runCatching { http?.getHomeUrl() }.getOrNull(),
                runCatching { http?.headers.toFlatMap() }.getOrNull() ?: emptyMap(),
            )
            emptyList()
        } catch (e: Exception) {
            Logger.log("General exception in search: $e")
            emptyList()
        }
    }


    private fun convertMangasPageToShowResponse(mangasPage: MangasPage): List<ShowResponse> {
        // covers are often hotlink-protected: send the source's headers (Referer, UA)
        val coverHeaders = runCatching {
            ((extension.sources.getOrNull(sourceLanguage) ?: extension.sources.firstOrNull()) as? HttpSource)
                ?.headers.toFlatMap()
        }.getOrNull() ?: emptyMap()
        return mangasPage.mangas.map { sManga ->
            ShowResponse(
                sManga.title,
                sManga.url,
                FileUrl(sManga.thumbnail_url ?: "", coverHeaders),
                sManga = sManga
            )
        }
    }

    private fun pageToMangaImage(page: Page, source: HttpSource? = null): MangaImage {
        var headersMap = mapOf<String, String>()
        // A page whose image url is resolved lazily is keyed by its page url (unique per page).
        val url = page.imageUrl?.takeIf { it.isNotBlank() }
            ?: page.url.takeIf { it.isNotBlank() }
            ?: "page:${page.index}"

        page.imageUrl?.takeIf { !it.startsWith("data:") }?.let {
            val splitUrl = it.split("&")
            // Legacy "key=value&key=value" header encoding. Only real header names are kept:
            // query parameters like "https://host/x.jpg?token" made OkHttp/Glide throw
            // "Unexpected char" and broke every non-source fallback for urls with a query.
            headersMap = splitUrl.mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx != -1) {
                    try {
                        val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                        val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                        Pair(key, value)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }.toMap().let { ani.dantotsu.media.manga.MangaImageFetcher.validHeaders(it) }
        }

        return MangaImage(
            FileUrl(url, headersMap),
            false,
            page,
            source
        )
    }


    private fun sChapterToMangaChapter(sChapter: SChapter): MangaChapter {
        return MangaChapter(
            sChapter.name,
            sChapter.url,
            sChapter.name,
            null,
            sChapter.scanlator?.trim()?.takeIf { it.isNotBlank() } ?: "Unknown",
            sChapter,
            sChapter.date_upload
        )
    }
}

/**
 * A lazy hoster (Hoster.lazy) shown in the server list without fetching it. Its single
 * placeholder video carries no playable url; [load] fetches the hoster's real videos when
 * the user picks it.
 */
class LazyHosterExtractor internal constructor(
    @Transient private val parser: DynamicAnimeParser,
    @Transient private val source: AnimeSource,
    @Transient private val sEpisode: SEpisode,
    @Transient private val hoster: Hoster,
    private val resolveLazily: Boolean,
) : VideoExtractor() {
    override val server: VideoServer = VideoServer(
        hoster.hosterName.ifBlank { "Server" },
        FileUrl(hoster.hosterUrl),
    )

    init {
        videos = listOf(
            ani.dantotsu.parsers.Video(
                null,
                VideoType.CONTAINER,
                FileUrl(LAZY_PREFIX + hoster.hosterName),
                null,
                "Tap to load",
            )
        )
    }

    override val isPlaceholder: Boolean
        get() = true

    override suspend fun extract(): VideoContainer = VideoContainer(videos)

    /** Fetches this hoster's videos; empty when the hoster has none or failed. */
    suspend fun loadHoster(): List<VideoExtractor> =
        parser.loadLazyHoster(source, sEpisode, hoster, resolveLazily)

    companion object {
        const val LAZY_PREFIX = "lazy-hoster:"
    }
}

class VideoServerPassthrough(private var videoServer: VideoServer) : VideoExtractor() {
    override val server: VideoServer
        get() = videoServer

    // Set by DynamicAnimeParser; used for selected-only resolution and link refresh.
    @Transient internal var parser: DynamicAnimeParser? = null
    @Transient internal var animeSource: AnimeSource? = null
    @Transient internal var sEpisode: SEpisode? = null
    @Transient internal var hosterName: String? = null
    @Transient internal var originalTitle: String = ""
    @Transient internal var unresolved: Boolean = false
    @Transient internal var sourceHeaders: Map<String, String> = emptyMap()

    override val needsResolve: Boolean
        get() = unresolved

    override suspend fun load(): VideoExtractor {
        if (unresolved && !hasPlayableUrl()) {
            // Listed before resolution: show it, but with no playable link yet.
            videos = listOf(placeholderVideo())
            subtitles = emptyList()
            audioTracks = emptyList()
            return this
        }
        return super.load()
    }

    private fun hasPlayableUrl(): Boolean {
        val v = videoServer.video ?: return false
        return (v.videoUrl.isNotBlank() && v.videoUrl != "null") || (v.url.isNotBlank() && v.url != "null")
    }

    private fun placeholderVideo(): ani.dantotsu.parsers.Video {
        val v = videoServer.video
        val quality = v?.resolution ?: v?.videoTitle?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0
        return ani.dantotsu.parsers.Video(
            quality,
            VideoType.CONTAINER,
            FileUrl("$UNRESOLVED_PREFIX${videoServer.name}", videoServer.embed.headers),
            null,
            null,
            null,
            preferred = v?.preferred == true
        )
    }

    /**
     * Runs the source's resolveVideo for this one video (it was listed unresolved).
     * Returns false when the source could not resolve it.
     */
    suspend fun resolveNow(): Boolean {
        if (!unresolved) return true
        val p = parser ?: return false
        val src = animeSource ?: return false
        val video = videoServer.video ?: return false
        val resolved = p.resolveForPlayback(src, video) ?: return false
        replaceVideo(resolved)
        return true
    }

    /** Video as the extension returned it, before a lib-17 HttpServer port was filled in. */
    @Transient private var preServerVideo: Video? = null

    /** True when this video is served by the source's local HttpServer (extensions-lib 17). */
    fun usesHttpServer(): Boolean =
        (preServerVideo ?: videoServer.video)?.usesHttpServer() == true

    /**
     * Creates and starts the source's HttpServer and points this video (and its tracks) at
     * its port. The caller owns the server and must stop() it when the video changes.
     * Returns null when the source has none or it failed to start.
     */
    suspend fun startHttpServer(): HttpServer? {
        val src = animeSource as? AnimeHttpSource ?: return null
        val base = preServerVideo ?: videoServer.video ?: return null
        if (!base.usesHttpServer()) return null
        val server = withContext(Dispatchers.IO) {
            try {
                src.createHttpServer()?.also { it.start() }
            } catch (e: Throwable) {
                Logger.log("HttpServer failed to start: ${e.message}")
                null
            }
        } ?: return null
        preServerVideo = base
        replaceVideo(base.copyHttpServer(server.listeningPort))
        return server
    }

    override suspend fun refreshLinks(): Boolean {
        val p = parser ?: return false
        val src = animeSource ?: return false
        val ep = sEpisode ?: return false
        val fresh = p.refetchVideo(src, ep, hosterName, originalTitle, videoServer.video?.resolution)
            ?: return false
        preServerVideo = null
        replaceVideo(fresh)
        return true
    }

    private suspend fun replaceVideo(fresh: Video) {
        val titled = fresh.copy(videoTitle = videoServer.video?.videoTitle ?: fresh.videoTitle)
        val url = titled.videoUrl.takeIf { it.isNotBlank() && it != "null" } ?: titled.url
        videoServer = videoServer.copy(
            embed = FileUrl(url, StreamHeaders.merge(sourceHeaders, titled.headers.toFlatMap())),
            video = titled,
        )
        unresolved = false
        super.load()
        resolvedAtMs = System.currentTimeMillis()
    }

    override suspend fun extract(): VideoContainer {
        val vidList = listOfNotNull(videoServer.video?.let { aniVideoToSaiVideo(it) })
        val headersMap = vidList.firstOrNull()?.file?.headers
            ?: StreamHeaders.merge(sourceHeaders, videoServer.video?.headers.toFlatMap())
        val videoUrl = vidList.firstOrNull()?.file?.url ?: ""
        val subList = videoServer.video?.subtitleTracks?.map { trackToSubtitle(it, videoUrl, headersMap) } ?: emptyList()
        val audioList = videoServer.video?.audioTracks ?: emptyList()

        return if (vidList.isNotEmpty()) {
            VideoContainer(vidList, subList, audioList)
        } else {
            throw Exception("No videos found")
        }
    }

    companion object {
        const val UNRESOLVED_PREFIX = "unresolved:"
    }

    private fun aniVideoToSaiVideo(aniVideo: Video): ani.dantotsu.parsers.Video {
        // Find the number value from the .quality string
        val number = Regex("""\d+""").find(aniVideo.quality)?.value?.toInt() ?: 0

        // Check for null video URL 
        val videoUrl = aniVideo.videoUrl.takeIf { it.isNotBlank() && it != "null" }
            ?: aniVideo.url.takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Video URL is null")

        var format: VideoType?

        try {
            val urlObj = URL(videoUrl)
            val path = urlObj.path
            val query = urlObj.query

            format = getVideoType(path)

            if (format == null && query != null) {
                val queryPairs: List<Pair<String, String>> = query.split("&").mapNotNull {
                    val idx = it.indexOf("=")
                    if (idx != -1) {
                        val key = runCatching { URLDecoder.decode(it.substring(0, idx), "UTF-8") }.getOrNull() ?: ""
                        val value = runCatching { URLDecoder.decode(it.substring(idx + 1), "UTF-8") }.getOrNull() ?: ""
                        Pair(key, value)
                    } else null
                }

                val typeParam = queryPairs.find { it.first.equals("type", ignoreCase = true) }?.second ?: ""
                if (typeParam.isNotBlank()) {
                    format = getVideoType(typeParam)
                }

                if (format == null) {
                    val targetParam = queryPairs.find { it.first == "url" || it.first == "file" }?.second ?: ""
                    if (targetParam.isNotBlank()) {
                        format = getVideoType(targetParam)
                    }
                }

                if (format == null && (videoUrl.contains(".m3u8", ignoreCase = true) || query.contains(".m3u8", ignoreCase = true))) {
                    format = VideoType.M3U8
                }
            }

            if (format == null) {
                if (StreamTypeDetector.isLocalMp4(urlObj)) {
                    format = VideoType.CONTAINER
                } else if (StreamTypeDetector.isLocalhost(urlObj) && !StreamTypeDetector.isTorrentStream(urlObj)) {
                    if (StreamTypeDetector.isLocalDash(urlObj)) {
                        format = VideoType.DASH
                    } else {
                        format = VideoType.M3U8
                    }
                }
            }

            if (format == null) {
                val titleOrQuality = "${aniVideo.videoTitle} ${aniVideo.quality}"
                if (titleOrQuality.contains("hls", ignoreCase = true) || titleOrQuality.contains("m3u8", ignoreCase = true)) {
                    format = VideoType.M3U8
                } else if (titleOrQuality.contains("dash", ignoreCase = true) || titleOrQuality.contains("mpd", ignoreCase = true)) {
                    format = VideoType.DASH
                }
            }

            // If the format is still undetermined, log an error
            if (format == null) {
                Logger.log("Unknown video format: $videoUrl")
                format = VideoType.CONTAINER
            }
        } catch (malformed: MalformedURLException) {
            if (videoUrl.startsWith("magnet:") || videoUrl.endsWith(".torrent"))
                format = VideoType.CONTAINER
            else
                throw malformed
        }
        // defaults are added by the player; here source.headers -> video.headers
        val headersMap: Map<String, String> =
            StreamHeaders.merge(sourceHeaders, aniVideo.headers.toFlatMap())


        return Video(
            number,
            format!!,
            FileUrl(videoUrl, headersMap),
            null,
            null,
            parseDrmInfo(aniVideo.internalData),
            preferred = aniVideo.preferred,
            mimeTypeHint = mimeTypeHintOf(aniVideo),
        )
    }

    /**
     * Honours the container hints Aniyomi extensions give mpv/ffmpeg: a forced MPEG-TS
     * demuxer ("demuxer-lavf-format=mpegts", ffmpeg "-f mpegts", or a "force_mpegts" flag).
     * Some hosts serve TS segments behind .jpg/.png/.html urls that ExoPlayer can't sniff.
     */
    private fun mimeTypeHintOf(aniVideo: Video): String? {
        val args = aniVideo.mpvArgs + aniVideo.ffmpegStreamArgs + aniVideo.ffmpegVideoArgs
        val forcesTs = args.any { (key, value) ->
            val k = key.trim().trimStart('-').lowercase()
            val v = value.trim().lowercase()
            (k == "force_mpegts" && v != "no" && v != "false" && v != "0") ||
                ((k == "demuxer-lavf-format" || k == "f" || k == "format") && v == "mpegts")
        }
        return if (forcesTs) androidx.media3.common.MimeTypes.VIDEO_MP2T else null
    }

    /**
     * Extract DRM parameters an extension passed through [Video.internalData].
     *
     * Expected shape (all fields optional except licenseUrl):
     * ```json
     * {
     *   "drmScheme": "widevine",
     *   "licenseUrl": "https://example.com/license",
     *   "licenseHeaders": { "Authorization": "Bearer ..." }
     * }
     * ```
     * Returns null for anything absent or malformed, so a clear stream - or an
     * extension using internalData for its own unrelated purposes - is unaffected.
     */
    private fun parseDrmInfo(internalData: String): DrmInfo? {
        if (internalData.isBlank()) return null
        return runCatching {
            val obj = JSONObject(internalData)
            val licenseUrl = obj.optString("licenseUrl")
            if (licenseUrl.isBlank()) return null

            fun headersOf(source: JSONObject?, name: String) =
                source?.optJSONObject(name)?.let { json ->
                    json.keys().asSequence().associateWith { key -> json.optString(key) }
                } ?: mapOf()

            val offline = obj.optJSONObject("offline")?.let { off ->
                val manifest = off.optString("manifestUrl")
                val offlineLicense = off.optString("licenseUrl")
                if (manifest.isBlank() || offlineLicense.isBlank()) {
                    null
                } else {
                    OfflineDrmInfo(
                        manifestUrl = manifest,
                        licenseUrl = offlineLicense,
                        licenseHeaders = headersOf(off, "licenseHeaders"),
                        headers = headersOf(off, "headers"),
                    )
                }
            }

            DrmInfo(
                scheme = obj.optString("drmScheme").ifBlank { "widevine" },
                licenseUrl = licenseUrl,
                licenseHeaders = headersOf(obj, "licenseHeaders"),
                offline = offline,
            )
        }.getOrNull()
    }

    private fun getVideoType(fileName: String): VideoType? {
        val type = when {
            fileName.endsWith(".mp4", ignoreCase = true) || fileName.endsWith(
                ".mkv",
                ignoreCase = true
            ) || fileName.endsWith(
                ".webm",
                ignoreCase = true
            ) || fileName.contains("/mp4", ignoreCase = true) ||
                    fileName.contains("format=mp4", ignoreCase = true) -> VideoType.CONTAINER

            fileName.endsWith(".m3u8", ignoreCase = true) ||
                    fileName.contains(".m3u8", ignoreCase = true) ||
                    fileName.contains("/m3u8", ignoreCase = true) ||
                    fileName.contains("/pl/", ignoreCase = true) ||
                    fileName.contains("/playlist/", ignoreCase = true) ||
                    fileName.contains("/hls/", ignoreCase = true) ||
                    fileName.contains("format=m3u8", ignoreCase = true) ||
                    fileName.contains("type=m3u8", ignoreCase = true) ||
                    fileName.equals("m3u8", ignoreCase = true) -> VideoType.M3U8
            fileName.endsWith(".mpd", ignoreCase = true) ||
                    fileName.contains(".mpd", ignoreCase = true) ||
                    fileName.contains("/mpd", ignoreCase = true) ||
                    fileName.contains("/dash/", ignoreCase = true) ||
                    fileName.contains("format=mpd", ignoreCase = true) -> VideoType.DASH
            else -> null
        }

        return type
    }

    @Suppress("unused")
    private fun headRequest(fileName: String, networkHelper: NetworkHelper): VideoType? {
        return try {
            Logger.log("attempting head request for $fileName")
            val request = Request.Builder()
                .url(fileName)
                .head()
                .build()

            networkHelper.client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")
                val contentDisposition = response.header("Content-Disposition")

                if (contentType != null) {
                    when {
                        contentType.contains("mpegurl", ignoreCase = true) -> VideoType.M3U8
                        contentType.contains("dash", ignoreCase = true) -> VideoType.DASH
                        contentType.contains("mp4", ignoreCase = true) -> VideoType.CONTAINER
                        else -> null
                    }
                } else if (contentDisposition != null) {
                    when {
                        contentDisposition.contains("mpegurl", ignoreCase = true) -> VideoType.M3U8
                        contentDisposition.contains("dash", ignoreCase = true) -> VideoType.DASH
                        contentDisposition.contains("mp4", ignoreCase = true) -> VideoType.CONTAINER
                        else -> null
                    }
                } else {
                    Logger.log("failed head request for $fileName")
                    null
                }

            }
        } catch (e: Exception) {
            Logger.log("Exception in headRequest: $e")
            null
        }

    }

    private fun trackToSubtitle(
        track: Track,
        videoUrl: String = "",
        headers: Map<String, String> = emptyMap()
    ): Subtitle {
        val resolvedUrl = ani.dantotsu.media.anime.player.PlayerSubtitleManager.resolveSubtitleUrl(track.url, videoUrl)
        var type = findSubtitleTypeFromUrl(resolvedUrl)
        if (type == SubtitleType.UNKNOWN) {
            val lower = resolvedUrl.lowercase(Locale.ROOT)
            type = when {
                lower.contains(".ass") || lower.contains(".ssa") || lower.contains("format=ass") -> SubtitleType.ASS
                lower.contains(".vtt") || lower.contains("format=vtt") -> SubtitleType.VTT
                lower.contains(".srt") || lower.contains("format=srt") -> SubtitleType.SRT
                else -> SubtitleType.VTT
            }
        }
        return Subtitle(track.lang, FileUrl(resolvedUrl, headers), type)
    }

    private fun findSubtitleTypeFromUrl(url: String): SubtitleType {
        val normalizedUrl = url.substringBefore('#').substringBefore('?')
        val fromPath = extensionToSubtitleType(normalizedUrl)
        if (fromPath != SubtitleType.UNKNOWN) return fromPath

        val encodedCandidate =
            try {
                val query = URL(url).query ?: return SubtitleType.UNKNOWN
                query
                    .split('&')
                    .asSequence()
                    .mapNotNull { part ->
                        val idx = part.indexOf('=')
                        if (idx == -1) null else URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                    }.firstOrNull { value ->
                        extensionToSubtitleType(value) != SubtitleType.UNKNOWN
                    }
            } catch (_: Exception) {
                null
            }

        return extensionToSubtitleType(encodedCandidate ?: "")
    }

    private fun extensionToSubtitleType(value: String): SubtitleType {
        val lower = value.lowercase(Locale.ROOT)
        return when {
            hasExtensionMarker(lower, ".vtt") -> SubtitleType.VTT
            hasExtensionMarker(lower, ".ass", ".ssa") -> SubtitleType.ASS
            hasExtensionMarker(lower, ".srt") -> SubtitleType.SRT
            else -> SubtitleType.UNKNOWN
        }
    }

    private fun hasExtensionMarker(value: String, vararg extensions: String): Boolean {
        val base = value.substringBefore('#').substringBefore('?').substringBefore('&')
        return extensions.any { ext ->
            base.endsWith(ext)
        }
    }
}

private suspend fun AnimeSource.getEpisodeListCompat(anime: SAnime): List<SEpisode> {
    return try {
        getAnimeEpisodeUpdate(anime, emptyList(), fetchDetails = false, fetchEpisodes = true).episodes
    } catch (e: UnsupportedOperationException) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (e: NoSuchMethodError) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (e: AbstractMethodError) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (e: NotImplementedError) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    } catch (_: Throwable) {
        runCatching { getEpisodeList(anime) }.getOrDefault(emptyList())
    }
}

private suspend fun AnimeSource.getSeasonListCompat(anime: SAnime): List<SAnime> {
    return try {
        getAnimeSeasonUpdate(anime, emptyList(), fetchDetails = false, fetchSeasons = true).seasons
    } catch (e: UnsupportedOperationException) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (e: NoSuchMethodError) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (e: AbstractMethodError) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (e: NotImplementedError) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    } catch (_: Throwable) {
        runCatching { getSeasonList(anime) }.getOrDefault(emptyList())
    }
}
