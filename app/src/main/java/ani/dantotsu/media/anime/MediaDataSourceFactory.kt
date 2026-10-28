package ani.dantotsu.media.anime

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import ani.dantotsu.parsers.StreamHeaders
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * HTTP DataSource for Media3 / ExoPlayer.
 *
 * Default: [OkHttpDataSource] on the extension source's client (see DantotsuPlayerManager),
 * so the source's cookies, Cloudflare clearance, DoH and interceptors apply to the stream.
 * This matters in India, where ISP DNS blocks of anime CDNs are common and only DoH gets
 * through.
 *
 * Opt-in ([PrefName.UseCronet]): [CronetDataSource] through the Play Services Cronet
 * provider (HTTP/3). Cronet does not see the OkHttp cookie jar, DoH or interceptors, which
 * is why it is off by default. One engine is shared by every player and created lazily on a
 * background thread (engine creation loads the provider and can take hundreds of ms); until
 * it is ready, streams use OkHttp. On F-Droid builds the provider is absent and creation
 * fails quietly.
 *
 * Localhost/loopback streams (torrent server, extension HttpServer, local proxies) always
 * use OkHttp.
 */
@UnstableApi
object MediaDataSourceFactory {

    @Volatile
    private var cronetEngine: CronetEngine? = null

    @Volatile
    private var cronetInitStarted = false

    private val cronetExecutor: ExecutorService by lazy { Executors.newFixedThreadPool(4) }

    /** Starts creating the shared Cronet engine in the background when the user opted in. */
    fun warmUpCronet(context: Context) {
        if (!PrefManager.getVal<Boolean>(PrefName.UseCronet)) return
        synchronized(this) {
            if (cronetInitStarted) return
            cronetInitStarted = true
        }
        val appContext = context.applicationContext
        Thread({
            cronetEngine = try {
                val provider = CronetProvider.getAllProviders(appContext).firstOrNull { p ->
                    p.isEnabled && p.name != CronetProvider.PROVIDER_NAME_FALLBACK
                }
                if (provider == null) {
                    Logger.log("DataSource: no active GMS Cronet provider found")
                    null
                } else {
                    provider.createBuilder()
                        .enableQuic(true)
                        .enableHttp2(true)
                        .build()
                        .also { Logger.log("DataSource: Cronet engine ready (${provider.name})") }
                }
            } catch (e: Throwable) {
                // NoClassDefFoundError on F-Droid, or runtime failure on a GMS device.
                Logger.log("DataSource: GMS Cronet unavailable — ${e.message}")
                null
            }
        }, "cronet-init").start()
    }

    /**
     * Returns the [HttpDataSource.Factory] for a stream.
     *
     * @param headers      Request headers (already merged: defaults -> source -> video).
     * @param okHttpClient Client for the OkHttp path (the source's client, cache removed).
     * @param isLocalhost  Loopback stream: always OkHttp.
     */
    fun resolveHttpFactory(
        context: Context,
        headers: Map<String, String>,
        okHttpClient: OkHttpClient,
        isLocalhost: Boolean = false,
    ): HttpDataSource.Factory {
        if (!isLocalhost && PrefManager.getVal<Boolean>(PrefName.UseCronet)) {
            val engine = cronetEngine
            if (engine != null) {
                return try {
                    CronetDataSource.Factory(engine, cronetExecutor).apply {
                        setDefaultRequestProperties(StreamHeaders.without(headers, "User-Agent"))
                        StreamHeaders.get(headers, "User-Agent")?.let { setUserAgent(it) }
                    }.also { Logger.log("DataSource: using Cronet (HTTP/3 + HTTP/2)") }
                } catch (e: Throwable) {
                    Logger.log("DataSource: Cronet factory failed — ${e.message}")
                    buildOkHttpFactory(okHttpClient, headers)
                }
            }
            warmUpCronet(context)
        }
        return buildOkHttpFactory(okHttpClient, headers)
    }

    /**
     * Removes the extension's request rate limiters (Tachiyomi `rateLimit`/
     * `rateLimitHost` interceptors) from a client used for media: they are meant for API
     * and page requests, and would otherwise throttle HLS segment downloads to a few per
     * second and stall playback.
     */
    fun dropRateLimiters(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.apply {
        interceptors().removeAll { it.javaClass.name.contains("RateLimit", ignoreCase = true) }
        networkInterceptors().removeAll { it.javaClass.name.contains("RateLimit", ignoreCase = true) }
    }

    /**
     * Builds an [OkHttpDataSource.Factory] with the supplied [headers]. The User-Agent is set
     * once through setUserAgent (it is not also a default request property), so exactly one
     * User-Agent header goes out.
     */
    fun buildOkHttpFactory(
        okHttpClient: OkHttpClient,
        headers: Map<String, String>,
    ): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(StreamHeaders.without(headers, "User-Agent"))
            StreamHeaders.get(headers, "User-Agent")?.let { setUserAgent(it) }
        }
}
