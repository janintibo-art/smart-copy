package com.smartcopy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0F17)
private val CardBg = Color(0xFF141A26)
private val CardBg2 = Color(0xFF1B2333)
private val Track = Color(0xFF232C3D)
private val Neutral = Color(0xFF2A3345)
private val Green = Color(0xFF3DDC97)
private val Blue = Color(0xFF5AA9FF)
private val Orange = Color(0xFFFFB547)
private val Red = Color(0xFFFF6B6B)
private val TextMain = Color(0xFFE8ECF4)
private val Muted = Color(0xFF8A94A8)
private val DarkText = Color(0xFF07101A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CopyEngine.init(applicationContext)
        setContent {
            SmartCopyTheme {
                SmartCopyScreen()
            }
        }
    }
}

@Composable
fun SmartCopyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            secondary = Blue,
            background = Bg,
            surface = CardBg,
            onPrimary = DarkText,
            onBackground = TextMain,
            onSurface = TextMain
        ),
        content = content
    )
}

@Composable
fun SmartCopyScreen() {
    val s by CopyEngine.state.collectAsState()
    val context = LocalContext.current

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) CopyEngine.addFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) CopyEngine.addFolder(uri)
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) CopyEngine.setDestination(uri)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var confirmMove by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var openedRun by remember { mutableStateOf(-1L) }

    if (confirmMove) {
        val pending = s.items.count { it.status != ItemStatus.DONE && it.status != ItemStatus.SKIPPED }
        AlertDialog(
            onDismissRequest = { confirmMove = false },
            containerColor = CardBg,
            title = { Text("Déplacer $pending fichier(s) ?", color = TextMain) },
            text = {
                Text(
                    "Chaque original sera supprimé une fois sa copie vérifiée. " +
                        "Les fichiers en échec restent à leur place.",
                    color = Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMove = false
                    CopyEngine.start()
                }) { Text("Déplacer", color = Orange) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMove = false }) { Text("Annuler", color = TextMain) }
            }
        )
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header() }
        val offer = s.resumeOffer
        if (offer != null && !s.running) {
            item { ResumePanel(offer) }
        }
        item { DestinationPanel(s) { pickDest.launch(null) } }
        item { AnalysisPanel(s) }
        item { OptionsPanel(s) }
        item { ProgressPanel(s) }
        item {
            Controls(
                s,
                onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                onAddFolder = { pickFolder.launch(null) },
                onStart = {
                    if (s.mode == TransferMode.MOVE) {
                        confirmMove = true
                    } else {
                        CopyEngine.start()
                    }
                }
            )
        }
        val msg = s.message
        if (msg != null) {
            item { MessageBar(msg) }
        }
        item {
            Text(
                "File d'attente (${s.filesTotal})",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (s.items.isEmpty()) {
            item {
                Text(
                    "Ajoutez des fichiers ou un dossier. Vous pourrez en ajouter d'autres pendant la copie.",
                    color = Muted,
                    fontSize = 13.sp
                )
            }
        }
        items(s.items, key = { it.id }) { item -> ItemRow(item) }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg)
                    .clickable { showHistory = !showHistory }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Historique (${s.pastRuns.size})",
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(if (showHistory) "Masquer" else "Afficher", color = Blue, fontSize = 13.sp)
            }
        }
        if (showHistory) {
            if (s.pastRuns.isEmpty()) {
                item { Text("Aucun transfert enregistré pour l'instant.", color = Muted, fontSize = 13.sp) }
            } else {
                items(s.pastRuns, key = { "h" + it.time }) { run ->
                    HistoryRow(run, expanded = openedRun == run.time) {
                        openedRun = if (openedRun == run.time) -1L else run.time
                    }
                }
                item {
                    ActionButton("Effacer l'historique", Neutral, contentColor = TextMain, modifier = Modifier.fillMaxWidth()) {
                        CopyEngine.clearHistory()
                    }
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

@Composable
private fun ResumePanel(offer: ResumeOffer) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A2A12))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Transfert inachevé", color = Orange, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${offer.files} fichier(s) restant(s), ${formatBytes(offer.bytes)}, vers ${offer.destinationName}. " +
                "Dernier état enregistré le ${dateFormat.format(Date(offer.savedAt))}.",
            color = TextMain,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Reprendre", Green, modifier = Modifier.weight(1f)) { CopyEngine.resumeSession() }
            ActionButton("Abandonner", Neutral, contentColor = TextMain, modifier = Modifier.weight(1f)) {
                CopyEngine.discardSession()
            }
        }
    }
}

