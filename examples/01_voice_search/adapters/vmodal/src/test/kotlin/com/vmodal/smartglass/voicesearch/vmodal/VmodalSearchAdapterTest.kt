package com.vmodal.smartglass.voicesearch.vmodal

import com.vmodal.smartglass.voicesearch.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VmodalSearchAdapterTest {
    @Test fun sdkUsesFrozenIdentityScopeVersionAndRankOne() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        val result = gateway.search.topOne(query) as SearchOutcome.Match
        val body = transport.body("/search")
        assertEquals("Find white shirt in my collection", body["query_text"])
        assertEquals("vid_file", body["mode"])
        assertEquals("videos", body["group_name"])
        assertEquals("default", body["stream_name"])
        assertEquals(0, body["offset"])
        assertEquals(1, body["limit"])
        assertEquals(7, body["version_lancedb"])
        // The SDK supplies its fixed source selector; the app adds no custom route/pipeline knobs.
        assertEquals(listOf("image"), body["search_sources"])
        assertFalse(body.containsKey("topK"))
        assertFalse(body.containsKey("userid"))
        assertEquals("account-a", query.scope.accountRef)
        assertEquals("rank1.mp4", result.ref.filename)
        assertEquals("0000000002000", result.ref.timestamp)
        assertEquals(1, result.ref.rank)
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer test-api-key" })
    }

    @Test fun emptyAndMalformedFirstHitStayDistinctAndNeverPromote() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        transport.searchBody = """{"data":[],"cnt_total":999}"""
        assertEquals(SearchOutcome.Empty, gateway.search.topOne(query))
        listOf("null", "17", "{}", "{\"title\":\"white shirt\"}").forEach { row ->
            transport.searchBody = """{"data":[$row,{"filename":"rank2.mp4"}]}"""
            assertEquals(SearchOutcome.TopHitUnresolvable, gateway.search.topOne(query))
        }
        assertEquals(0, transport.downloads)
    }

    @Test fun aliasesItemIdTimestampAndScoreFollowExistingFixtures() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        listOf("filename", "filename_sanitized", "video_filename", "video", "source_path", "path").forEach { name ->
            transport.searchBody = """{"data":[{"$name":"folder/nested/video.mp4","ts_unix":"1700000000"}]}"""
            val ref = (gateway.search.topOne(query) as SearchOutcome.Match).ref
            assertEquals("video.mp4", ref.filename)
            assertEquals("1700000000000", ref.timestamp)
        }
        transport.searchBody = """{"data":[{"stream":"astream","item_id":"astream-video_10frames-0000000002000","ts_unix":"0000000002000","score":0.42},{"filename":"second.mp4"}]}"""
        val ref = (gateway.search.topOne(query) as SearchOutcome.Match).ref
        assertEquals("video_10frames", ref.filename)
        assertEquals("astream", ref.streamName)
        assertEquals("0000000002000", ref.timestamp)
        assertEquals(0.42, ref.score)
        assertEquals(null, imageCoordinates(mapOf("item_id" to "arbitrary", "stream" to "s", "ts_unix" to "2"), "s"))
        assertEquals(null, imageCoordinates(mapOf("filename" to "clip.mp4"), "s")!!.timestamp)
        assertEquals("0000000002000", strTimestamp13("time=2,000ms"))
        assertEquals("1700000000123", strTimestamp13("170000000012345"))
    }

    @Test fun advertisedIndexAndVisibleCollectionAreRequired() = runTest {
        val transport = TestTransport()
        transport.groupsBody = """{"data":[{"mode":"vid_file","group_name":"videos","lancedb_versions":[]}]}"""
        val gateway = gateway(transport)
        assertEquals(listOf(CollectionChoice("videos", null)), gateway.authenticate("test-api-key", 1))
        expectFailure(FailureCode.NotIndexed) { gateway.selectCollection("videos", "default") }
        expectFailure(FailureCode.AuthRequired) { gateway.selectCollection("hidden", "default") }
        assertTrue(transport.requests.none { it.path.endsWith("/search") })
    }

    @Test fun typedErrorsAreSafeAndCancellationReachesSdkTransport() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        transport.searchStatus = 401
        expectFailure(FailureCode.AuthRequired) { gateway.search.topOne(query) }
        transport.searchStatus = 503
        expectFailure(FailureCode.SearchFailed) { gateway.search.topOne(query) }
        transport.holdSearch = true
        val job = launch { gateway.search.topOne(query) }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, transport.cancelled)
    }

    @Test fun closedOrChangedScopeCannotSearchOrDeliver() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        gateway.selectCollection("videos", "other")
        val changed = async { runCatching { gateway.search.topOne(query) }.exceptionOrNull() }
        assertTrue(changed.await() is kotlinx.coroutines.CancellationException)
        gateway.close()
        expectFailure(FailureCode.AuthRequired) { gateway.search.topOne(query) }
        assertTrue(transport.requests.none { it.path.endsWith("/search") })
    }

    @Test fun lateCompletionAfterScopeChangeIsCancelled() = runTest {
        val transport = TestTransport()
        val (gateway, query) = prepared(transport)
        transport.holdSearch = true
        val pending = async { runCatching { gateway.search.topOne(query) }.exceptionOrNull() }
        runCurrent()
        gateway.selectCollection("videos", "other")
        requireNotNull(transport.pending).invoke()
        assertTrue(pending.await() is kotlinx.coroutines.CancellationException)
    }
}
