package ani.dantotsu.media.anime.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Sends HLS/DASH playlists straight to the network and everything else (segments,
 * progressive files) through the disk cache.
 *
 * A cached playlist outlives its signed segment urls: reopening an episode replayed the
 * stale playlist and every segment failed with 403. Playlists are tiny, so not caching
 * them costs nothing.
 */
@UnstableApi
class PlaylistBypassDataSourceFactory(
    private val cached: DataSource.Factory,
    private val direct: DataSource.Factory,
    private val manifestUrl: String?,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = Source()

    private fun isPlaylist(uri: Uri): Boolean {
        val s = uri.toString()
        if (manifestUrl != null && s == manifestUrl) return true
        val path = (s.toHttpUrlOrNull()?.encodedPath ?: uri.path ?: "").lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")
    }

    private inner class Source : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = (if (isPlaylist(dataSpec.uri)) direct else cached).createDataSource()
            listeners.forEach { source.addTransferListener(it) }
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate?.read(buffer, offset, length) ?: throw IllegalStateException("read before open")

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
            }
        }
    }
}
