package com.vmodal.smartglass.voicesearch.core

import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    private val scope = SessionScope(1, "account", "vid_file", "shirts", "frames", 42, "vid_img")
    private val query = SearchQuery(1, 2, scope, "Find white shirt in my collection")

    @Test fun fullSpokenWordingAndRawTextAreRetained() {
        val raw = "  Find white shirt\n in my collection  "
        val query = query.copy(text = normalizeTranscript(raw), rawText = raw)
        assertEquals("Find white shirt in my collection", query.text)
        assertEquals(raw, query.rawText)
        assertEquals(42, query.scope.versionLancedb)
    }

    @Test fun scopeSnapshotRemainsFrozenAcrossCollectionChanges() {
        val changed = scope.copy(sessionGeneration = 2, groupName = "other", versionLancedb = 99)
        assertNotEquals(changed, query.scope)
        assertEquals("shirts", query.scope.groupName)
        assertEquals(42L, query.scope.versionLancedb)
    }

    @Test(expected = IllegalArgumentException::class) fun lowerRankCannotBePromoted() {
        RankedImageRef(query, "shirt.mp4", rank = 2)
    }

    @Test fun absentTimestampStaysAbsentAndLeadingZeroesSurvive() {
        assertNull(RankedImageRef(query, "shirt.mp4").timestamp)
        assertEquals("000032", RankedImageRef(query, "shirt.mp4", timestamp = "000032").timestamp)
    }

    @Test(expected = IllegalArgumentException::class) fun unsupportedImageCollectionIsNotInvented() {
        scope.copy(mode = "img_file", imageModality = "image")
    }

    @Test(expected = IllegalArgumentException::class) fun blankQueriesAreRejected() { query.copy(text = " ") }
    @Test(expected = IllegalArgumentException::class) fun unnormalizedQueriesAreRejected() { query.copy(text = " white  shirt ") }
    @Test(expected = IllegalArgumentException::class) fun oversizedSourceIsRejected() {
        EncodedImage(query, ByteArray(8 * 1024 * 1024 + 1), "image/png")
    }
    @Test(expected = IllegalArgumentException::class) fun unboundedPreviewConfigurationIsRejected() { ImageLimits(maxSide = 0) }
    @Test(expected = IllegalArgumentException::class) fun unsupportedPreviewFormatIsRejected() {
        PreparedImage(query, byteArrayOf(1), 1, 1, "image/gif")
    }
}
