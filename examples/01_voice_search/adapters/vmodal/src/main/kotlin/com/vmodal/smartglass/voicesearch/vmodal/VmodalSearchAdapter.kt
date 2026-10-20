package com.vmodal.smartglass.voicesearch.vmodal

import com.vmodal.smartglass.voicesearch.core.*

/** Search only backend rank one; a broken first hit never promotes a later row. */
class VmodalSearchAdapter(private val gateway: VmodalSessionGateway) : SearchPort {
    override suspend fun topOne(query: SearchQuery): SearchOutcome = gateway.use(query.scope, Stage.Search, query) { client ->
        val scope = query.scope
        if (scope.versionLancedb > Int.MAX_VALUE) {
            throw failure(Stage.Search, FailureCode.NotIndexed, false, "Refresh the collection index.", query)
        }
        val response = client.coroutines().searches.searchVideo(
            queryText = query.text,
            mode = scope.mode,
            groupName = scope.groupName,
            streamName = scope.streamName,
            offset = 0,
            limit = 1,
            versionLancedb = scope.versionLancedb.toInt(),
        )
        if (response.data.isEmpty()) return@use SearchOutcome.Empty
        val hit = imageCoordinates(response.data.first(), scope.streamName)
            ?: return@use SearchOutcome.TopHitUnresolvable
        SearchOutcome.Match(RankedImageRef(query, hit.filename, hit.stream, hit.timestamp, hit.caption, hit.score))
    }
}
