package ani.dantotsu.media.anime.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.MediaDataSourceFactory
import ani.dantotsu.media.anime.VideoCache
import ani.dantotsu.parsers.StreamHeaders
import ani.dantotsu.parsers.StreamTypeDetector
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Warms the video cache with the first ~10 s of the next episode once its links are known
 * (the player prefetches them near the end of the current episode), so Next starts from
 * disk instead of a cold CDN connection.
 *
 * Progressive files: the first 4 MiB. HLS: the first few segments of the rendition the
 * player is likely to start with (highest BANDWIDTH under the initial bitrate estimate's
 * usable share). Streams the player doesn't cache (loopback/torrent, DRM, content://) are
 * skipped. Everything here is best effort.
 */
@UnstableApi
object NextEpisodePreloader {

    private const val PROGRESSIVE_BYTES = 4L * 1024 * 1024
    private const val HLS_SEGMENTS = 3
    // ~70% of DantotsuPlayerManager's 12 Mbit/s initial estimate (ExoPlayer's ABR fraction)
    private const val HLS_TARGET_BANDWIDTH = 8_400_000L

    @Volatile
    private var job: Job? = null

    @Volatile
    private var lastUrl: String? = null

    fun preload(
        context: Context,
        scope: CoroutineScope,
        video: Video,
        sourceClient: OkHttpClient?,
        fallbackClient: OkHttpClient,
    ) {
        val url = video.file.url
        if (!url.startsWith("http", ignoreCase = true)) return
        if (video.drm != null) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (StreamTypeDetector.isLocalhost(uri)) return
        if (lastUrl == url && job?.isActive == true) return
        lastUrl = url
        job?.cancel()
        val appContext = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            try {
                val headers = StreamHeaders.merge(defaultHeaders, video.file.headers)
                val client = MediaDataSourceFactory.dropRateLimiters((sourceClient ?: fallbackClient).newBuilder())
                    .cache(null)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .build()
                val upstream = MediaDataSourceFactory.buildOkHttpFactory(client, headers)
                val cacheFactory = CacheDataSource.Factory()
                    .setCache(VideoCache.getInstance(appContext))
                    .setUpstreamDataSourceFactory(upstream)
                val isHls = video.format == VideoType.M3U8 ||
                    url.contains(".m3u8", ignoreCase = true)
                when {
                    isHls -> {
                        for (segment in firstHlsSegments(client, headers, url)) {
                            if (!isActive) break
                            cache(cacheFactory, segment, C.LENGTH_UNSET.toLong())
                        }
                    }
                    video.format == VideoType.CONTAINER -> cache(cacheFactory, url, PROGRESSIVE_BYTES)
                }
                Logger.log("Next episode preloaded")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.log("Next episode preload skipped: ${e.message}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        lastUrl = null
    }

    private fun cache(factory: CacheDataSource.Factory, url: String, length: Long) {
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(0)
            .setLength(length)
            .build()
        CacheWriter(factory.createDataSource(), spec, null, null).cache()
    }

    private fun fetchText(client: OkHttpClient, headers: Map<String, String>, url: String): String? {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }

    private fun resolve(base: String, ref: String): String? =
        base.toHttpUrlOrNull()?.resolve(ref.trim())?.toString()

    private fun firstHlsSegments(
        client: OkHttpClient,
        headers: Map<String, String>,
        url: String,
    ): List<String> {
        var playlistUrl = url
        var text = fetchText(client, headers, playlistUrl) ?: return emptyList()
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            val variants = mutableListOf<Pair<Long, String>>()
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val bandwidth = Regex("""[:,]BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val ref = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                resolve(playlistUrl, ref)?.let { variants += bandwidth to it }
            }
            val chosen = variants.filter { it.first <= HLS_TARGET_BANDWIDTH }.maxByOrNull { it.first }
                ?: variants.minByOrNull { it.first }
                ?: return emptyList()
            playlistUrl = chosen.second
            text = fetchText(client, headers, playlistUrl) ?: return emptyList()
        }
        // byte-range segments are cached under different keys by the player: skip
        if (text.contains("#EXT-X-BYTERANGE")) return emptyList()
        return text.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(HLS_SEGMENTS)
            .mapNotNull { resolve(playlistUrl, it) }
    }
}
