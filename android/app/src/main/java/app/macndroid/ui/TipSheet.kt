package app.macndroid.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.macndroid.R
import app.macndroid.service.FilesTileService
import app.macndroid.service.SyncService

enum class Tip(val title: String, val summary: String, val icon: ImageVector) {
    Share("Share anything to your Mac", "Files, photos and links from any app", Icons.Outlined.Share),
    Tile("Quick Settings tile", "Send photos and files in one tap", Icons.Outlined.Dashboard),
    Ring("Find your phone", "Ring it from your Mac, even on silent", Icons.Outlined.NotificationsActive),
    Mirror("Use your phone from your Mac", "See and control this screen in a Mac window", Icons.Outlined.ScreenShare),
    Webcam("Phone as webcam", "Use this camera in FaceTime, Zoom or Meet", Icons.Outlined.Videocam),
}

/** Bottom sheet with a small visual preview of the feature, the steps, and a way to try it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipSheet(tip: Tip, macName: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(20.dp)) {
                    when (tip) {
                        Tip.Share -> SharePreview(macName)
                        Tip.Tile -> TilePreview(macName)
                        Tip.Ring -> RingPreview(macName)
                        Tip.Mirror -> FlowPreview(Icons.Outlined.PhoneAndroid, Icons.Outlined.LaptopMac, "Mirror")
                        Tip.Webcam -> FlowPreview(Icons.Outlined.Videocam, Icons.Outlined.LaptopMac, "USB")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(tip.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            steps(tip, macName).forEachIndexed { i, s -> Step(i + 1, s) }
            Spacer(Modifier.height(24.dp))
            when (tip) {
                Tip.Share -> Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
                Tip.Tile -> {
                    fun add(service: Class<*>, label: Int, icon: Int) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                ComponentName(ctx, service), ctx.getString(label),
                                AndroidIcon.createWithResource(ctx, icon), ctx.mainExecutor,
                            ) { }
                        } else {
                            Toast.makeText(ctx, "Swipe down twice, tap ✎ and drag the tile in", Toast.LENGTH_LONG).show()
                        }
                    }
                    Button(
                        onClick = { add(FilesTileService::class.java, R.string.files_tile_label, R.drawable.ic_upload) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Add tile to Quick Settings") }
                }
                Tip.Ring -> Button(
                    onClick = {
                        ctx.startService(Intent(ctx, SyncService::class.java).setAction(SyncService.ACTION_TEST_RING))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Outlined.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text("Test ring (3 seconds)") }
                Tip.Mirror, Tip.Webcam -> Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
            }
        }
    }
}

private fun steps(tip: Tip, mac: String) = when (tip) {
    Tip.Share -> listOf(
        "In any app (Photos, Chrome, Files…), tap Share.",
        "Choose Open on $mac to open a link in its browser, or Send to $mac to send it.",
        "Sent files land in Downloads on $mac; sent text and links land on its clipboard.",
    )
    Tip.Tile -> listOf(
        "Add the tile below, then swipe down from the top of the screen.",
        "Tap Send files to Mac and pick photos or files.",
        "They land in Downloads on $mac.",
    )
    Tip.Ring -> listOf(
        "On $mac, click the phone icon in the menu bar.",
        "Click Ring. Your phone plays an alarm at full volume, even on silent.",
        "Tap the notification on your phone to stop it.",
    )
    Tip.Mirror -> listOf(
        "On $mac, click Mirror Screen in the menu bar or the Overview page.",
        "On your phone, tap Start. Android asks every time, for your privacy.",
        "Click, scroll and type in the Mac window to use your phone. Controls need \"Control from Mac\" turned on.",
    )
    Tip.Webcam -> listOf(
        "Connect your phone to $mac with a USB cable.",
        "On your phone, tap the \"Charging this device via USB\" notification and choose Webcam.",
        "In FaceTime, Zoom, Meet or Photo Booth on $mac, pick your phone as the camera. Needs Android 14 or later.",
    )
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// MARK: Mini previews (drawn with Material components)

@Composable
private fun SharePreview(mac: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Share", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            ShareTarget(Icons.Outlined.Email, "Gmail", false)
            ShareTarget(Icons.Outlined.Chat, "Messages", false)
            ShareTarget(Icons.Outlined.LaptopMac, "Send to $mac", true)
            ShareTarget(Icons.Outlined.CloudUpload, "Drive", false)
        }
        Spacer(Modifier.height(16.dp))
        Text("→ $mac", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ShareTarget(icon: ImageVector, label: String, highlighted: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier.size(52.dp)
                .background(if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                .then(if (highlighted) Modifier.border(3.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun TilePreview(mac: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QsTile(Icons.Outlined.Wifi, "Internet", "Home Wi-Fi", false)
            QsTile(Icons.Outlined.FlashlightOn, "Flashlight", "Off", false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QsTile(Icons.Outlined.UploadFile, "Send files", mac, true)
            QsTile(Icons.Outlined.Bluetooth, "Bluetooth", "On", false)
        }
    }
}

@Composable
private fun QsTile(icon: ImageVector, title: String, sub: String, active: Boolean) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.width(150.dp).height(60.dp).background(bg, RoundedCornerShape(30.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.75f), maxLines = 1)
        }
    }
}

@Composable
private fun RingPreview(mac: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.LaptopMac, null, Modifier.size(64.dp))
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VolumeUp, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("Ring", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(20.dp))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape))
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape))
            Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FlowPreview(from: ImageVector, to: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Icon(from, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Icon(to, null, Modifier.size(72.dp))
    }
}
