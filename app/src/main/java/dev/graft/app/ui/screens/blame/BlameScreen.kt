package dev.graft.app.ui.screens.blame

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.graft.app.git.BlameLine
import java.text.DateFormat
import java.util.Date

/** Assigns a stable, muted color per commit SHA so runs of lines from the same commit read
 * as one visual block without needing a comprehensive color-per-author scheme. */
private val blameColors = listOf(150f, 30f, 215f, 320f, 80f, 175f, 15f, 270f)
    // Translucent hue washes rather than fixed dark fills, so bands read in light and dark alike.
    .map { hue -> dev.graft.app.ui.theme.hsl(hue, 0.55f, 0.50f, alpha = 0.20f) }
private fun colorFor(sha: String): Color =
    if (sha.isBlank()) Color(0x22808080) else blameColors[Math.floorMod(sha.hashCode(), blameColors.size)]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlameScreen(
    repoId: Long,
    path: String,
    onBack: () -> Unit,
    onOpenCommit: (sha: String) -> Unit,
    vm: BlameViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(repoId, path) { vm.load(repoId, path) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(state.lines, key = { it.lineNumber }) { line ->
                    val previous = state.lines.getOrNull(line.lineNumber - 2)
                    val isNewBlock = previous == null || previous.commitSha != line.commitSha
                    BlameLineRow(line, showAttribution = isNewBlock, onClick = { onOpenCommit(line.commitSha) })
                }
            }
        }
    }
}

@Composable
private fun BlameLineRow(line: BlameLine, showAttribution: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(colorFor(line.commitSha)),
        )
        Column(
            Modifier.width(120.dp).padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (showAttribution) {
                Text(line.shortSha, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                Text(line.authorName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (line.authorTime > 0) {
                    Text(
                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(line.authorTime)),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "${line.lineNumber}",
            modifier = Modifier.width(36.dp).padding(top = 2.dp),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            line.content,
            modifier = Modifier.weight(1f).padding(top = 2.dp, end = 8.dp),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            maxLines = 1,
        )
    }
}
