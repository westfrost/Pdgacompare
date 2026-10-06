package dk.pdgacompare.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

internal fun OkHttpClient.getText(url: String): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PdgaCompare/1.0")
        .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
        .build()
    newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
        return response.body?.string() ?: throw IOException("Empty response from $url")
    }
}

/** Fetches rated round results of PDGA events. */
class PdgaClient internal constructor(private val http: OkHttpClient) {
    constructor() : this(defaultHttpClient())

    suspend fun fetchEvent(eventId: Long): PdgaEvent = withContext(Dispatchers.IO) {
        val fromPage = runCatching { PdgaParser.parseEventHtml(http.getText("$BASE/tour/event/$eventId"), eventId) }
        val page = fromPage.getOrNull()
        if (page != null && page.rounds.isNotEmpty() && page.rounds.all { it.layout != null }) return@withContext page

        // The results page lacks results or layout details: PDGA Live has both, when the event used it.
        val live = runCatching { fetchLive(eventId) }.getOrNull()
        when {
            live != null && live.rounds.isNotEmpty() -> live.copy(
                name = page?.name ?: live.name,
                startDate = page?.startDate?.ifEmpty { null } ?: live.startDate,
            )
            page != null && page.rounds.isNotEmpty() -> page
            fromPage.isFailure -> throw IOException(
                "Could not load PDGA event $eventId: ${fromPage.exceptionOrNull()?.message}",
                fromPage.exceptionOrNull(),
            )
            else -> throw IOException("PDGA event $eventId has no rated rounds yet")
        }
    }

    /**
     * Searches PDGA events in [countryCode] between the dates, optionally by event name.
     * Reads result pages until a page brings nothing new or [maxPages] is reached.
     */
    suspend fun searchEvents(
        countryCode: String,
        from: LocalDate,
        to: LocalDate,
        name: String? = null,
        maxPages: Int = 10,
    ): List<PdgaEventSummary> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<Long, PdgaEventSummary>()
        for (page in 0 until maxPages) {
            val url = "$BASE/tour/search".toHttpUrl().newBuilder()
                .addQueryParameter("OfficialName", name.orEmpty())
                .addQueryParameter("date_filter[min][date]", from.toString())
                .addQueryParameter("date_filter[max][date]", to.toString())
                .addQueryParameter("Country[]", countryCode.trim().uppercase())
                .apply { if (page > 0) addQueryParameter("page", page.toString()) }
                .build()
            val results = PdgaParser.parseEventSearch(http.getText(url.toString()))
            val before = found.size
            results.forEach { found.putIfAbsent(it.id, it) }
            if (found.size == before) break
        }
        found.values.toList()
    }

    private suspend fun fetchLive(eventId: Long): PdgaEvent = coroutineScope {
        val (suffix, eventJson) = LIVE_SUFFIXES.firstNotNullOf { suffix ->
            runCatching { suffix to http.getText("$LIVE/live_results_fetch_event$suffix?TournID=$eventId") }.getOrNull()
        }
        val event = PdgaParser.parseLiveEvent(eventJson)
        val roundNumbers = 1..(event.rounds ?: 4).coerceIn(1, 12)
        val limit = Semaphore(4)
        val rounds = event.divisions.flatMap { division ->
            roundNumbers.map { round ->
                async<Pair<Int, List<Pair<PdgaLayoutInfo?, RatedResult>>>> {
                    limit.withPermit {
                        val url = "$LIVE/live_results_fetch_round$suffix?TournID=$eventId&Division=$division&Round=$round"
                        round to runCatching { PdgaParser.parseLiveRound(http.getText(url)) }.getOrDefault(emptyList())
                    }
                }
            }
        }.awaitAll()
        PdgaParser.buildLiveEvent(eventId, event, rounds)
    }

    companion object {
        private const val BASE = "https://www.pdga.com"
        private const val LIVE = "$BASE/apps/tournament/live-api"
        private val LIVE_SUFFIXES = listOf("", ".php")

        fun eventUrl(eventId: Long) = "$BASE/tour/event/$eventId"
    }
}
