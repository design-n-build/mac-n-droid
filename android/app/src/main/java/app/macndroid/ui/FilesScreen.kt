package app.macndroid.ui

import app.macndroid.core.Computer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.service.SyncService
import app.macndroid.service.TransferItem
import app.macndroid.service.Transfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val ctx = LocalContext.current
    val items by Transfers.items.collectAsState()
    val status by Link.status.collectAsState()
    val mac = Prefs.pairing.collectAsState().value?.macName ?: Computer.word
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var tab by rememberSaveable { mutableIntStateOf(0) }   // 0 = Received, 1 = Sent
    val received = items.filter { !it.outgoing }
    val sent = items.filter { it.outgoing }
    val shown = if (tab == 0) received else sent
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) SyncService.sendUris(ctx, uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) SyncService.sendFolder(ctx, tree)
    }
    var sendMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text("Files") },
                    actions = {
                        if (items.any { it.state != TransferItem.State.Active }) {
                            TextButton(onClick = { Transfers.clearFinished() }) { Text("Clear") }
                        }
                    },
                    scrollBehavior = scroll,
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 },
                        text = { Text(if (received.isEmpty()) "Received" else "Received (${received.size})") },
                        icon = { Icon(Icons.Outlined.Download, null) })
                    Tab(selected = tab == 1, onClick = { tab = 1 },
                        text = { Text(if (sent.isEmpty()) "Sent" else "Sent (${sent.size})") },
                        icon = { Icon(Icons.Outlined.Upload, null) })
                }
            }
        },
        floatingActionButton = {
            if (status == Link.Status.Connected) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { sendMenu = true },
                        icon = { Icon(Icons.Outlined.UploadFile, null) },
                        text = { Text("Send to $mac") },
                    )
                    SendMenu(sendMenu, onDismiss = { sendMenu = false },
                        onFiles = { pick.launch(arrayOf("*/*")) }, onFolder = { pickFolder.launch(null) })
                }
            }
        },
    ) { pad ->
        if (shown.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(if (tab == 0) "Nothing received yet" else "Nothing sent yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (tab == 0) "Drag files or folders onto Mac n Droid on ${Computer.your} to send them here. They're saved in Downloads › Mac n Droid."
                    else "Tap Send to $mac, or use Share → Send to ${Computer.word} in any app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { FileCard(it, mac) }
            }
        }
    }
}

@Composable
private fun FileCard(t: TransferItem, mac: String) {
    val ctx = LocalContext.current
    // A received folder opens Downloads; a sent one has nothing to open.
    val canOpen = t.state == TransferItem.State.Done && (t.uri != null || (t.isFolder && !t.outgoing))
    fun open() {
        if (t.isFolder) {
            runCatching { ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            return
        }
        val uri = t.uri ?: return
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri) ?: t.mime.ifBlank { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
    Card(
        onClick = { if (canOpen) open() },
        enabled = canOpen,
        colors = CardDefaults.cardColors(
            containerColor = tileColor,
            disabledContainerColor = tileColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(t.uri, t.mime, ready = t.state == TransferItem.State.Done)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                when (t.state) {
                    TransferItem.State.Active -> {
                        LinearProgressIndicator(progress = { t.progress }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text(if (t.outgoing) "Sending to $mac…" else "Receiving from $mac…",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TransferItem.State.Done -> Text(
                        listOf(
                            if (t.outgoing) "Sent to $mac" else "From $mac",
                            if (t.size > 0) Formatter.formatShortFileSize(ctx, t.size) else null,
                            DateUtils.getRelativeTimeSpanString(t.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        ).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TransferItem.State.Failed -> Text("Transfer failed", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            if (canOpen) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = ::open, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Open") }
            }
        }
    }
}

/** Real preview for photos, videos and PDFs (via the system thumbnailer), otherwise a type icon. */
@Composable
private fun Thumbnail(uri: Uri?, mime: String, ready: Boolean) {
    val ctx = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri, ready) {
        value = if (uri != null && ready) withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
        } else null
    }
    val shape = RoundedCornerShape(12.dp)
    Surface(shape = shape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp)) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().clip(shape), contentScale = ContentScale.Crop)
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(iconFor(mime), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** Choose files or a whole folder to send. */
@Composable
fun SendMenu(expanded: Boolean, onDismiss: () -> Unit, onFiles: () -> Unit, onFolder: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text("Files") }, onClick = { onDismiss(); onFiles() },
            leadingIcon = { Icon(Icons.Outlined.InsertDriveFile, null) })
        DropdownMenuItem(text = { Text("Folder") }, onClick = { onDismiss(); onFolder() },
            leadingIcon = { Icon(Icons.Outlined.Folder, null) })
    }
}

private fun iconFor(mime: String): ImageVector = when {
    mime == app.macndroid.service.FOLDER_MIME -> Icons.Outlined.Folder
    mime.startsWith("image/") -> Icons.Outlined.Image
    mime.startsWith("video/") -> Icons.Outlined.VideoFile
    mime.startsWith("audio/") -> Icons.Outlined.AudioFile
    mime == "application/pdf" -> Icons.Outlined.PictureAsPdf
    mime.contains("zip") || mime.contains("compressed") -> Icons.Outlined.FolderZip
    mime.startsWith("text/") || mime.contains("document") || mime.contains("word") -> Icons.AutoMirrored.Outlined.Article
    else -> Icons.Outlined.InsertDriveFile
}
