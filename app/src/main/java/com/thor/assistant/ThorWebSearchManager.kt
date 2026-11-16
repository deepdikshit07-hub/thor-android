package com.thor.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Small in-app web search client. It retrieves public DuckDuckGo HTML results
 * without an API key and returns only ordinary HTTPS links for the user to open.
 */
object ThorWebSearchManager {
    data class Result(
        val title: String,
        val url: String,
        val snippet: String
    )

    suspend fun search(query: String, limit: Int = 8): List<Result> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().take(240)
        require(cleanQuery.isNotBlank()) { "Enter a search query." }

        val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.name())
        // DDG's full HTML endpoint can occasionally return a challenge page to
        // mobile clients. Try its lighter HTML endpoint before reporting failure.
        val endpoints = listOf(
            "https://html.duckduckgo.com/html/?q=$encoded&kp=1",
            "https://lite.duckduckgo.com/lite/?q=$encoded&kp=1"
        )
        var lastFailure: Exception? = null
        for (endpoint in endpoints) {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 18_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }.orEmpty().take(120)
                    throw IllegalStateException(
                        "Search provider returned HTTP $status" +
                            if (detail.isBlank()) "." else ": $detail"
                    )
                }
                val html = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val results = parseResults(html, limit)
                if (results.isNotEmpty()) return@withContext results
                lastFailure = IllegalStateException("Search provider returned a page without readable results.")
            } catch (error: Exception) {
                lastFailure = error
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(
            lastFailure?.message ?: "Both web search endpoints are unavailable. Check your internet connection.",
            lastFailure
        )
    }

    /** Pure HTML parser so the result filtering can be verified in local unit tests. */
    internal fun parseResults(html: String, limit: Int = 8): List<Result> {
        val document = Jsoup.parse(html)
        val nodes = document.select("div.result, article[data-testid=result], tr")
        val found = LinkedHashMap<String, Result>()

        for (node in nodes) {
            // The lite endpoint uses a compact table row and result-link class;
            // the full endpoint uses div.result and result__a.
            val link = node.selectFirst(
                "a.result__a, a.result-link, a[data-testid=result-title-a], .result__title a"
            ) ?: continue
            val title = link.text().trim()
            val rawHref = link.absUrl("href").ifBlank { link.attr("href") }.trim()
            if (title.isBlank() || rawHref.isBlank()) continue

            val normalizedHref = if (rawHref.startsWith("//")) "https:" + rawHref else rawHref
            val redirectUri = runCatching { URI(normalizedHref) }.getOrNull() ?: continue
            val destination = queryParameters(redirectUri.rawQuery)["uddg"]?.takeIf { it.isNotBlank() }
                ?: normalizedHref
            val safeUri = runCatching { URI(destination) }.getOrNull() ?: continue
            val host = safeUri.host ?: continue
            if (!safeUri.scheme.equals("https", ignoreCase = true)) continue
            if (host.equals("duckduckgo.com", ignoreCase = true) ||
                host.endsWith(".duckduckgo.com", ignoreCase = true)) continue

            val snippet = node.selectFirst(
                ".result__snippet, [data-result=snippet], .result-snippet"
            )?.text()?.trim().orEmpty()
            found.putIfAbsent(destination, Result(title, destination, snippet))
            if (found.size >= limit.coerceIn(1, 10)) break
        }
        return found.values.toList()
    }

    private fun queryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { item ->
            val parts = item.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) }.getOrNull()
                ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) }.getOrNull()
                ?: return@mapNotNull null
            key to value
        }.toMap()
    }
}
