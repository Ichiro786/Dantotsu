package ani.dantotsu.media.anime.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Strips a fake image header some HLS hosts put in front of MPEG-TS segments.
 *
 * Several anime CDNs serve TS segments as .jpg/.png/.html/.js with a matching
 * Content-Type, and some also prepend a real image header (a tiny PNG/JPEG/GIF) before
 * the first TS packet. mpv/ffmpeg resync on the 0x47 sync byte (that is what extensions'
 * `force_mpegts` is for); ExoPlayer's TS sniffer only looks 187 bytes in, so a longer
 * header makes the segment unparseable.
 *
 * Only a read from position 0 whose first bytes are an image signature, followed by three
 * TS sync bytes 188 apart, is shifted; everything else (playlists, keys, real TS, fMP4,
 * byte-range reads, encrypted segments) passes through unchanged after a small peek.
 */
@UnstableApi
class DisguisedSegmentDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = Source(upstream.createDataSource())

    private class Source(private val delegate: DataSource) : DataSource {
        private var peek: ByteArray? = null
        private var peekPos = 0
        private var peekEnd = 0

        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            peek = null
            peekPos = 0
            peekEnd = 0
            val length = delegate.open(dataSpec)
            if (dataSpec.position != 0L || isPlaylist(dataSpec.uri)) return length

            val buf = ByteArray(PEEK_SIZE)
            var filled = 0
            while (filled < PEEK_SIZE) {
                val n = delegate.read(buf, filled, PEEK_SIZE - filled)
                if (n == C.RESULT_END_OF_INPUT) break
                filled += n
            }
            val skip = junkLength(buf, filled)
            peek = buf
            peekPos = skip
            peekEnd = filled
            return if (skip > 0 && length != C.LENGTH_UNSET.toLong()) length - skip else length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val p = peek
            if (p != null && peekPos < peekEnd) {
                val n = minOf(length, peekEnd - peekPos)
                System.arraycopy(p, peekPos, buffer, offset, n)
                peekPos += n
                return n
            }
            return delegate.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() {
            peek = null
            delegate.close()
        }
    }

    companion object {
        private const val PEEK_SIZE = 4096
        private const val TS_PACKET = 188
        private const val SYNC = 0x47.toByte()

        private fun isPlaylist(uri: Uri): Boolean {
            val path = (uri.path ?: "").lowercase()
            return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")
        }

        private fun startsWithImage(b: ByteArray, n: Int): Boolean {
            if (n < 4) return false
            fun u(i: Int) = b[i].toInt() and 0xFF
            return (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) || // PNG
                (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) || // JPEG
                (u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x38) || // GIF8
                (u(0) == 0x42 && u(1) == 0x4D) || // BMP
                (u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46) // RIFF (WebP)
        }

        /** Bytes before the first TS packet when [b] is an image-wrapped TS segment, else 0. */
        internal fun junkLength(b: ByteArray, n: Int): Int {
            if (!startsWithImage(b, n)) return 0
            // "GIF8" begins with 0x47: a real TS packet can look like it.
            if (n > 2 * TS_PACKET && b[0] == SYNC && b[TS_PACKET] == SYNC && b[2 * TS_PACKET] == SYNC) return 0
            var i = 1
            while (i + 2 * TS_PACKET < n) {
                if (b[i] == SYNC && b[i + TS_PACKET] == SYNC && b[i + 2 * TS_PACKET] == SYNC) return i
                i++
            }
            return 0
        }
    }
}
