package dk.pdgacompare

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.pdgacompare.core.AppState
import dk.pdgacompare.core.Candidate
import dk.pdgacompare.core.CourseMatch
import dk.pdgacompare.core.Layout
import dk.pdgacompare.core.Layouts
import dk.pdgacompare.core.MetrixClient
import dk.pdgacompare.core.MetrixCourse
import dk.pdgacompare.core.MetrixCourseRef
import dk.pdgacompare.core.PdgaClient
import dk.pdgacompare.core.PdgaEvent
import dk.pdgacompare.core.PdgaLayoutInfo
import dk.pdgacompare.core.PdgaParser
import dk.pdgacompare.core.PdgaRoundFinder
import dk.pdgacompare.core.PlayedRound
import dk.pdgacompare.core.PlayerCard
import dk.pdgacompare.core.RatingEstimator
import dk.pdgacompare.core.StateCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object NewLayout : Screen
    data class LayoutDetail(val layoutId: String) : Screen
    data class NewRound(val layoutId: String? = null) : Screen
    data class Scoring(val roundId: String) : Screen
    data class Summary(val roundId: String) : Screen
}

/** PDGA layouts of which the user has to pick the one that is the same as the layout. */
data class PendingChoice(
    val layoutId: String,
    val text: String,
    val candidates: List<Candidate>,
    /** The best guess, shown first and marked. */
    val suggested: Candidate? = null,
    /** Choosing replaces the layout's current PDGA layout and rounds (switching e.g. from white to yellow tees). */
    val replace: Boolean = false,
)

