package app.macndroid.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.macndroid.core.Computer
import app.macndroid.core.Pause
import java.util.Date

/** "Until 6:38 PM", "Until tomorrow, 2:00 AM", or "Until you resume". */
fun pauseEndText(ctx: Context, until: Long): String {
    if (until == Pause.FOREVER) return "Until you resume"
    val time = DateFormat.getTimeFormat(ctx).format(Date(until))
    return when {
        DateUtils.isToday(until) -> "Until $time"
        DateUtils.isToday(until - DateUtils.DAY_IN_MILLIS) -> "Until tomorrow, $time"
        else -> "Until " + DateUtils.formatDateTime(ctx, until, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
    }
}

/** Choose how long to pause. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseSheet(macName: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    fun choose(ms: Long?) { Pause.pause(ctx, ms); onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Pause sharing", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "This phone stays connected to $macName, but nothing is shared: no notifications, clipboard, " +
                    "photos, messages, music or screen. When you resume, everything you had on comes back as it was.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Column(Modifier.clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PauseOption(Icons.Outlined.HourglassTop, "For 1 hour", pauseEndText(ctx, now + Pause.HOUR)) { choose(Pause.HOUR) }
                PauseOption(Icons.Outlined.HourglassBottom, "For 3 hours", pauseEndText(ctx, now + 3 * Pause.HOUR)) { choose(3 * Pause.HOUR) }
                PauseOption(Icons.Outlined.AllInclusive, "Until I resume", "Resume from here, the notification or Quick Settings") { choose(null) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Find my phone keeps working while paused.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PauseOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.tileRow().clickable(role = Role.Button, onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Takes the status card's place while paused: what's going on, until when, and Resume. */
@Composable
internal fun PausedCard(until: Long, macName: String) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(28.dp),
        // Inverse surface: always a near-white / near-black pair, so it reads clearly with any wallpaper
        // palette (the tinted containers turn a muddy grey with monochrome ones).
        colors = CardDefaults.cardColors(containerColor = scheme.inverseSurface, contentColor = scheme.inverseOnSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = scheme.surface, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PauseCircle, null, Modifier.size(28.dp), tint = scheme.onSurface)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sharing paused", style = MaterialTheme.typography.titleLarge)
                    Text(pauseEndText(ctx, until), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Still connected to $macName. Nothing is shared until you resume, and your settings come back as they were.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.inverseOnSurface.copy(alpha = 0.8f))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { Pause.resume(ctx) },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.surface, contentColor = scheme.onSurface),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Resume sharing")
            }
        }
    }
}

/**
 * Dims [content] while paused and catches every tap on it, so nothing changes by accident; [onBlocked] asks
 * to resume instead.
 */
@Composable
internal fun PausedGate(paused: Boolean, onBlocked: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (paused) 0.38f else 1f, label = "dim")
    Box(modifier) {
        Box(Modifier.alpha(alpha).then(if (paused) Modifier.semantics { disabled() } else Modifier)) { content() }
        if (paused) Box(
            Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "Resume sharing", onClick = onBlocked,
            ),
        )
    }
}

/** "Sharing is paused — resume to continue?" */
@Composable
internal fun ResumeDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PauseCircle, null) },
        title = { Text("Sharing is paused") },
        text = { Text("Resume sharing with ${Computer.your} to use this. Everything you had on comes back as it was.") },
        confirmButton = { TextButton(onClick = { onDismiss(); Pause.resume(ctx) }) { Text("Resume") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
