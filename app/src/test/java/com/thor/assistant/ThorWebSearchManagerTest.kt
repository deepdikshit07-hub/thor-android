package com.thor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorWebSearchManagerTest {
    @Test
    fun unwrapsDuckDuckGoRedirectAndKeepsSnippet() {
        val html = """
            <div class="result">
              <h2 class="result__title">
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fnews%3Fid%3D7">
                  Example news
                </a>
              </h2>
              <a class="result__snippet">A short result summary.</a>
            </div>
        """.trimIndent()

        val results = ThorWebSearchManager.parseResults(html)

        assertEquals(1, results.size)
        assertEquals("Example news", results[0].title)
        assertEquals("https://example.com/news?id=7", results[0].url)
        assertEquals("A short result summary.", results[0].snippet)
    }

    @Test
    fun rejectsUnsafeAndSearchEngineRedirectLinks() {
        val html = """
            <div class="result">
              <a class="result__a" href="javascript:alert(1)">Unsafe</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://duckduckgo.com/about">Search engine page</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://example.org/safe">Safe result</a>
            </div>
        """.trimIndent()

        val results = ThorWebSearchManager.parseResults(html)

        assertEquals(1, results.size)
        assertEquals("https://example.org/safe", results.single().url)
    }

    @Test
    fun honorsRequestedResultLimit() {
        val html = (1..4).joinToString("") { index ->
            """<div class="result"><a class="result__a" href="https://example.com/$index">Result $index</a></div>"""
        }

        val results = ThorWebSearchManager.parseResults(html, limit = 2)

        assertEquals(2, results.size)
        assertTrue(results.all { it.url.startsWith("https://example.com/") })
    }
}
