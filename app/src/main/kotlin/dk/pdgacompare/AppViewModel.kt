package dk.pdgacompare

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.pdgacompare.core.AppState
import dk.pdgacompare.core.Layout
import dk.pdgacompare.core.Layouts
import dk.pdgacompare.core.MetrixClient
import dk.pdgacompare.core.MetrixCourse
import dk.pdgacompare.core.PdgaClient
import dk.pdgacompare.core.PdgaEvent
import dk.pdgacompare.core.PdgaLayoutInfo
import dk.pdgacompare.core.PdgaParser
import dk.pdgacompare.core.PlayedRound
import dk.pdgacompare.core.PlayerCard
import dk.pdgacompare.core.StateCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object NewLayout : Screen
    data class LayoutDetail(val layoutId: String) : Screen
    data class NewRound(val layoutId: String? = null) : Screen
    data class Scoring(val roundId: String) : Screen
    data class Summary(val roundId: String) : Screen
}

/** An event whose rounds matched none of the layout's PDGA layouts: the user picks which one is the same. */
data class PendingLink(val layoutId: String, val event: PdgaEvent)

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

    val pendingLinks = mutableStateListOf<PendingLink>()

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
            }
        }
    }

    // ---- Layouts -------------------------------------------------------------------------------

    fun setMetrixCode(code: String) = update { it.copy(metrixCode = code) }

    fun fetchPdgaForNewLayout(input: String) = work {
        val id = PdgaParser.parseEventIds(input).firstOrNull() ?: error("Enter a PDGA event id or link")
        newLayoutEvent = null
        newLayoutEvent = pdga.fetchEvent(id)
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

    /** Fetches PDGA events and adds their rounds on this layout. */
    fun addPdgaEvents(layoutId: String, input: String) {
        val ids = PdgaParser.parseEventIds(input)
        if (ids.isEmpty()) {
            message = "Enter one or more PDGA event ids or links"
            return
        }
        fetchEventsInto(layoutId, ids)
    }

    /** Re-fetches the layout's events, picking up ratings that have become official since. */
    fun refreshPdgaEvents(layoutId: String) {
        val ids = layout(layoutId)?.calibrationRounds?.map { it.eventId }?.distinct().orEmpty()
        if (ids.isEmpty()) {
            message = "No PDGA events to refresh"
            return
        }
        fetchEventsInto(layoutId, ids)
    }

    private fun fetchEventsInto(layoutId: String, ids: List<Long>) = work {
        var added = 0
        val failures = mutableListOf<String>()
        for (id in ids) {
            val event = try {
                pdga.fetchEvent(id)
            } catch (e: Exception) {
                failures += "$id: ${e.message}"
                continue
            }
            val layout = layout(layoutId) ?: return@work
            val (updated, count) = Layouts.addMatchingRounds(layout, event)
            val matchedAny = event.rounds.any { (it.layout?.key ?: "") in layout.pdgaLayoutKeys }
            updateLayout(layoutId) { updated }
            added += count
            if (!matchedAny) pendingLinks += PendingLink(layoutId, event)
        }
        message = buildString {
            append("Added $added PDGA round${if (added == 1) "" else "s"}")
            if (failures.isNotEmpty()) append("\nFailed: ${failures.joinToString("\n")}")
        }
    }

    fun resolvePendingLink(link: PendingLink, layout: PdgaLayoutInfo?, use: Boolean) {
        pendingLinks.remove(link)
        if (!use) return
        val current = layout(link.layoutId) ?: return
        val (updated, count) = Layouts.linkPdgaLayout(current, link.event, layout)
        updateLayout(link.layoutId) { updated }
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
