package app.macndroid.ui

import app.macndroid.core.Computer

import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.service.ClipHistory

/** The last 20 things copied on the phone or the Mac. Tap to copy here; long-press for more. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ClipboardScreen() {
    val ctx = LocalContext.current
    val items by ClipHistory.items.collectAsState()
    val status by Link.status.collectAsState()
    val pairing by Prefs.pairing.collectAsState()
    val mac = pairing?.macName ?: Computer.word
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menuFor by remember { mutableStateOf<ClipHistory.Item?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Clipboard") },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteSweep, "Clear history") }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (status == Link.Status.Connected) "Things you copy on this phone or on $mac show up here. The last 20 are kept."
                    else "Connect to $mac to see what you've copied on both devices.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Tap to copy on this phone. Touch and hold to copy on $mac.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(items, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = tileColor),
                    modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape).combinedClickable(
                        onClick = {
                            val ok = ClipHistory.copy(ctx, item)
                            when {
                                !ok -> toast("Not connected to $mac")
                                item.text == null || item.truncated -> toast("Copying from $mac…")
                                Build.VERSION.SDK_INT < 33 -> toast("Copied")   // Android 13+ shows its own
                            }
                        },
                        onLongClick = { menuFor = item },
                    ),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            if (item.image != null) {
                                Image(
                                    item.image.asImageBitmap(), "Copied image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.heightIn(max = 140.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                                )
                            } else {
                                Text(item.text.orEmpty(), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (item.fromMac) Icons.Outlined.Laptop else Icons.Outlined.PhoneAndroid, null,
                                    Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    (if (item.fromMac) "From $mac" else "From this phone") + " · " +
                                        DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("Clear clipboard history?") },
            text = { Text("This removes the list on this phone and on $mac. What's on the clipboard right now stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    if (!ClipHistory.clearEverywhere()) toast("Not connected to $mac")
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    menuFor?.let { item ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(if (item.image != null) "Image" else "Text") },
            text = { Text("Put this on the clipboard of $mac or this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    menuFor = null
                    toast(if (ClipHistory.copyToMac(item)) "Copied on $mac" else "Not connected to $mac")
                }) { Text("Copy on $mac") }
            },
            dismissButton = {
                TextButton(onClick = {
                    menuFor = null
                    if (!ClipHistory.copy(ctx, item)) toast("Not connected to $mac")
                }) { Text("Copy here") }
            },
        )
    }
}