@Composable
private fun HistoryRow(run: HistoryEntry, expanded: Boolean, onToggle: () -> Unit) {
    val (label, color) = when {
        run.cancelled -> Pair("Annulé", Muted)
        run.failed > 0 -> Pair("${run.failed} échec(s)", Red)
        else -> Pair("Réussi", Green)
    }
    val verb = if (run.mode == TransferMode.MOVE) "Déplacement" else "Copie"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$verb · ${dateFormat.format(Date(run.time))}",
                color = TextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        val skipped = if (run.skipped > 0) " · ${run.skipped} ignoré(s)" else ""
        Text(
            "${run.done} fichier(s) · ${formatBytes(run.bytes)}$skipped" + if (run.verified) " · vérifiés" else "",
            color = Muted,
            fontSize = 12.sp
        )
        Text(
            "Durée ${formatDuration(run.durationMs)} · moyenne ${formatSpeed(run.avgSpeed)} · pic ${formatSpeed(run.peakSpeed)}",
            color = Muted,
            fontSize = 12.sp
        )
        if (run.destination.isNotEmpty()) {
            Text("→ ${run.destination}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded && run.failures.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            for (f in run.failures) {
                Text("• ${f.first} : ${f.second}", color = Red, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Header() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B3A5C), Color(0xFF123D32))))
            .padding(20.dp)
    ) {
        Text("Smart Copy", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Copie intelligente · débit optimisé", color = Color(0xFFB5C3D8), fontSize = 13.sp)
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title.uppercase(Locale.FRANCE),
            color = Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        content()
    }
}

