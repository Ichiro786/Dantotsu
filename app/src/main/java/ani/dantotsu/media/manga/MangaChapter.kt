package ani.dantotsu.media.manga

import ani.dantotsu.parsers.MangaChapter
import ani.dantotsu.parsers.MangaImage
import eu.kanade.tachiyomi.source.model.SChapter
import java.io.Serializable

data class MangaChapter(
    val number: String,
    var link: String,
    var title: String? = null,
    var description: String? = null,
    var sChapter: SChapter,
    val scanlator: String? = null,
    val date: Long? = null,
    var progress: String? = ""
) : Serializable {
    constructor(chapter: MangaChapter) : this(
        chapter.number,
        chapter.link,
        chapter.title,
        chapter.description,
        chapter.sChapter,
        chapter.scanlator,
        chapter.date
    )

    // Read by the reader's loader, render and UI threads while a page list loads on IO: swapped as
    // whole immutable lists (a shared mutable list threw ConcurrentModificationException).
    @Volatile
    private var images: List<MangaImage> = emptyList()

    @Volatile
    private var dualPages: List<Pair<MangaImage, MangaImage?>> = emptyList()

    fun images(): List<MangaImage> = images

    /** Sets the page list once; a second list is ignored (see [replaceImages]). */
    @Synchronized
    fun addImages(image: List<MangaImage>) {
        if (images.isNotEmpty()) return
        setImages(image)
    }

    /** Replaces the page list, e.g. after the source returned a different page count. */
    @Synchronized
    fun replaceImages(image: List<MangaImage>) {
        setImages(image)
    }

    private fun setImages(image: List<MangaImage>) {
        val pages = ArrayList(image)
        val pairs = ArrayList<Pair<MangaImage, MangaImage?>>((pages.size + 1) / 2)
        var i = 0
        while (i < pages.size) {
            pairs.add(pages[i] to pages.getOrNull(i + 1))
            i += 2
        }
        dualPages = pairs
        images = pages
    }

    fun dualPages(): List<Pair<MangaImage, MangaImage?>> = dualPages

    fun uniqueNumber(): String = "${number}-${scanlator ?: "Unknown"}"

}
