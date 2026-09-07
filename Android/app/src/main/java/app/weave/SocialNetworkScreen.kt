package app.weave

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.weave.backbone.ScoredProfile
import app.weave.backbone.VerificationState
import kotlin.math.roundToInt

@Composable
fun SocialNetworkScreen(
    controller: SocialNetworkController,
    ownProfileText: () -> String,
    ownProfileName: () -> String,
    onOpenRemote: (mainDht: String, name: String, profileText: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by controller.ui.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Profile") }
            Column(Modifier.weight(1f)) {
                Text("Weave", style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text(state.status, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Recent 50", "Publish", "Discovery Lab", "Network / Debug").forEachIndexed { index, label ->
                FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 7.dp))
        when (tab) {
            0 -> SocialRecentTab(state, controller, onOpenRemote)
            1 -> SocialPublishTab(state, controller, ownProfileText, ownProfileName)
            2 -> SocialDiscoveryTab(state, controller, onOpenRemote)
            else -> SocialDebugTab(state, controller)
        }
    }
}

@Composable
private fun SocialRecentTab(state: SocialUiState, controller: SocialNetworkController, onOpenRemote: (String, String, String) -> Unit) {
    if (state.recent.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No other profile pages discovered yet. VeilKnit app discovery supplies bootstrap peers; Weave gossip fills this list as profiles are encountered.")
            Button(onClick = { controller.refreshNow() }) { Text("Refresh peers") }
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.recent, key = { it.hint.mainDht }) { row ->
            ProfileDiscoveryCard(row.hint.name, row.hint.description, row.hint.features, row.hint.mainDht, row.hint.profileRootDht,
                row.hint.verification, row.hint.minhash.compactHex(), row.openable,
                onOpen = {
                    controller.profileText(row.hint.mainDht)?.let { onOpenRemote(row.hint.mainDht, row.hint.name, it) }
                        ?: controller.ensureProfileAvailable(row.hint.mainDht)
                },
                onMore = { controller.moreLike(row.hint.mainDht) },
                onAvoid = { controller.avoidLike(row.hint.mainDht) })
        }
    }
}

@Composable
private fun SocialPublishTab(state: SocialUiState, controller: SocialNetworkController, ownProfileText: () -> String, ownProfileName: () -> String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("The decorated VSPF page is the public profile. These fields are discovery metadata used by MinHash/search; the profile name is still excluded from the MinHash signature.")
        OutlinedTextField(
            value = state.discoveryName,
            onValueChange = controller::setDiscoveryName,
            label = { Text("Discovery/display name (blank = Profile Page name)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(state.discoveryDescription, controller::setDiscoveryDescription, label = { Text("Discovery description") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.featuresText, controller::setFeatures, label = { Text("Skills / interests / tags (comma, semicolon, or new line)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { controller.publishProfile(ownProfileText(), ownProfileName()) }) { Text("Publish current Profile Page") }
        Text("The VSPF document is uploaded through the daemon blob store; this app-root DHT holds only its descriptor/hash and discovery metadata.", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Column {
                Text("Main DHT: ${state.mainDht}", fontFamily = FontFamily.Monospace)
                Text("Profile root: ${state.profileRoot}", fontFamily = FontFamily.Monospace)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(state.mainDht)) }) { Text("Copy main DHT") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(state.profileRoot)) }) { Text("Copy profile root") }
        }
    }
}