@Composable
private fun DestinationPanel(s: UiState, onPick: () -> Unit) {
    Panel("Destination") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    s.destinationName ?: "Aucun dossier choisi",
                    color = TextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val v = s.destinationInfo
                if (v != null) {
                    Text(v.kind, color = Blue, fontSize = 12.sp)
                    if (v.totalBytes > 0) {
                        Text(
                            "Libre : ${formatBytes(v.freeBytes)} sur ${formatBytes(v.totalBytes)}",
                            color = Muted,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Bar(1f - v.freeBytes.toFloat() / v.totalBytes.toFloat(), Blue, 6.dp)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            ActionButton("Choisir", Blue, enabled = !s.running, onClick = onPick)
        }
    }
}

@Composable
private fun AnalysisPanel(s: UiState) {
    Panel("Analyse des mémoires") {
        val a = s.analysis
        val step = s.analyzing
        if (step != null) {
            Text("En cours : $step", color = Orange, fontSize = 14.sp)
        } else if (a == null) {
            Text(
                "Mesure le débit réel d'écriture de la destination pour plusieurs tailles de bloc, " +
                    "la vitesse de lecture de la source et la latence des petits fichiers. " +
                    "Lancée automatiquement au démarrage si vous ne le faites pas.",
                color = Muted,
                fontSize = 13.sp
            )
        } else {
            Row(Modifier.fillMaxWidth()) {
                Stat(
                    "Lecture source",
                    if (a.sourceReadMBps > 0) formatMBps(a.sourceReadMBps) else "—",
                    Green,
                    Modifier.weight(1f)
                )
                Stat("Écriture destination", formatMBps(a.writeResults.maxOfOrNull { it.mbPerSec } ?: 0.0), Blue, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Bloc optimal", formatBytes(a.bestBlock.toLong()), Orange, Modifier.weight(1f))
                Stat("Petit fichier", String.format(Locale.FRANCE, "%.1f ms", a.smallFileLatencyMs), TextMain, Modifier.weight(1f))
                Stat("Flux", "${a.workers}", TextMain, Modifier.weight(1f))
            }
            val src = a.source
            if (src != null) {
                Text("Source : ${src.label} — ${src.kind}", color = Muted, fontSize = 12.sp)
            }
            if (a.sameVolume) {
                Text(
                    "Source et destination sur le même support : les gros fichiers passent un par un.",
                    color = Orange,
                    fontSize = 12.sp
                )
            }
            BlockChart(a.writeResults, a.bestBlock)
        }
        ActionButton(
            if (a == null) "Analyser" else "Relancer l'analyse",
            Green,
            enabled = s.destination != null && !s.running && step == null,
            modifier = Modifier.fillMaxWidth()
        ) { CopyEngine.analyze() }
    }
}

@Composable
private fun ProgressPanel(s: UiState) {
    val frac = if (s.totalBytes > 0) (s.copiedBytes.toDouble() / s.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    Panel("Progression") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.FRANCE, "%.1f", frac * 100f),
                color = TextMain,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
            Text(" %", color = Muted, fontSize = 20.sp, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(
                statusLabel(s),
                color = statusColor(s),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Bar(frac, Green, 14.dp)
        Text("${formatBytes(s.copiedBytes)} sur ${formatBytes(s.totalBytes)}", color = Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth()) {
            Stat("Vitesse", formatSpeed(s.speed), Green, Modifier.weight(1f))
            Stat("Moyenne", formatSpeed(s.avgSpeed), Blue, Modifier.weight(1f))
            Stat("Pic", formatSpeed(s.peakSpeed), Orange, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Restant", formatDuration(s.etaMs), TextMain, Modifier.weight(1f))
            Stat("Écoulé", formatDuration(s.elapsedMs), TextMain, Modifier.weight(1f))
            Stat("Fichiers", "${s.filesDone} / ${s.filesTotal}", TextMain, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Bloc actuel", formatBytes(s.blockSize.toLong()), TextMain, Modifier.weight(1f))
            Stat("Flux actifs", "${s.activeStreams}", TextMain, Modifier.weight(1f))
            Stat("Ignorés", "${s.filesSkipped}", TextMain, Modifier.weight(1f))
            Stat("Échecs", "${s.filesFailed}", if (s.filesFailed > 0) Red else TextMain, Modifier.weight(1f))
        }
        SpeedChart(s.history)
    }
}

private fun statusLabel(s: UiState): String = when {
    s.running && s.paused -> "En pause"
    s.running -> if (s.mode == TransferMode.MOVE) "Déplacement en cours" else "Copie en cours"
    s.filesTotal > 0 && s.filesDone + s.filesSkipped == s.filesTotal -> "Terminé"
    else -> "Prêt"
}

private fun statusColor(s: UiState): Color = when {
    s.running && s.paused -> Orange
    s.running -> Blue
    s.filesTotal > 0 && s.filesDone + s.filesSkipped == s.filesTotal -> Green
    else -> Muted
}

@Composable
private fun Controls(s: UiState, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("+ Fichiers", Blue, modifier = Modifier.weight(1f), onClick = onAddFiles)
            ActionButton("+ Dossier", Blue, modifier = Modifier.weight(1f), onClick = onAddFolder)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.running) {
                ActionButton(
                    if (s.mode == TransferMode.MOVE) "Déplacer" else "Démarrer",
                    if (s.mode == TransferMode.MOVE) Orange else Green,
                    enabled = s.destination != null && s.items.any { it.status != ItemStatus.DONE && it.status != ItemStatus.SKIPPED } && s.analyzing == null,
                    modifier = Modifier.weight(1f),
                    onClick = onStart
                )
                ActionButton(
                    "Vider la liste",
                    Neutral,
                    contentColor = TextMain,
                    enabled = s.items.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { CopyEngine.clear() }
            } else {
                ActionButton(
                    if (s.paused) "Reprendre" else "Pause",
                    Orange,
                    modifier = Modifier.weight(1f)
                ) { CopyEngine.togglePause() }
                ActionButton("Annuler", Red, modifier = Modifier.weight(1f)) { CopyEngine.cancel() }
            }
        }
        if (s.running) {
            Text(
                "Les fichiers ajoutés maintenant passent à la suite de la liste.",
                color = Muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun MessageBar(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg2)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = TextMain, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { CopyEngine.clearMessage() }) {
            Text("OK", color = Green)
        }
    }
}

@Composable
private fun ItemRow(item: ItemUi) {
    val copyFrac = when {
        item.size > 0 -> (item.copied.toFloat() / item.size.toFloat()).coerceIn(0f, 1f)
        item.status == ItemStatus.DONE -> 1f
        else -> 0f
    }
    val verifyFrac = if (item.size > 0) (item.verified.toFloat() / item.size.toFloat()).coerceIn(0f, 1f) else 0f
    val color = when (item.status) {
        ItemStatus.DONE -> Green
        ItemStatus.FAILED -> Red
        ItemStatus.CANCELLED -> Muted
        ItemStatus.COPYING -> Blue
        ItemStatus.VERIFYING -> Orange
        ItemStatus.SKIPPED -> Muted
        ItemStatus.PENDING -> Muted
    }
    val label = when (item.status) {
        ItemStatus.DONE -> when {
            item.already -> "Déjà copié avant l'interruption"
            item.instant -> "Déplacé (instantané)"
            item.moved && item.verified > 0 -> "Déplacé · vérifié"
            item.moved -> "Déplacé"
            item.verified > 0 -> "Copié · vérifié"
            else -> "Copié"
        }
        ItemStatus.FAILED -> "Échec"
        ItemStatus.CANCELLED -> "Annulé"
        ItemStatus.COPYING -> String.format(Locale.FRANCE, "%.0f %%", copyFrac * 100f)
        ItemStatus.VERIFYING -> String.format(Locale.FRANCE, "Vérif. %.0f %%", verifyFrac * 100f)
        ItemStatus.SKIPPED -> if (item.moved) "Identique · original supprimé" else "Ignoré (déjà présent)"
        ItemStatus.PENDING -> "En attente"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.path.isNotEmpty()) {
                    Text(item.path, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        when (item.status) {
            ItemStatus.COPYING -> Bar(copyFrac, color, 6.dp)
            ItemStatus.DONE -> Bar(1f, color, 6.dp)
            ItemStatus.VERIFYING -> Bar(verifyFrac, Orange, 6.dp)
            else -> {
            }
        }
        Text(
            when (item.status) {
                ItemStatus.COPYING -> "${formatBytes(item.copied)} / ${formatBytes(item.size)}"
                ItemStatus.VERIFYING -> "Relecture ${formatBytes(item.verified)} / ${formatBytes(item.size)}"
                else -> formatBytes(item.size)
            },
            color = Muted,
            fontSize = 11.sp
        )
        val hash = item.hash
        if ((item.status == ItemStatus.DONE || item.status == ItemStatus.SKIPPED) && hash != null) {
            Text("SHA-256 " + hash.take(16) + "…", color = Muted, fontSize = 10.sp, maxLines = 1)
        }
        val err = item.error
        if (item.status == ItemStatus.FAILED && err != null) {
            Text(err, color = Red, fontSize = 11.sp)
        }
        val warn = item.warning
        if (warn != null) {
            Text(warn, color = Orange, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OptionsPanel(s: UiState) {
    val move = s.mode == TransferMode.MOVE
    Panel("Options") {
        Text("Mode", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in TransferMode.values()) {
                Chip(
                    m.label,
                    selected = s.mode == m,
                    enabled = !s.running,
                    selectedColor = if (m == TransferMode.MOVE) Orange else Blue,
                    modifier = Modifier.weight(1f)
                ) { CopyEngine.setMode(m) }
            }
        }
        Text(s.mode.help, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Vérifier chaque copie", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (move) "Toujours active en mode Déplacer : l'original n'est supprimé qu'après vérification."
                    else "Relit la copie et compare son empreinte SHA-256 avec celle de l'original.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = s.verify || move,
                onCheckedChange = { CopyEngine.setVerify(it) },
                enabled = !move,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = DarkText,
                    checkedTrackColor = Green,
                    uncheckedThumbColor = Muted,
                    uncheckedTrackColor = Track
                )
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("Si le fichier existe déjà", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in ConflictPolicy.values()) {
                Chip(p.label, selected = s.policy == p, modifier = Modifier.weight(1f)) {
                    CopyEngine.setPolicy(p)
                }
            }
        }
        Text(s.policy.help, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedColor: Color = Blue,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) selectedColor else if (enabled) Neutral else Track)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) DarkText else TextMain,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 11.sp, maxLines = 1)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, height: Dp, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(Track)
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.65f), color)))
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = DarkText,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = contentColor,
            disabledContainerColor = Track,
            disabledContentColor = Muted
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun BlockChart(results: List<BlockResult>, best: Int) {
    if (results.isEmpty()) return
    val max = results.maxOf { it.mbPerSec }.coerceAtLeast(0.001)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Débit d'écriture selon la taille de bloc (Mo/s)", color = Muted, fontSize = 11.sp)
        for (r in results) {
            val isBest = r.blockSize == best
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatBytes(r.blockSize.toLong()),
                    color = if (isBest) Orange else Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.width(60.dp)
                )
                Bar((r.mbPerSec / max).toFloat(), if (isBest) Orange else Blue, 8.dp, Modifier.weight(1f))
                Text(
                    String.format(Locale.FRANCE, "%.0f", r.mbPerSec),
                    color = Muted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp)
                )
            }
        }
    }
}

@Composable
private fun SpeedChart(history: List<Float>) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg2)
    ) {
        if (history.size < 2) {
            Text(
                "Graphique du débit en direct",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val maxValue = history.max().coerceAtLeast(1f)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                val w = size.width
                val h = size.height
                for (k in 1..3) {
                    val y = h * k / 4f
                    drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(w, y), 1f)
                }
                val step = w / 119f
                val startX = w - step * (history.size - 1)
                val line = Path()
                val fill = Path()
                history.forEachIndexed { i, v ->
                    val x = startX + i * step
                    val y = h - (v / maxValue) * h
                    if (i == 0) {
                        line.moveTo(x, y)
                        fill.moveTo(x, h)
                        fill.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        fill.lineTo(x, y)
                    }
                }
                fill.lineTo(startX + step * (history.size - 1), h)
                fill.close()
                drawPath(fill, Brush.verticalGradient(listOf(Green.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(line, Green, style = Stroke(width = 2.dp.toPx()))
            }
            Text(
                formatSpeed(maxValue.toDouble()),
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
        }
    }
}
