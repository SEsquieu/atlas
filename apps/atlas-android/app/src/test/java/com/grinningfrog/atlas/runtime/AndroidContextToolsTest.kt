package com.grinningfrog.atlas.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidContextToolsTest {
    @Test fun `search results are bounded and malformed URLs are dropped`() {
        val body = """{"web":{"results":[
            {"title":"One","url":"https://example.com/1","description":"First"},
            {"title":"Bad","url":"javascript:alert(1)","description":"No"},
            {"title":"Two","url":"http://example.com/2","description":"Second"},
            {"title":"Three","url":"https://example.com/3","description":"Third"}
        ]}}"""

        val results = parseBraveSearchResults(body, 3)

        assertEquals(2, results.length())
        assertEquals("One", results.getJSONObject(0).getString("title"))
        assertEquals("Two", results.getJSONObject(1).getString("title"))
    }
}
