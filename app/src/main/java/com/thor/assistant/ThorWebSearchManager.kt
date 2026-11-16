package com.thor.assistant

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
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
        val connection = (URL("https://html.duckduckgo.com/html/?q=" + encoded)
            .openConnection() as HttpURLConnection).apply {
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
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    .orEmpty().take(160)
                error("Search provider returned HTTP " + status +
                    if (detail.isBlank()) "." else ": " + detail)
            }

            val html = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val document = Jsoup.parse(html)
            val nodes = document.select("div.result, article[data-testid=result]")
            val found = LinkedHashMap<String, Result>()

            for (node in nodes) {
                val link = node.selectFirst("a.result__a, a[data-testid=result-title-a], .result__title a")
                    ?: continue
                val title = link.text().trim()
                val rawHref = link.absUrl("href").ifBlank { link.attr("href") }.trim()
                if (title.isBlank() || rawHref.isBlank()) continue

                val parsed = Uri.parse(if (rawHref.startsWith("//")) "https:" + rawHref else rawHref)
                val destination = parsed.getQueryParameter("uddg")?.takeIf { it.isNotBlank() } ?: rawHref
                val safeUri = Uri.parse(destination)
                if (safeUri.scheme != "https" || safeUri.host.isNullOrBlank()) continue
                if (safeUri.host!!.contains("duckduckgo.com", ignoreCase = true)) continue

                val snippet = node.selectFirst(".result__snippet, [data-result=snippet], .result-snippet")
                    ?.text()?.trim().orEmpty()
                found.putIfAbsent(destination, Result(title, destination, snippet))
                if (found.size >= limit.coerceIn(1, 10)) break
            }
            found.values.toList()
        } finally {
            connection.disconnect()
        }
    }
}
