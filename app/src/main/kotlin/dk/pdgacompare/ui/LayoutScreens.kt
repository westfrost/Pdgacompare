package dk.pdgacompare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.pdgacompare.AppViewModel
import dk.pdgacompare.PendingLink
import dk.pdgacompare.Screen
import dk.pdgacompare.core.CalibrationRound
import dk.pdgacompare.core.Layout
import dk.pdgacompare.core.PdgaClient
import dk.pdgacompare.core.RatingEstimator

@Composable
fun NewLayoutScreen(vm: AppViewModel) {
    var pdgaInput by rememberSaveable { mutableStateOf("") }
    var metrixInput by rememberSaveable { mutableStateOf("") }
    var manualName by rememberSaveable { mutableStateOf("") }
    var manualHoles by rememberSaveable { mutableStateOf("18") }

    ScreenScaffold(title = "Add layout", onBack = vm::back, busy = vm.busy) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("From a PDGA event")
            Text(
                "Enter a PDGA event played on the layout (event link or id, e.g. pdga.com/tour/event/12345). " +
                    "Its rated rounds on the layout are used to estimate ratings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = pdgaInput,
                onValueChange = { pdgaInput = it },
                label = { Text("PDGA event") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.fetchPdgaForNewLayout(pdgaInput) }, enabled = !vm.busy && pdgaInput.isNotBlank()) {
                Text("Fetch event")
            }
            vm.newLayoutEvent?.let { event ->
                Text("${event.name} · ${event.startDate}", fontWeight = FontWeight.SemiBold)
                Text("Pick the layout you play:", style = MaterialTheme.typography.bodyMedium)
                for ((layout, rounds) in event.layouts) {
                    Card(Modifier.fillMaxWidth().clickable { vm.createLayoutFromPdga(event, layout) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(layout?.label ?: "Layout not stated by PDGA", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${rounds.size} round(s) · ${rounds.sumOf { it.results.size }} rated results",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            SectionTitle("From Disc Golf Metrix")
            Text(
                "Imports holes, par and lengths. Link PDGA events to the layout afterwards to get rating estimates.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = metrixInput,
                onValueChange = { metrixInput = it },
                label = { Text("Metrix course link or id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.state.metrixCode,
                onValueChange = vm::setMetrixCode,
                label = { Text("Metrix API code (if required)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.fetchMetrixForNewLayout(metrixInput) }, enabled = !vm.busy && metrixInput.isNotBlank()) {
                Text("Fetch course")
            }
            vm.newLayoutMetrix?.let { course ->
                Card(Modifier.fillMaxWidth().clickable { vm.createLayoutFromMetrix(course) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(course.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${course.holes.size} holes · par ${course.holes.sumOf { it.par }} · tap to create",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            SectionTitle("Manual")
            OutlinedTextField(
                value = manualName,
                onValueChange = { manualName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = manualHoles,
                onValueChange = { manualHoles = it.filter(Char::isDigit).take(2) },
                label = { Text("Holes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            val holes = manualHoles.toIntOrNull()?.takeIf { it in 1..36 }
            Button(onClick = { vm.createManualLayout(manualName, holes!!) }, enabled = holes != null) {
                Text("Create layout")
            }
        }
    }
}

@Composable
fun LayoutDetailScreen(vm: AppViewModel, layoutId: String) {
    val layout = vm.layout(layoutId)
    if (layout == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingEvents by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    ScreenScaffold(
        title = layout.name,
        onBack = vm::back,
        busy = vm.busy,
        actions = {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete layout") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("${layout.holes.size} holes · par ${layout.par} · from ${layout.source}")
                if (layout.parsEstimated) {
                    Text(
                        "PDGA only gave the total par, so the hole pars below are a guess. Please check them.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { vm.navigate(Screen.NewRound(layout.id)) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Start round on this layout")
                }
            }

            item { EstimateCalculator(layout) }

            item {
                SectionTitle("PDGA rounds")
                Text(
                    "Rated rounds from PDGA events on this layout. Estimates use the ${RatingEstimator.DEFAULT_ROUND_COUNT} most recent.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { addingEvents = true }, enabled = !vm.busy) { Text("Add events") }
                    OutlinedButton(onClick = { vm.refreshPdgaEvents(layout.id) }, enabled = !vm.busy) { Text("Refresh") }
                }
            }
            val sorted = RatingEstimator.mostRecent(layout.calibrationRounds)
            val inUse = RatingEstimator.mostRecent(layout.calibrationRounds, layout.holes.size)
                .filter { r -> r.results.any { it.rating > 0 } }
                .take(RatingEstimator.DEFAULT_ROUND_COUNT)
                .map { it.id }
                .toSet()
            if (sorted.isEmpty()) {
                item { Text("No PDGA rounds yet - add an event played on this layout.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(sorted, key = { it.id }) { round ->
                CalibrationRoundRow(
                    round = round,
                    inUse = round.id in inUse,
                    holes = layout.holes.size,
                    onOpen = { uriHandler.openUri(PdgaClient.eventUrl(round.eventId)) },
                    onDelete = { vm.removeCalibrationRound(layout.id, round.id) },
                )
            }

            item { SectionTitle("Holes") }
            itemsIndexed(layout.holes) { index, hole ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hole ${hole.number}", modifier = Modifier.weight(1f))
                    hole.lengthMeters?.let { Text("$it m  ", style = MaterialTheme.typography.bodySmall) }
                    Text("Par ", style = MaterialTheme.typography.bodySmall)
                    Stepper(
                        value = hole.par.toString(),
                        onMinus = { vm.changePar(layout.id, index, -1) },
                        onPlus = { vm.changePar(layout.id, index, +1) },
                    )
                }
            }
        }
    }

    if (renaming) RenameDialog(layout.name, onRename = { vm.renameLayout(layout.id, it) }, onDismiss = { renaming = false })
    if (deleting) {
        ConfirmDialog(
            title = "Delete layout?",
            text = "\"${layout.name}\" and its PDGA rounds are removed. Played rounds are kept.",
            confirm = "Delete",
            onConfirm = { vm.deleteLayout(layout.id) },
            onDismiss = { deleting = false },
        )
    }
    if (addingEvents) {
        AddEventsDialog(onAdd = { vm.addPdgaEvents(layout.id, it) }, onDismiss = { addingEvents = false })
    }
    vm.pendingLinks.firstOrNull { it.layoutId == layout.id }?.let { PendingLinkDialog(vm, it) }
}

@Composable
private fun EstimateCalculator(layout: Layout) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val score = layout.par + offset
    val estimate = RatingEstimator.estimate(layout.calibrationRounds, score, layout.holes.size)
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("What would a round rate?", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper(value = toPar(offset), onMinus = { offset-- }, onPlus = { offset++ })
                Text("  ($score throws)", modifier = Modifier.weight(1f))
                Text(estimate?.rating?.toString() ?: "–", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                estimate?.let { "Average of ${it.perRound.size} PDGA round(s), range ${it.min}–${it.max}" }
                    ?: "Needs PDGA rounds on this layout",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CalibrationRoundRow(round: CalibrationRound, inUse: Boolean, holes: Int, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${round.eventName} · Rd ${round.round}", fontWeight = FontWeight.SemiBold)
                Text(
                    "${round.date} · ${round.results.size} rated results" + if (inUse) " · in use" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (inUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(round.layout?.label ?: "Layout not stated by PDGA", style = MaterialTheme.typography.bodySmall)
                if (round.layout?.holes != null && round.layout?.holes != holes) {
                    Text("Different number of holes - not used", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove round") }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename layout") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(name); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddEventsDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add PDGA events") },
        text = {
            Column {
                Text(
                    "Paste one or more PDGA event links or ids played on this layout. " +
                        "Rounds on the same PDGA layout are added automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Events") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text); onDismiss() }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PendingLinkDialog(vm: AppViewModel, link: PendingLink) {
    AlertDialog(
        onDismissRequest = { vm.resolvePendingLink(link, null, use = false) },
        title = { Text("Which layout is yours?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "No rounds in ${link.event.name} were on this layout's PDGA layout. Tap the layout that is the same as yours:",
                    style = MaterialTheme.typography.bodySmall,
                )
                for ((layout, rounds) in link.event.layouts) {
                    Card(Modifier.fillMaxWidth().clickable { vm.resolvePendingLink(link, layout, use = true) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(layout?.label ?: "Layout not stated by PDGA", fontWeight = FontWeight.SemiBold)
                            Text("${rounds.size} round(s)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.resolvePendingLink(link, null, use = false) }) { Text("None of them") } },
    )
}
