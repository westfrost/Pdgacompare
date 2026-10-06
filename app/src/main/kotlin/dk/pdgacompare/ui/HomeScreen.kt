package dk.pdgacompare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dk.pdgacompare.AppViewModel
import dk.pdgacompare.Screen

@Composable
fun HomeScreen(vm: AppViewModel) {
    val state = vm.state
    ScreenScaffold(title = "PDGA Compare", onBack = null) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(
                    onClick = { vm.navigate(Screen.NewRound()) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Start new round") }
            }

            item { SectionTitle("Rounds") }
            if (state.rounds.isEmpty()) {
                item { Text("No rounds yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.rounds, key = { it.id }) { round ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        vm.navigate(if (round.finished) Screen.Summary(round.id) else Screen.Scoring(round.id))
                    },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(round.layoutName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (!round.finished) {
                                Text("In progress", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(formatDate(round.startedAt), style = MaterialTheme.typography.bodySmall)
                        Text(
                            round.players.joinToString(" · ") { "${it.name} ${it.total} (${toPar(it.toPar(round.holes))})" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Layouts", Modifier.weight(1f))
                    TextButton(onClick = { vm.navigate(Screen.NewLayout) }) { Text("Add layout") }
                }
            }
            if (state.layouts.isEmpty()) {
                item {
                    Text(
                        "Add a layout from a PDGA event played on it. The event's rated rounds are used to estimate the rating of your rounds.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.layouts, key = { it.id }) { layout ->
                Card(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.LayoutDetail(layout.id)) }) {
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
