package eu.kanade.tachiyomi.util.system

import dalvik.system.PathClassLoader
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Enumeration

/**
 * A parent-last class loader that will try in order:
 * - the system class loader
 * - the child class loader
 * - the parent class loader.
 */
class ChildFirstPathClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    private val systemClassLoader: ClassLoader? = getSystemClassLoader()

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        var c = findLoadedClass(name)

        if (c == null && systemClassLoader != null) {
            try {
                c = systemClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        if (c == null && name != null && isHostApi(name)) {
            // Host API first: an extension that bundles its own kotlin-stdlib/coroutines
            // (newer Miruro, AniNeko builds) would otherwise get a second copy of
            // kotlin.coroutines.Continuation, COROUTINE_SUSPENDED etc., and every suspend
            // call between it and the host fails (ClassCastException /
            // IncompatibleClassChangeError, swallowed into empty results).
            c = try {
                parent.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            }
        }

        if (c == null) {
            c = try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }

        if (resolve) {
            resolveClass(c)
        }

        return c
    }

    private fun isHostApi(name: String): Boolean =
        HOST_FIRST_PREFIXES.any { name.startsWith(it) }

    override fun getResource(name: String?): URL? {
        return systemClassLoader?.getResource(name)
            ?: findResource(name)
            ?: super.getResource(name)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        val systemUrls = systemClassLoader?.getResources(name)
        val localUrls = findResources(name)
        val parentUrls = parent?.getResources(name)
        val urls = buildList {
            while (systemUrls?.hasMoreElements() == true) {
               add(systemUrls.nextElement())
            }

            while (localUrls?.hasMoreElements() == true) {
               add(localUrls.nextElement())
            }

            while (parentUrls?.hasMoreElements() == true) {
               add(parentUrls.nextElement())
            }
        }

        return object : Enumeration<URL> {
            val iterator = urls.iterator()

            override fun hasMoreElements() = iterator.hasNext()
            override fun nextElement() = iterator.next()
        }
    }

    override fun getResourceAsStream(name: String?): InputStream? {
        return try {
            getResource(name)?.openStream()
        } catch (_: IOException) {
            return null
        }
    }

    private companion object {
        /**
         * Packages whose classes cross the host/extension boundary and must be the host's.
         * Everything else (keiyoushi.*, bundled NanoHTTPD, androidx.webkit boundary
         * interfaces, ...) stays extension-first.
         */
        val HOST_FIRST_PREFIXES = arrayOf(
            "kotlin.",
            "kotlinx.",
            "okhttp3.",
            "okio.",
            "org.jsoup.",
            "rx.",
            "uy.kohesive.injekt.",
            "app.cash.quickjs.",
            "androidx.preference.",
            "eu.kanade.tachiyomi.animesource.",
            "eu.kanade.tachiyomi.source.",
            "eu.kanade.tachiyomi.network.",
            "eu.kanade.tachiyomi.util.",
        )
    }
}
