package ani.dantotsu.media.anime.player

import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.anime.AudioFocusListener
import ani.dantotsu.media.anime.MediaDataSourceFactory
import ani.dantotsu.media.anime.VideoCache
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.StreamHeaders
import ani.dantotsu.parsers.StreamTypeDetector
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import io.github.peerless2012.ass.media.kt.withAssSupport
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.zip.GZIPInputStream

@UnstableApi
class DantotsuPlayerManager(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val subtitleManager: PlayerSubtitleManager,
    private val client: OkHttpClient,
    private val onPlayerErrorCallback: (error: PlaybackException) -> Unit
) {

    var exoPlayer: ExoPlayer? = null
        private set
    var trackSelector: DefaultTrackSelector? = null
        private set
    var mediaSession: MediaSession? = null
        private set
    var audioFocusListener: AudioFocusListener? = null
        private set

    var mediaSource: MediaSource? = null
        private set
    var currentMediaItem: MediaItem? = null
        private set
    var isInitialized = false
        private set

    // Buffering tuned for streaming from flaky anime CDNs over mobile data: start fast
    // (1 s), re-start after a stall with 2 s, but keep up to 60-120 s ahead so short network
    // dips never reach the screen. The back buffer lets a short rewind play without a refetch.
    private val DEFAULT_MIN_BUFFER_MS = 60_000
    private val DEFAULT_MAX_BUFFER_MS = 120_000
    private val BUFFER_FOR_PLAYBACK_MS = 1_000
    private val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
    private val BACK_BUFFER_DURATION_MS = 30_000

    // Start HLS/DASH at a high rendition instead of ExoPlayer's conservative default
    // estimate; it steps down if the network can't keep up.
    private val INITIAL_BITRATE_ESTIMATE = 12_000_000L

    // A flaky CDN segment is retried 6 times (ExoPlayer's default is 3) before the player
    // gives up and the error handler takes over.
    private val loadErrorPolicy = DefaultLoadErrorHandlingPolicy(6)

    private val bandwidthMeter: DefaultBandwidthMeter by lazy {
        DefaultBandwidthMeter.Builder(activity.applicationContext)
            .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE)
            .build()
    }

    /** LoadControl flavour the current player was built with (torrents buffer by bytes). */
    private var builtForTorrent: Boolean? = null

    private val subLanguages = arrayOf(
        "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
        "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
        "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
        "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
        "Turkish", "Ukrainian", "Urdu", "Vietnamese"
    )

    /**
     * Track selection preferences, applied to the selector *before* the player is built so
     * the first selection is already right (no audio/text switch after prepare).
     * Audio: Japanese for sub watchers; for dub watchers the subtitle language, then English.
     */
    private fun buildTrackSelectionParameters(): DefaultTrackSelector.Parameters {
        val langName = subLanguages.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"
        val langCode = LanguageMapper.getLanguageCode(langName)
        val preferDub = PrefManager.getVal<Boolean>(PrefName.SettingsPreferDub)
        return DefaultTrackSelector.Parameters.Builder(activity).apply {
            if (langCode.isNotBlank() && !langCode.equals("all", ignoreCase = true)) {
                setPreferredTextLanguage(langCode)
            }
            if (preferDub) {
                val dubLangs = listOf(langCode, "en").filter { it.isNotBlank() && !it.equals("all", true) }.distinct()
                setPreferredAudioLanguages(*dubLangs.toTypedArray())
            } else {
                setPreferredAudioLanguages("ja")
            }
            // never let the selector pick a track the decoder can't take
            setExceedRendererCapabilitiesIfNecessary(true)
        }.build()
    }

    fun initTrackSelector() {
        trackSelector = DefaultTrackSelector(activity, buildTrackSelectionParameters())
    }

    /**
     * The client a stream is fetched with: the extension source's client (cookies,
     * Cloudflare, DoH, its interceptors) with the disk cache removed and no call timeout (a
     * progressive stream is one long call). Loopback streams use system DNS (DoH resolvers
     * refuse "localhost") and get the NanoHTTPD gzip/m3u8 workarounds.
     */
    private fun streamClient(base: OkHttpClient, isLocalhost: Boolean): OkHttpClient =
        base.newBuilder().apply {
            cache(null)
            callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            if (isLocalhost) dns(Dns.SYSTEM)
            MediaDataSourceFactory.dropRateLimiters(this)
            addLocalhostWorkarounds(this)
        }.build()

    /** NanoHTTPD (torrent server / local proxies) workarounds; no-ops for remote hosts. */
    private fun addLocalhostWorkarounds(builder: OkHttpClient.Builder) {
        builder.apply {
            // Strip Accept-Encoding on the wire for localhost/127.0.0.1 NanoHTTPD server.
            // When NanoHTTPD forwards requests upstream to CDNs (like imgnex/megaplay),
            // having NO Accept-Encoding header allows NanoHTTPD's internal OkHttpClient
            // to enable transparentGzip = true, which automatically decompresses
            // gzipped M3U8 playlists. If Accept-Encoding is sent (even "identity" or "gzip"),
            // transparentGzip is disabled in NanoHTTPD and it returns mangled binary gzip
            // which causes ExoPlayer ParserException: Input does not start with #EXTM3U.
            addNetworkInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isLocal = StreamTypeDetector.isLocalhost(host)
                val newRequest = if (isLocal) {
                    request.newBuilder()
                        .removeHeader("Accept-Encoding")
                        .build()
                } else {
                    request
                }
                chain.proceed(newRequest)
            }
            addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isLocal = StreamTypeDetector.isLocalhost(host)
                val newRequest = if (isLocal) {
                    request.newBuilder()
                        .removeHeader("Accept-Encoding")
                        .build()
                } else {
                    request
                }
                val response = chain.proceed(newRequest)
                if (isLocal && response.isSuccessful) {
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val isMediaContent = contentType.startsWith("video/") ||
                        contentType.startsWith("audio/") ||
                        contentType.startsWith("image/")

                    val isSegment = isMediaContent || StreamTypeDetector.isSegmentUrl(request.url)
                    val isM3u8 = !isSegment && (
                        contentType.contains("mpegurl") ||
                        StreamTypeDetector.isM3u8Url(request.url)
                    )
                    val hasGzipEncoding = response.header("Content-Encoding")?.equals("gzip", ignoreCase = true) == true

                    val body = response.body
                    if (body != null && !isSegment && (isM3u8 || hasGzipEncoding)) {
                        val rawBytes = body.bytes()
                        val isGzip = (rawBytes.size >= 2 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) || hasGzipEncoding
                        val decompressedBytes = if (isGzip) {
                            try {
                                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
                            } catch (_: Exception) {
                                rawBytes
                            }
                        } else {
                            rawBytes
                        }

                        val startsWithHls = StreamTypeDetector.startsWithHlsMagic(decompressedBytes)
                        val finalBytes = if (startsWithHls || isM3u8) {
                            var text = decompressedBytes.toString(Charsets.UTF_8)
                            if (text.startsWith("\uFEFF")) {
                                text = text.substring(1)
                            }
                            text.trimStart().toByteArray(Charsets.UTF_8)
                        } else {
                            decompressedBytes
                        }

                        val mediaType = (if (startsWithHls || isM3u8) {
                            "application/vnd.apple.mpegurl"
                        } else {
                            body.contentType()?.toString()
                        })?.toMediaTypeOrNull() ?: body.contentType()

                        val strippedHeaders = response.headers.newBuilder()
                            .removeAll("Content-Encoding")
                            .removeAll("Content-Length")
                            .build()

                        val newBody = finalBytes.toResponseBody(mediaType)
                        response.newBuilder()
                            .headers(strippedHeaders)
                            .body(newBody)
                            .build()
                    } else {
                        response
                    }
                } else {
                    response
                }
            }
        }
    }

    fun buildMediaSource(
        video: Video,
        subConfigs: List<MediaItem.SubtitleConfiguration>,
        mimeType: String?,
        downloadedMediaItem: MediaItem?,
        mediaMetadata: MediaMetadata? = null,
        audioTracks: List<eu.kanade.tachiyomi.animesource.model.Track> = emptyList(),
        sourceClient: OkHttpClient? = null,
    ): Pair<MediaSource, MediaItem> {
        val videoUri = runCatching { video.file.url.toUri() }.getOrNull()
        val isLocalhost = StreamTypeDetector.isLocalhost(videoUri)
        val isLocalhostTorrent = StreamTypeDetector.isTorrentStream(videoUri)
        val isLocalhostMp4 = StreamTypeDetector.isLocalMp4(videoUri)

        // defaults -> source.headers -> video.headers, case-insensitive, one User-Agent
        var headers = StreamHeaders.merge(defaultHeaders, video.file.headers)
        if (isLocalhost) {
            headers = StreamHeaders.without(headers, "Accept-Encoding")
        }

        // Loopback streams (torrent server, extension HttpServer) don't need the source's
        // interceptors (rate limits, Cloudflare); remote ones do.
        val httpClient = streamClient(if (isLocalhost) client else (sourceClient ?: client), isLocalhost)
        val httpDataSourceFactory = MediaDataSourceFactory.resolveHttpFactory(
            context = activity,
            headers = headers,
            okHttpClient = httpClient,
            isLocalhost = isLocalhost,
        )

        val upstream = DefaultDataSource.Factory(activity, httpDataSourceFactory)
        val segmentCacheFactory: DataSource.Factory = CacheDataSource.Factory()
            .setCache(VideoCache.getInstance(activity))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Playlists (.m3u8/.mpd) go straight to the network: a cached playlist outlives its
        // signed segment urls (reopening an episode replayed it and every segment got 403).
        val isManifestStream = video.format == VideoType.M3U8 || video.format == VideoType.DASH ||
            mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8 ||
            mimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD
        val cacheFactory: DataSource.Factory = PlaylistBypassDataSourceFactory(
            cached = segmentCacheFactory,
            direct = upstream,
            manifestUrl = video.file.url.takeIf { isManifestStream },
        )

        val extractorsFactory = subtitleManager.createExtractorsFactory()
        val assParserFactory = subtitleManager.createSubtitleParserFactory()
        // Some providers wrap the license response; normalise it before the CDM sees it.
        val drmProvider = WrappedDrmCallback.providerFor(httpDataSourceFactory)
        val assMediaSourceFactory = DefaultMediaSourceFactory(cacheFactory, extractorsFactory)
            .setSubtitleParserFactory(assParserFactory)
            .setDrmSessionManagerProvider(drmProvider)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)

        val mediaItem = downloadedMediaItem?.buildUpon()?.apply {
            if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
        }?.build() ?: MediaItem.Builder()
            .setUri(video.file.url.toUri())
            .apply {
                val effectiveMimeType = mimeType ?: if (isLocalhost && !isLocalhostTorrent && !isLocalhostMp4 && video.format != VideoType.DASH && video.format != VideoType.CONTAINER) {
                    androidx.media3.common.MimeTypes.APPLICATION_M3U8
                } else null
                if (effectiveMimeType != null) setMimeType(effectiveMimeType)
                if (subConfigs.isNotEmpty()) setSubtitleConfigurations(subConfigs)
                if (mediaMetadata != null) setMediaMetadata(mediaMetadata)
                video.drm?.let { drm ->
                    // The device CDM does the decryption; this only says where the
                    // license server is.
                    val uuid = when (drm.scheme.lowercase()) {
                        "playready" -> C.PLAYREADY_UUID
                        "clearkey" -> C.CLEARKEY_UUID
                        else -> C.WIDEVINE_UUID
                    }
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(uuid)
                            .setLicenseUri(drm.licenseUrl)
                            .setLicenseRequestHeaders(drm.licenseHeaders)
                            // Video and audio can carry separate keys.
                            .setMultiSession(true)
                            .build()
                    )
                    Logger.log("DRM: ${drm.scheme} license @ ${drm.licenseUrl}")
                }
            }
            .build()
        this.currentMediaItem = mediaItem

        // A downloaded episode is played from its own uri, which is not the one
        // the extension handed us, so the scheme has to come from the media item.
        val isContentUri =
            (mediaItem.localConfiguration?.uri?.scheme ?: video.file.url.substringBefore(":")) == "content"

        val activeFactory = if (isContentUri) {
            val localDataSourceFactory = DefaultDataSource.Factory(activity)
            DefaultMediaSourceFactory(localDataSourceFactory, extractorsFactory)
                .setSubtitleParserFactory(assParserFactory)
                .setDrmSessionManagerProvider(drmProvider)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
        } else if (isLocalhostTorrent || isLocalhost) {
            // Direct upstream for localhost streams (torrents and local extension proxies)
            // - avoids double writing to flash via VideoCache and avoids Range request issues with NanoHTTPD
            DefaultMediaSourceFactory(upstream, extractorsFactory)
                .setSubtitleParserFactory(assParserFactory)
                .setDrmSessionManagerProvider(drmProvider)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
        } else {
            assMediaSourceFactory
        }
        this.activeMediaSourceFactory = activeFactory
        val primarySource = activeFactory.createMediaSource(mediaItem)

        val audioSources = mutableListOf<MediaSource>()
        audioTracks.forEach { audioTrack ->
            val audioUrl = audioTrack.url
            if (audioUrl.isNotBlank() && audioUrl != video.file.url) {
                val audioMimeType = when {
                    audioUrl.contains(".m3u8", ignoreCase = true) || audioUrl.contains("/m3u8", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    audioUrl.contains(".mpd", ignoreCase = true) || audioUrl.contains("/mpd", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                    else -> null
                }
                val audioMediaItem = MediaItem.Builder()
                    .setUri(audioUrl.toUri())
                    .apply {
                        if (audioMimeType != null) setMimeType(audioMimeType)
                    }
                    .build()
                val audioSource = activeFactory.createMediaSource(audioMediaItem)
                audioSources.add(audioSource)
            }
        }

        val finalSource = if (audioSources.isNotEmpty()) {
            MergingMediaSource(primarySource, *audioSources.toTypedArray())
        } else {
            primarySource
        }

        this.mediaSource = finalSource
        return Pair(finalSource, mediaItem)
    }

    var activeMediaSourceFactory: MediaSource.Factory? = null

    fun applyUpdatedSubtitles(newSubConfigs: List<MediaItem.SubtitleConfiguration>, position: Long) {
        val player = exoPlayer ?: return
        val currentItem = currentMediaItem ?: return

        // Snapshot current track parameters so audio selection survives the media-source rebuild.
        val savedTrackParams = player.trackSelectionParameters

        val newMediaItem = currentItem.buildUpon()
            .setSubtitleConfigurations(newSubConfigs)
            .build()
        this.currentMediaItem = newMediaItem

        val factory = activeMediaSourceFactory
        if (factory != null) {
            val newSource = factory.createMediaSource(newMediaItem)
            this.mediaSource = newSource
            player.setMediaSource(newSource, position)
        } else {
            player.setMediaItem(newMediaItem, position)
        }
        player.prepare()
        // Restore track selection so the audio track (and any disabled text tracks) are not reset.
        player.trackSelectionParameters = savedTrackParams
        player.play()
    }

    /**
     * Starts the media source built by [buildMediaSource] at [startPositionMs].
     *
     * The player is created once and reused for every episode and server change: only the
     * media source is swapped (setMediaSource with the start position, so there is no seek
     * after prepare and no throw-away load from 0). A new player is built only the first
     * time, or when the stream kind changes between torrent and non-torrent (different
     * LoadControl).
     */
    fun startPlayback(
        startPositionMs: Long,
        playbackParameters: PlaybackParameters,
        listener: Player.Listener,
    ): ExoPlayer {
        val player = exoPlayer
        val isTorrent = StreamTypeDetector.isTorrentStream(currentMediaItem?.localConfiguration?.uri)
        if (player == null || !isInitialized || builtForTorrent != isTorrent) {
            return buildExoplayer(startPositionMs, playbackParameters, listener)
        }
        playerView.player = player
        player.playbackParameters = playbackParameters
        player.playWhenReady = true
        mediaSource?.let { player.setMediaSource(it, startPositionMs.coerceAtLeast(0L)) }
        player.prepare()
        return player
    }

    fun buildExoplayer(
        playbackPosition: Long,
        playbackParameters: PlaybackParameters,
        listener: Player.Listener,
        forceDefaultRenderers: Boolean = false
    ): ExoPlayer {
        releaseExoPlayer()

        val uri = currentMediaItem?.localConfiguration?.uri
        val isTorrentStream = StreamTypeDetector.isTorrentStream(uri)
        val targetBufferBytes = if (isTorrentStream) 48 * 1024 * 1024 else androidx.media3.common.C.LENGTH_UNSET
        val loadControl = DefaultLoadControl.Builder()
            // retain from the previous keyframe, so a rewind into the back buffer needs no refetch
            .setBackBuffer(BACK_BUFFER_DURATION_MS, true)
            .setBufferDurationsMs(
                DEFAULT_MIN_BUFFER_MS,
                DEFAULT_MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        builtForTorrent = isTorrentStream

        subtitleManager.initAssHandler()
        val handler = subtitleManager.assHandler!!
        Logger.log("Libass: Calling renderersFactory.withAssSupport()")
        // forceDefaultRenderers (retry after a renderer runtime-check failure): ExoPlayer's
        // own codec order, without the AAC software-first selector below.
        val renderersFactory = DefaultRenderersFactory(activity)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (forceDefaultRenderers) MediaCodecSelector.DEFAULT else object : MediaCodecSelector {
                override fun getDecoderInfos(
                    mimeType: String,
                    requiresSecureDecoder: Boolean,
                    requiresTunnelingDecoder: Boolean
                ): List<MediaCodecInfo> {
                    val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                    if (mimeType.equals(MimeTypes.AUDIO_AAC, ignoreCase = true)) {
                        // Prefer Google's software decoder for AAC to fix AAC Main (mp4a.40.1) crashes on hardware decoders (especially Samsung/Qualcomm)
                        val (softwareDecoders, hardwareDecoders) = decoders.partition {
                            it.name.startsWith("c2.android.", ignoreCase = true) ||
                            it.name.startsWith("OMX.google.", ignoreCase = true) ||
                            it.name.contains("sw", ignoreCase = true)
                        }
                        return softwareDecoders + hardwareDecoders
                    }
                    return decoders
                }
            })
            .withAssSupport(handler)

        val mediaSourceFactory = activeMediaSourceFactory ?: DefaultMediaSourceFactory(activity)
            .setSubtitleParserFactory(subtitleManager.createSubtitleParserFactory())
            .setLoadErrorHandlingPolicy(loadErrorPolicy)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Preferences go in before build() so the very first track selection uses them.
        val newTrackSelector = DefaultTrackSelector(activity, buildTrackSelectionParameters())
        this.trackSelector = newTrackSelector

        val player = ExoPlayer.Builder(activity, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(newTrackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        this.exoPlayer = player
        playerView.player = player

        // Audio focus is handled by ExoPlayer itself (setAudioAttributes(..., true)); a second
        // focus listener used to fight it and resumed playback the user had paused.

        Logger.log("Libass: Calling handler.init(exoPlayer)")
        handler.init(player)

        player.playWhenReady = true
        player.playbackParameters = playbackParameters
        // Start position goes with the source: no prepare-from-0 followed by a seek.
        mediaSource?.let { player.setMediaSource(it, playbackPosition.coerceAtLeast(0L)) }
        player.prepare()

        try {
            val rightNow = Calendar.getInstance()
            mediaSession = MediaSession.Builder(activity, player)
                .setId(rightNow.timeInMillis.toString())
                .build()
        } catch (e: Exception) {
            toast(e.toString())
        }

        player.addListener(listener)
        isInitialized = true
        return player
    }

    fun releaseExoPlayer() {
        audioFocusListener?.abandonRequest()
        audioFocusListener = null
        isInitialized = false
        playerView.player = null
        exoPlayer?.let { p ->
            p.stop()
            p.clearMediaItems()
            p.release()
        }
        exoPlayer = null
        trackSelector = null
        builtForTorrent = null
        mediaSession?.release()
        mediaSession = null
    }

    fun release() {
        releaseExoPlayer()
        // VideoCache is process-wide and may already be used by a newly started player
        // instance, so it is not released per activity.
        subtitleManager.release()
    }
}