private const val SEARCH_YEARS = 4L
private const val MAX_EVENTS_TO_CHECK = 30

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val file = File(app.filesDir, "state.json")
    // Not viewModelScope: saves must complete even while the ViewModel is being cleared.
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pdga = PdgaClient()
    private val metrix = MetrixClient()

    var state by mutableStateOf(load())
        private set

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** Result of "fetch PDGA event" on the new-layout screen. */
    var newLayoutEvent by mutableStateOf<PdgaEvent?>(null)
        private set

    /** Result of "fetch Metrix course" on the new-layout screen. */
    var newLayoutMetrix by mutableStateOf<MetrixCourse?>(null)
        private set

    /** Result of the course search on the new-layout screen. */
    var courseResults by mutableStateOf<List<MetrixCourseRef>?>(null)
        private set

    /** What a long-running fetch is doing right now. */
    var progress by mutableStateOf<String?>(null)
        private set

    val pendingChoices = mutableStateListOf<PendingChoice>()

    /** Fetched PDGA events, so searching again does not re-download them. */
    private val eventCache = mutableMapOf<Long, PdgaEvent>()

    // ---- Navigation ----------------------------------------------------------------------------

    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    /** Replaces the current screen, so back skips it. */
    fun replace(screen: Screen) {
        backStack[backStack.lastIndex] = screen
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // ---- Persistence ---------------------------------------------------------------------------

    private fun load(): AppState =
        runCatching { StateCodec.decode(file.readText()) }.getOrDefault(AppState())

    private fun update(change: (AppState) -> AppState) {
        state = change(state)
        val snapshot = state
        saver.launch {
            val tmp = File(file.parentFile, "state.json.tmp")
            tmp.writeText(StateCodec.encode(snapshot))
            tmp.renameTo(file)
        }
    }

    private fun updateLayout(id: String, change: (Layout) -> Layout) =
        update { s -> s.copy(layouts = s.layouts.map { if (it.id == id) change(it) else it }) }

    private fun updateRound(id: String, change: (PlayedRound) -> PlayedRound) =
        update { s -> s.copy(rounds = s.rounds.map { if (it.id == id) change(it) else it }) }

    fun layout(id: String?): Layout? = state.layouts.firstOrNull { it.id == id }
    fun round(id: String): PlayedRound? = state.rounds.firstOrNull { it.id == id }

    private fun newId() = UUID.randomUUID().toString()

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                busy = false
                progress = null
            }
        }
    }

    // ---- Layouts -------------------------------------------------------------------------------

    fun setMetrixCode(code: String) = update { it.copy(metrixCode = code) }

    fun setCountryCode(code: String) = update { it.copy(countryCode = code.trim().uppercase().take(2)) }

    fun searchCourses(name: String) = work {
        if (name.isBlank()) error("Enter a course name")
        courseResults = null
        val results = metrix.searchCourses(name, state.countryCode)
        // Layouts first; a course with layouts is only useful through one of them.
        courseResults = results.filter { it.isLayout }.ifEmpty { results }
        if (results.isEmpty()) message = "No courses named \"$name\" in ${state.countryCode} on Disc Golf Metrix"
    }

    /** Creates a layout from a course search result, then looks for PDGA rounds on it. */
    fun createLayoutFromSearch(ref: MetrixCourseRef) = work {
        progress = "Loading holes from Disc Golf Metrix…"
        val course = runCatching { metrix.fetchCourseHoles(ref.id, state.metrixCode) }.getOrNull()
        val layout = Layouts.fromMetrixSearch(newId(), ref, course, state.countryCode)
        addLayout(layout)
        findRounds(layout.id)
    }

    fun fetchPdgaForNewLayout(input: String) = work {
        val id = PdgaParser.parseEventIds(input).firstOrNull() ?: error("Enter a PDGA event id or link")
        newLayoutEvent = null
        newLayoutEvent = fetchEvent(id)
    }

    fun fetchMetrixForNewLayout(input: String) = work {
        newLayoutMetrix = null
        newLayoutMetrix = metrix.fetch(input, state.metrixCode)
    }

    fun createLayoutFromPdga(event: PdgaEvent, layout: PdgaLayoutInfo?) =
        addLayout(Layouts.fromPdga(newId(), event, layout))

    fun createLayoutFromMetrix(course: MetrixCourse) = addLayout(Layouts.fromMetrix(newId(), course))

    fun createManualLayout(name: String, holes: Int) = addLayout(Layouts.manual(newId(), name, holes))

    private fun addLayout(layout: Layout) {
        update { it.copy(layouts = it.layouts + layout) }
        newLayoutEvent = null
        newLayoutMetrix = null
        courseResults = null
        replace(Screen.LayoutDetail(layout.id))
    }

    fun renameLayout(id: String, name: String) = updateLayout(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun changePar(id: String, holeIndex: Int, delta: Int) = updateLayout(id) { layout ->
        layout.copy(holes = layout.holes.mapIndexed { i, h -> if (i == holeIndex) h.copy(par = (h.par + delta).coerceIn(2, 7)) else h })
    }

    fun deleteLayout(id: String) {
        update { s -> s.copy(layouts = s.layouts.filter { it.id != id }) }
        back()
    }

    private suspend fun fetchEvent(id: Long): PdgaEvent =
        eventCache[id] ?: pdga.fetchEvent(id).also { eventCache[id] = it }

    /** Searches PDGA for events on the layout's course and adds their rounds. */
    fun findPdgaRounds(layoutId: String) = work { findRounds(layoutId) }

    /** Lets the user pick another PDGA layout on the course, replacing the current one. */
    fun changePdgaLayout(layoutId: String) = work { findRounds(layoutId, choose = true) }

    /**
     * Finds PDGA rounds on the layout's course. The layout's already linked PDGA layout, or the only one
     * on the course, is used directly; otherwise (or with [choose]) the user picks among them.
     */
    private suspend fun findRounds(layoutId: String, choose: Boolean = false) {
        val start = layout(layoutId) ?: return
        val course = start.courseName.ifBlank { start.name }
        val country = start.countryCode.ifBlank { state.countryCode }
        val terms = CourseMatch.eventSearchTerms(course).ifEmpty { error("\"$course\" is too generic to search for") }
        val to = LocalDate.now()
        val from = to.minusYears(SEARCH_YEARS)

        progress = "Searching PDGA events in $country…"
        val byName = terms.map { term -> runCatching { pdga.searchEvents(country, from, to, term) } }
        // Events are not always named after the course: also look at events in the course's town.
        val byTown = runCatching {
            if (start.city.isBlank()) emptyList()
            else pdga.searchEvents(country, from, to, maxPages = 8).filter { CourseMatch.locationMatches(it.location, start.city) }
        }
        if (byName.all { it.isFailure } && byTown.getOrNull().isNullOrEmpty()) throw byName.first().exceptionOrNull()!!

        // Events found by the course's name are on the course even when PDGA names the course differently.
        val courseEvents = byName.flatMap { it.getOrDefault(emptyList()) }.map { it.id }.toSet()
        val candidates = (byName.flatMap { it.getOrDefault(emptyList()) } + byTown.getOrDefault(emptyList()))
            .distinctBy { it.id }
            .sortedByDescending { it.date }
            .take(MAX_EVENTS_TO_CHECK)
        val fetched = mutableListOf<PdgaEvent>()
        for ((i, summary) in candidates.withIndex()) {
            progress = "Checking PDGA event ${i + 1} of ${candidates.size}: ${summary.name}"
            fetched += runCatching { fetchEvent(summary.id) }.getOrNull() ?: continue
            // Enough once the layout the user will (probably) take has five rounds.
            val found = PdgaRoundFinder.candidates(start, fetched, courseEvents)
            val pick = PdgaRoundFinder.suggest(start, found)
            val total = (pick?.rounds?.map { it.id }.orEmpty() + start.calibrationRounds.map { it.id }).distinct().size
            if (pick != null && total >= RatingEstimator.DEFAULT_ROUND_COUNT) break
        }

        val found = PdgaRoundFinder.candidates(start, fetched, courseEvents)
        val searched = "checked ${fetched.size} of ${candidates.size} PDGA events found for ${terms.joinToString { "\"$it\"" }}" +
            if (start.city.isBlank()) "" else " and ${start.city}"
        val pick = if (choose) null else PdgaRoundFinder.linked(start, found) ?: found.singleOrNull()
        when {
            found.isEmpty() -> message = "No PDGA rounds found on $course ($searched). You can add events by link instead."
            pick != null -> {
                val (updated, count) = Layouts.linkCandidate(layout(layoutId) ?: return, pick)
                updateLayout(layoutId) { updated }
                message = "Added $count PDGA round${if (count == 1) "" else "s"} on ${pick.layout?.label ?: course} ($searched)"
            }
            else -> {
                val suggested = PdgaRoundFinder.suggest(start, found)
                pendingChoices += PendingChoice(
                    layoutId,
                    "PDGA has several layouts on $course. Pick the one you play — white and yellow tees can have the same par.",
                    listOfNotNull(suggested) + found.filter { it != suggested },
                    suggested,
                    replace = choose,
                )
            }
        }
    }

    /** Fetches PDGA events by id and adds their rounds on this layout. */
    fun addPdgaEvents(layoutId: String, input: String) {
        val ids = PdgaParser.parseEventIds(input)
        if (ids.isEmpty()) {
            message = "Enter one or more PDGA event ids or links"
            return
        }
        fetchEventsInto(layoutId, ids, refresh = false)
    }

    /** Re-fetches the layout's events, picking up ratings that have become official since. */
    fun refreshPdgaEvents(layoutId: String) {
        val ids = layout(layoutId)?.calibrationRounds?.map { it.eventId }?.distinct().orEmpty()
        if (ids.isEmpty()) {
            message = "No PDGA events to refresh"
            return
        }
        fetchEventsInto(layoutId, ids, refresh = true)
    }

    private fun fetchEventsInto(layoutId: String, ids: List<Long>, refresh: Boolean) = work {
        var added = 0
        val failures = mutableListOf<String>()
        for ((i, id) in ids.withIndex()) {
            progress = "Fetching PDGA event ${i + 1} of ${ids.size}"
            if (refresh) eventCache.remove(id)
            val event = try {
                fetchEvent(id)
            } catch (e: Exception) {
                failures += "$id: ${e.message}"
                continue
            }
            val layout = layout(layoutId) ?: return@work
            val (updated, count) = Layouts.addMatchingRounds(layout, event)
            updateLayout(layoutId) { updated }
            added += count
            if (event.rounds.none { layout.isLinkedTo(it.layout) }) {
                val options = event.layouts.map { (info, rounds) -> Candidate(info, rounds) }
                pendingChoices += PendingChoice(
                    layoutId,
                    "No rounds in ${event.name} were on this layout's PDGA layout. Tap the layout that is the same as yours:",
                    options,
                )
            }
        }
        message = buildString {
            append("Added $added PDGA round${if (added == 1) "" else "s"}")
            if (failures.isNotEmpty()) append("\nFailed: ${failures.joinToString("\n")}")
        }
    }

    fun resolveChoice(choice: PendingChoice, candidate: Candidate?) {
        pendingChoices.remove(choice)
        if (candidate == null) return
        val current = layout(choice.layoutId)?.let {
            if (choice.replace) it.copy(pdgaLayouts = emptyList(), calibrationRounds = emptyList()) else it
        } ?: return
        val (updated, count) = Layouts.linkCandidate(current, candidate)
        updateLayout(choice.layoutId) { updated }
        message = "Added $count PDGA round${if (count == 1) "" else "s"}"
    }

    fun removeCalibrationRound(layoutId: String, roundId: String) = updateLayout(layoutId) { layout ->
        layout.copy(calibrationRounds = layout.calibrationRounds.filter { it.id != roundId })
    }

    // ---- Rounds --------------------------------------------------------------------------------

    fun startRound(layoutId: String, players: List<String>) {
        val layout = layout(layoutId) ?: return
        val round = PlayedRound(
            id = newId(),
            layoutId = layout.id,
            layoutName = layout.name,
            holes = layout.holes,
            startedAt = System.currentTimeMillis(),
            players = players.map { PlayerCard(it, List(layout.holes.size) { null }) },
        )
        update { s ->
            s.copy(
                rounds = listOf(round) + s.rounds,
                knownPlayers = (players + s.knownPlayers).distinct().take(30),
            )
        }
        replace(Screen.Scoring(round.id))
    }

    fun setScore(roundId: String, player: Int, hole: Int, score: Int?) = updateRound(roundId) { round ->
        round.copy(players = round.players.mapIndexed { i, card ->
            if (i != player) card else card.copy(scores = card.scores.mapIndexed { h, s -> if (h == hole) score else s })
        })
    }

    /** Gives every player without a score on [hole] a par. */
    fun fillPar(roundId: String, hole: Int) = updateRound(roundId) { round ->
        val par = round.holes[hole].par
        round.copy(players = round.players.map { card ->
            card.copy(scores = card.scores.mapIndexed { h, s -> if (h == hole && s == null) par else s })
        })
    }

    fun finishRound(roundId: String) {
        updateRound(roundId) { it.copy(finished = true) }
        showOnly(Screen.Summary(roundId), roundId)
    }

    fun openScoring(roundId: String) = showOnly(Screen.Scoring(roundId), roundId)

    /** Shows [screen] without leaving other scoring/summary screens of the round on the back stack. */
    private fun showOnly(screen: Screen, roundId: String) {
        backStack.removeAll { it == Screen.Scoring(roundId) || it == Screen.Summary(roundId) }
        backStack.add(screen)
    }

    fun deleteRound(roundId: String) {
        update { s -> s.copy(rounds = s.rounds.filter { it.id != roundId }) }
        back()
    }
}
