package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import ani.dantotsu.snackString
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

    // Lazy: CookieManager.getInstance() loads WebView/Chromium (50-300 ms). Doing it when the
    // first request needs cookies keeps it out of Application.onCreate.
    val manager: CookieManager? by lazy {
        try {
            CookieManager.getInstance()
        } catch (e: Exception) {
            snackString("Webview is outdated, please update your webview")
            null
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()

        cookies.forEach { manager?.setCookie(urlString, it.toString()) }
        manager?.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    fun get(url: HttpUrl): List<Cookie> {
        val cookies = manager?.getCookie(url.toString())

        return if (!cookies.isNullOrEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val urlString = url.toString()
        val manager = manager ?: return 0
        val cookies = manager.getCookie(urlString) ?: return 0

        fun List<String>.filterNames(): List<String> {
            return if (cookieNames != null) {
                this.filter { it in cookieNames }
            } else {
                this
            }
        }

        val count = cookies.split(";")
            .map { it.substringBefore("=") }
            .filterNames()
            .onEach { manager.setCookie(urlString, "$it=;Max-Age=$maxAge") }
            .count()
        manager.flush()
        return count
    }

    fun removeAll() {
        val manager = manager ?: return
        manager.removeAllCookies {
            manager.flush()
        }
    }

    fun flush() {
        manager?.flush()
    }
}
