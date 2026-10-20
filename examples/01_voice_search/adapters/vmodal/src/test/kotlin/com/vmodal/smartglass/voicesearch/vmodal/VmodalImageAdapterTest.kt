package com.vmodal.smartglass.voicesearch.vmodal

import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VmodalImageAdapterTest {
    @Test fun lookupUsesExactImageCoordinatesAndNoInventedVersion() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        val ref = RankedImageRef(query, "white-shirt.mp4", "indexed-stream", "0000000002000")
        val encoded = gateway.images.load(ref)
        val records = transport.body("/get_url_bulk")["records"] as List<*>
        assertEquals(listOf(mapOf("mode" to "vid_file", "group_name" to "videos", "modality" to "vid_img",
            "stream_name" to "indexed-stream", "filename" to "white-shirt.mp4", "ts_unix_13digits" to "0000000002000")), records)
        assertEquals(query, encoded.query)
        assertEquals("image/jpeg", encoded.mimeType)
        assertArrayEquals(transport.image, encoded.bytes)
        assertEquals(1, transport.downloads)
    }

    @Test fun strictBulkJoinRejectsMissingInvalidFractionalAndDuplicateZero() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        val ref = RankedImageRef(query, "top.mp4")
        listOf(
            """{"url_pre_signed":"https://images.test/x"}""",
            """{"input_index":1,"url_pre_signed":"https://images.test/x"}""",
            """{"input_index":0.1,"url_pre_signed":"https://images.test/x"}""",
            """{"input_index":-0.1,"url_pre_signed":"https://images.test/x"}""",
            """{"input_index":0,"url_pre_signed":"https://images.test/x"},{"input_index":0,"url_pre_signed":"https://images.test/y"}""",
            """{"input_index":0,"found":false,"url_pre_signed":"https://images.test/x"}""",
            """{"input_index":0,"url_pre_signed":" "}""",
        ).forEach { row ->
            transport.bulkBody = """{"records":[$row]}"""
            expectFailure(FailureCode.TopImageUnavailable) { gateway.images.load(ref) }
        }
        assertEquals(0, transport.downloads)
        assertEquals(null, intInputIndex(Double.NaN))
        assertEquals(null, intInputIndex(4294967296L))
        assertEquals(0, intInputIndex("0"))
    }

    @Test fun expiredUrlResolvesSameHitExactlyOnceAndNeverSearchesAgain() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        transport.imageFailures = 1
        gateway.images.load(RankedImageRef(query, "top.mp4", timestamp = "0000000002000"))
        val lookups = transport.requests.filter { it.path.endsWith("/get_url_bulk") }
        assertEquals(2, lookups.size)
        assertEquals(lookups.first().jsonBody, lookups.last().jsonBody)
        assertEquals(2, transport.downloads)
        assertTrue(transport.requests.none { it.path.endsWith("/search") })
    }

    @Test fun persistentDownloadFailureIsBoundedToTwoAndDoesNotTryAnotherRank() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        transport.imageFailures = 10
        expectFailure(FailureCode.TopImageUnavailable) { gateway.images.load(RankedImageRef(query, "top.mp4")) }
        assertEquals(2, transport.downloads)
        assertEquals(2, transport.requests.count { it.path.endsWith("/get_url_bulk") })
        assertTrue(transport.requests.none { it.path.endsWith("/search") })
    }

    @Test fun nonExpiryFailuresDoNotRefreshAndInvalidBytesFailExplicitly() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        val ref = RankedImageRef(query, "top.mp4")
        transport.imageFailures = 1
        transport.imageStatus = 500
        expectFailure(FailureCode.TopImageUnavailable) { gateway.images.load(ref) }
        assertEquals(1, transport.downloads)
        transport.image = "not an image".toByteArray()
        expectFailure(FailureCode.TopImageUnavailable) { gateway.images.load(ref) }
        transport.image = byteArrayOf()
        expectFailure(FailureCode.TopImageUnavailable) { gateway.images.load(ref) }
    }

    @Test fun encodedBytesCannotExceedConfiguredBound() = runTest {
        val transport = TestTransport()
        val gateway = gateway(transport, maxBytes = 3)
        val scope = gateway.connect("test-api-key", 1, "videos", "default")
        expectFailure(FailureCode.ImageTooLarge) {
            gateway.images.load(RankedImageRef(SearchQuery(1, 1, scope, "white shirt"), "top.mp4"))
        }
    }

    @Test fun imageCancellationReachesTheDownloadTransportWithoutRefresh() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        transport.holdImage = true
        val job = launch { gateway.images.load(RankedImageRef(query, "top.mp4")) }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, transport.cancelled)
        assertEquals(1, transport.requests.count { it.path.endsWith("/get_url_bulk") })
    }
}