@Composable
private fun SocialDiscoveryTab(state: SocialUiState, controller: SocialNetworkController, onOpenRemote: (String, String, String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedTextField(state.nameQuery, controller::setNameQuery, label = { Text("Name / prefix search (e.g. bob)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.query, controller::setQuery, label = { Text("Feature/keyword seed (e.g. woodworking)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.positiveMainDht, controller::setPositive, label = { Text("More-like profile main DHT (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.negativeMainDht, controller::setNegative, label = { Text("Avoid-like profile main DHT (optional)") }, modifier = Modifier.fillMaxWidth())
            SocialSlider("Avoidance strength", state.avoidance, 0f..1.5f, controller::setAvoidance)
            SocialSlider("Common-word penalty", state.commonPenalty, 0f..1f, controller::setCommonPenalty)
            SocialSlider("Stuffing penalty", state.stuffingPenalty, 0f..1f, controller::setStuffingPenalty)
            SocialSlider("Novelty / exploration", state.novelty, 0f..0.75f, controller::setNovelty)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { controller.search() }) { Text("Run / continue search") }
                Button(onClick = { controller.clearExamples() }) { Text("Clear examples") }
                Button(onClick = { controller.refreshNow() }) { Text("Refresh peers") }
                Button(onClick = { controller.gossipNow() }) { Text("Gossip now") }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Results (${state.searchResults.size})")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            items(state.searchResults, key = { it.hint.mainDht }) { result -> SearchResultCard(result, controller, onOpenRemote) }
        }
    }
}

@Composable
private fun SearchResultCard(result: ScoredProfile, controller: SocialNetworkController, onOpenRemote: (String, String, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = controller.profileText(result.hint.mainDht)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
                Text("${result.hint.name.ifBlank { "(unnamed)" }}  score=${"%.3f".format(result.score)}  name=${"%.2f".format(result.nameScore)}  +sim=${"%.2f".format(result.positiveSimilarity)}  -sim=${"%.2f".format(result.negativeSimilarity)}  novelty=${"%.2f".format(result.noveltyBonus)}\n${result.hint.mainDht}")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Button(onClick = {
                    if (text != null) onOpenRemote(result.hint.mainDht, result.hint.name, text) else controller.ensureProfileAvailable(result.hint.mainDht)
                }) { Text(if (text != null) "Open page" else "Fetch + verify") }
                TextButton(onClick = { controller.moreLike(result.hint.mainDht) }) { Text("More like") }
                TextButton(onClick = { controller.avoidLike(result.hint.mainDht) }) { Text("Avoid like") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(result.hint.mainDht)) }) { Text("Copy DHT") }
            }
        }
    }
}

@Composable
private fun ProfileDiscoveryCard(
    name: String, description: String, features: List<String>, mainDht: String, root: String,
    verification: VerificationState, signature: String, openable: Boolean,
    onOpen: () -> Unit, onMore: () -> Unit, onAvoid: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SelectionContainer {
                Column {
                    Text(name.ifBlank { "(unnamed profile)" }, style = MaterialTheme.typography.titleMedium)
                    if (description.isNotBlank()) Text(description)
                    if (features.isNotEmpty()) Text("Skills/interests: ${features.joinToString(", ")}")
                    Text("State: ${verification.name.lowercase()} • signature: $signature", style = MaterialTheme.typography.labelSmall)
                    Text(mainDht, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Button(onClick = onOpen) { Text(if (openable) "Open page" else "Fetch + verify") }
                TextButton(onClick = onMore) { Text("More like") }
                TextButton(onClick = onAvoid) { Text("Avoid like") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(mainDht)) }) { Text("Copy DHT") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(root)) }) { Text("Copy root") }
            }
        }
    }
}

@Composable
private fun SocialDebugTab(state: SocialUiState, controller: SocialNetworkController) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SelectionContainer {
            Text("Peers=${state.peers}   DHT/blob-verified=${state.verified}   gossip sent=${state.gossipSent}   gossip received=${state.gossipReceived}\nMain DHT=${state.mainDht}\nProfile root=${state.profileRoot}")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { controller.refreshNow() }) { Text("Refresh") }
            Button(onClick = { controller.gossipNow() }) { Text("Gossip now") }
            Button(onClick = { clipboard.setText(AnnotatedString(state.clusterText)) }) { Text("Copy clusters") }
            Button(onClick = { clipboard.setText(AnnotatedString(state.debugLog)) }) { Text("Copy debug log") }
        }
        Text("Cluster map / rotating record table")
        OutlinedTextField(state.clusterText, {}, readOnly = true, modifier = Modifier.fillMaxWidth().heightIn(min = 115.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
        Text("Daemon + Weave debug log")
        OutlinedTextField(state.debugLog, {}, readOnly = true, modifier = Modifier.fillMaxWidth().weight(1f), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun SocialSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, setter: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = setter, valueRange = range)
    }
}
