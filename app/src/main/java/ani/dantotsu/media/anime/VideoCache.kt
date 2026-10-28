package ani.dantotsu.media.anime

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object VideoCache {
    private var simpleCache: SimpleCache? = null

    @Synchronized
    fun getInstance(context: Context): SimpleCache {
        simpleCache?.let { return it }
        val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
        return SimpleCache(
            File(context.cacheDir, "exoplayer"),
            LeastRecentlyUsedCacheEvictor(500L * 1024L * 1024L),
            databaseProvider
        ).also { simpleCache = it }
    }

    @Synchronized
    fun release() {
        simpleCache?.release()
        simpleCache = null
    }
}