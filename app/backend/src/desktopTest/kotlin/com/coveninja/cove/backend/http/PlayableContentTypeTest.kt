package com.coveninja.cove.backend.http

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayableContentTypeTest {
    @Test
    fun aWebPageIsNotAPlayableStream() {
        // An unresolved provider link answers 200 with an HTML portal, so status alone judged it
        // alive and the picker offered it; mpv then found no container and the viewer was told
        // the file format was unrecognised.
        assertFalse(looksLikePlayableContentType("text/html"))
        assertFalse(looksLikePlayableContentType("text/html; charset=UTF-8"))
        assertFalse(looksLikePlayableContentType("Text/HTML"))
        assertFalse(looksLikePlayableContentType("application/xhtml+xml"))
    }

    @Test
    fun anythingElseIsGivenTheBenefitOfTheDoubt() {
        // A denylist, not an allowlist: hosts serve video under every kind of vague or wrong
        // content type, and refusing what we do not recognise would discard working sources.
        assertTrue(looksLikePlayableContentType("video/mp4"))
        assertTrue(looksLikePlayableContentType("application/octet-stream"))
        assertTrue(looksLikePlayableContentType("application/vnd.apple.mpegurl"))
        assertTrue(looksLikePlayableContentType("binary/octet-stream"))
        assertTrue(looksLikePlayableContentType(null))
        assertTrue(looksLikePlayableContentType(""))
    }
}
