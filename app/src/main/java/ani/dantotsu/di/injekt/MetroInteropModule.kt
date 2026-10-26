package ani.dantotsu.di.injekt

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.torrent.TorrentServerManager
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.anime.AndroidAnimeSourceManager
import eu.kanade.tachiyomi.source.manga.AndroidMangaSourceManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

@OptIn(ExperimentalSerializationApi::class)
@Inject
class MetroInteropModule(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
    private val sourcePreferences: SourcePreferences,
    private val basePreferences: BasePreferences,
    private val downloadsManager: Lazy<DownloadsManager>,
    private val networkHelper: NetworkHelper,
    private val javaScriptEngine: Lazy<JavaScriptEngine>,
    private val animeExtensionManager: Lazy<AnimeExtensionManager>,
    private val mangaExtensionManager: Lazy<MangaExtensionManager>,
    private val novelExtensionManager: Lazy<NovelExtensionManager>,
    private val torrentServerManager: Lazy<TorrentServerManager>,
    private val downloadAddonManager: Lazy<DownloadAddonManager>,
    private val animeSourceManager: Lazy<AnimeSourceManager>,
    private val mangaSourceManager: Lazy<MangaSourceManager>,
    private val mangaCache: Lazy<MangaCache>,
    private val databaseProvider: Lazy<StandaloneDatabaseProvider>,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) : InjektModule {

    // Heavy singletons are injected as Lazy and registered as factories, so building this
    // module in Application.onCreate no longer constructs all of them on the main thread.
    override fun InjektRegistrar.registerInjectables() {
        addSingleton(context)
        if (context is Application) {
            addSingleton<Application>(context)
        }

        addSingleton(preferenceStore)
        addSingleton(sourcePreferences)
        addSingleton(basePreferences)

        addSingletonFactory { downloadsManager.value }
        addSingleton(networkHelper)
        addSingleton(networkHelper.client)
        addSingletonFactory { javaScriptEngine.value }

        addSingletonFactory { animeExtensionManager.value }
        addSingletonFactory { mangaExtensionManager.value }
        addSingletonFactory { novelExtensionManager.value }
        addSingletonFactory { torrentServerManager.value }
        addSingletonFactory { downloadAddonManager.value }

        addSingletonFactory { animeSourceManager.value }
        addSingletonFactory { mangaSourceManager.value }

        addSingleton(json)
        addSingleton(protoBuf)
        addSingletonFactory { databaseProvider.value }
        addSingletonFactory { mangaCache.value }
    }
}
