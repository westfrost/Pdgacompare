package dk.pdgacompare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dk.pdgacompare.AppViewModel
import dk.pdgacompare.Screen
import dk.pdgacompare.core.Layout
import dk.pdgacompare.core.PlayedRound
import dk.pdgacompare.core.PlayerCard
import dk.pdgacompare.core.RatingEstimator
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRoundScreen(vm: AppViewModel, initialLayoutId: String?) {
    val layouts = vm.state.layouts
    var layoutId by rememberSaveable { mutableStateOf(initialLayoutId ?: layouts.firstOrNull()?.id) }
    val players = remember { mutableStateListOf<String>() }
    var name by rememberSaveable { mutableStateOf("") }

    fun addPlayer() {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed !in players) players.add(trimmed)
        name = ""
    }

    ScreenScaffold(title = "New round", onBack = vm::back) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { SectionTitle("Layout") }
            if (layouts.isEmpty()) {
                item {
                    Text("Add a layout first.")
                    Button(onClick = { vm.replace(Screen.NewLayout) }) { Text("Add layout") }
                }
            }
            items(layouts, key = { it.id }) { layout ->
                Row(
                    Modifier.fillMaxWidth().clickable { layoutId = layout.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = layoutId == layout.id, onClick = { layoutId = layout.id })
                    Column {
                        Text(layout.name)
                        Text(
                            "${layout.holes.size} holes · par ${layout.par} · ${layout.calibrationRounds.size} PDGA rounds",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SectionTitle("Players") }
            itemsIndexed(players) { index, player ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(player, modifier = Modifier.weight(1f))
                    IconButton(onClick = { players.removeAt(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove $player")
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Player name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addPlayer() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { addPlayer() }, enabled = name.isNotBlank()) { Text("Add") }
                }
            }
            val suggestions = vm.state.knownPlayers.filter { it !in players }
            if (suggestions.isNotEmpty()) {
                item {
                    Text("Recent players", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions) { player ->
                            AssistChip(onClick = { players.add(player) }, label = { Text(player) })
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { vm.startRound(layoutId!!, players.toList()) },
                    enabled = vm.layout(layoutId) != null && players.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp),
                ) { Text("Start round") }
            }
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoringScreen(vm: AppViewModel, roundId: String) {
    val round = vm.round(roundId)
    if (round == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var hole by rememberSaveable(roundId) {
        mutableIntStateOf(round.holes.indices.firstOrNull { h -> round.players.any { it.scores[h] == null } } ?: 0)
    }
    val current = round.holes[hole]
    val chips = rememberLazyListState()
    LaunchedEffect(hole) { chips.animateScrollToItem((hole - 2).coerceAtLeast(0)) }
    KeepScreenOn()

    ScreenScaffold(
        title = round.layoutName,
        onBack = vm::back,
        actions = { TextButton(onClick = { vm.navigate(Screen.Summary(roundId)) }) { Text("Card") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                state = chips,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(round.holes) { index, h ->
                    val done = round.players.all { it.scores[index] != null }
                    FilterChip(
                        selected = index == hole,
                        onClick = { hole = index },
                        label = { Text(if (done) "${h.number}✓" else "${h.number}") },
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Hole ${current.number}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Par ${current.par}" + (current.lengthMeters?.let { " · $it m" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(round.players) { index, card ->
                    val score = card.scores[hole]
                    val shown = score ?: current.par
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${card.total} (${toPar(card.toPar(round.holes))})", style = MaterialTheme.typography.bodyMedium)
                            }
                            Stepper(
                                value = shown.toString(),
                                dimmed = score == null,
                                large = true,
                                onMinus = { vm.setScore(roundId, index, hole, (shown - 1).coerceAtLeast(1)) },
                                onPlus = { vm.setScore(roundId, index, hole, (shown + 1).coerceAtMost(20)) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Grey scores are par and are saved when you go to the next hole.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { hole-- }, enabled = hole > 0, modifier = Modifier.height(56.dp)) { Text("Previous") }
                val last = hole == round.holes.lastIndex
                Button(
                    onClick = {
                        vm.fillPar(roundId, hole)
                        if (!last) {
                            hole++
                        } else {
                            val updated = vm.round(roundId)
                            val open = updated?.let { r -> r.holes.indices.firstOrNull { h -> r.players.any { it.scores[h] == null } } }
                            if (updated == null || open == null) {
                                vm.finishRound(roundId)
                            } else {
                                hole = open
                                vm.message = "Hole ${updated.holes[open].number} has no score yet"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text(if (last) "Finish round" else "Next hole") }
            }
        }
    }
}

@Composable
fun SummaryScreen(vm: AppViewModel, roundId: String) {
    val round = vm.round(roundId)
    if (round == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val layout = vm.layout(round.layoutId)
    var deleting by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Scorecard",
        onBack = vm::back,
        actions = {
            IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete round") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(round.layoutName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    formatDate(round.startedAt) + if (round.finished) "" else " · in progress",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(round.players) { card ->
                PlayerResult(card, round, layout, onLink = { layout?.let { vm.navigate(Screen.LayoutDetail(it.id)) } })
            }
            item {
                SectionTitle("Scorecard")
                ScorecardTable(round)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.openScoring(roundId) }) { Text("Edit scores") }
                    if (!round.finished) Button(onClick = { vm.finishRound(roundId) }) { Text("Finish round") }
                }
            }
        }
    }

    if (deleting) {
        ConfirmDialog(
            title = "Delete round?",
            text = "The round and its scores are removed.",
            confirm = "Delete",
            onConfirm = { vm.deleteRound(roundId) },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun PlayerResult(card: PlayerCard, round: PlayedRound, layout: Layout?, onLink: () -> Unit) {
    var expanded by rememberSaveable(card.name) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${card.total} (${toPar(card.toPar(round.holes))})", style = MaterialTheme.typography.titleMedium)
            }
            val estimate = layout?.let { RatingEstimator.estimate(it.calibrationRounds, card.total, round.holes.size) }
            when {
                !card.isComplete -> Text("Finish the card to get a rating estimate.", style = MaterialTheme.typography.bodySmall)
                layout == null -> Text("The layout was deleted, so there are no PDGA rounds to compare with.", style = MaterialTheme.typography.bodySmall)
                estimate == null -> {
                    Text(
                        if (layout.calibrationRounds.isEmpty()) "No PDGA rounds are linked to this layout."
                        else "The linked PDGA rounds have no usable results.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onLink) { Text("Add PDGA events") }
                }
                else -> {
                    Text("Estimated PDGA rating", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(estimate.rating.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Average of the ${estimate.perRound.size} latest PDGA round(s) on this layout · range ${estimate.min}–${estimate.max}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
                    if (expanded) {
                        for (r in estimate.perRound) {
                            Column(Modifier.padding(bottom = 8.dp)) {
                                Row {
                                    Text(
                                        "${r.round.eventName} · Rd ${r.round.round}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(r.rating.roundToInt().toString(), fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "${r.round.date} · ${r.exactMatches} shot ${card.total} · " +
                                        "${r.used.size} results from ${r.used.minOf { it.score }} to ${r.used.maxOf { it.score }} used" +
                                        if (r.method == RatingEstimator.Method.EXTRAPOLATED) " · extrapolated" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScorecardTable(round: PlayedRound) {
    val nameWidth = 96.dp
    val cellWidth = 36.dp
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Cell("Hole", nameWidth, bold = true, alignStart = true)
            Cell("Par", nameWidth, alignStart = true)
            round.players.forEach { Cell(it.name, nameWidth, bold = true, alignStart = true) }
        }
        round.holes.forEachIndexed { index, hole ->
            Column {
                Cell(hole.number.toString(), cellWidth, bold = true)
                Cell(hole.par.toString(), cellWidth)
                round.players.forEach { card ->
                    val score = card.scores[index]
                    val color = when {
                        score == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        score < hole.par -> MaterialTheme.colorScheme.primary
                        score > hole.par -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Cell(score?.toString() ?: "–", cellWidth, color = color)
                }
            }
        }
        Column {
            Cell("Tot", 48.dp, bold = true)
            Cell(round.holes.sumOf { it.par }.toString(), 48.dp)
            round.players.forEach { Cell(it.total.toString(), 48.dp, bold = true) }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    bold: Boolean = false,
    alignStart: Boolean = false,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(Modifier.size(width, 32.dp), contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center) {
        Text(
            text,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
        )
    }
}
