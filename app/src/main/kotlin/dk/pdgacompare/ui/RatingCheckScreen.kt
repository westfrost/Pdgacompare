package dk.pdgacompare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dk.pdgacompare.AppViewModel
import dk.pdgacompare.Screen
import dk.pdgacompare.core.Layout
import dk.pdgacompare.core.RatingEstimator
import kotlin.math.roundToInt

/** Quick look-up of what scores on a layout would rate, without playing it. */
@Composable
fun RatingCheckScreen(vm: AppViewModel, layoutId: String?) {
    val layout = vm.layout(layoutId)
    if (layout == null) {
        LayoutPicker(vm)
        return
    }
    var offset by rememberSaveable(layoutId) { mutableIntStateOf(0) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val score = layout.par + offset
    val estimate = RatingEstimator.estimate(layout.calibrationRounds, score, layout.holes.size)
    val table = remember(layout) { ratingTable(layout) }

    ScreenScaffold(title = layout.name, onBack = vm::back, busy = vm.busy) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Stepper(value = toPar(offset), onMinus = { offset-- }, onPlus = { offset++ }, large = true)
                        Text("$score throws · par ${layout.par} · ${layout.holes.size} holes", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            estimate?.rating?.toString() ?: "–",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            estimate?.let { "Average of the ${it.perRound.size} latest PDGA round(s) · range ${it.min}–${it.max}" }
                                ?: "No PDGA rounds on this layout yet",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        if (estimate != null) {
                            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide rounds" else "Show rounds") }
                        }
                        if (showDetails && estimate != null) {
                            for (r in estimate.perRound) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text("${r.round.date} · ${r.round.eventName} · Rd ${r.round.round}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(r.rating.roundToInt().toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            if (layout.calibrationRounds.isEmpty()) {
                item {
                    Text(
                        "Look up PDGA rounds on this layout to get ratings.",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Button(onClick = { vm.findPdgaRounds(layout.id) }, enabled = !vm.busy) { Text("Find PDGA rounds") }
                    vm.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            } else {
                item {
                    SectionTitle("All scores")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text("Score", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                        Text("Throws", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Text("Rating", style = MaterialTheme.typography.labelMedium)
                    }
                }
                items(table, key = { it.first }) { (rowOffset, rating) ->
                    val selected = rowOffset == offset
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { offset = rowOffset }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(toPar(rowOffset), Modifier.width(72.dp), fontWeight = FontWeight.SemiBold)
                        Text("${layout.par + rowOffset}", Modifier.weight(1f))
                        Text(rating.toString(), fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                TextButton(onClick = { vm.navigate(Screen.LayoutDetail(layout.id)) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Layout and PDGA rounds")
                }
            }
        }
    }
    vm.pendingChoices.firstOrNull { it.layoutId == layout.id }?.let { ChoiceDialog(vm, it) }
}

/** Estimated rating for every score (relative to par) shot in the layout's recent PDGA rounds. */
private fun ratingTable(layout: Layout): List<Pair<Int, Int>> {
    val recent = RatingEstimator.mostRecent(layout.calibrationRounds, layout.holes.size).take(RatingEstimator.DEFAULT_ROUND_COUNT)
    val scores = recent.flatMap { r -> r.results.filter { it.rating > 0 }.map { it.score } }
    if (scores.isEmpty()) return emptyList()
    return (scores.min()..scores.max()).mapNotNull { score ->
        RatingEstimator.estimate(layout.calibrationRounds, score, layout.holes.size)?.let { score - layout.par to it.rating }
    }
}

@Composable
private fun LayoutPicker(vm: AppViewModel) {
    ScreenScaffold(title = "Check a rating", onBack = vm::back) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(onClick = { vm.replace(Screen.NewLayout(thenCheckRating = true)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Find a course")
                }
            }
            if (vm.state.layouts.isNotEmpty()) item { SectionTitle("Your layouts") }
            items(vm.state.layouts, key = { it.id }) { layout ->
                Card(Modifier.fillMaxWidth().clickable { vm.replace(Screen.RatingCheck(layout.id)) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(layout.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${layout.holes.size} holes · par ${layout.par} · ${layout.calibrationRounds.size} PDGA rounds",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
