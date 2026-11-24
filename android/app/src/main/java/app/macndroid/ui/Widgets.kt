package app.macndroid.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.macndroid.R
import app.macndroid.core.Computer
import app.macndroid.core.Link
import app.macndroid.core.Pause
import app.macndroid.core.Prefs
import app.macndroid.service.MacMusic
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Four home-screen widgets, in Material You colours, that adapt as they're resized:
 *  - [PauseWidget] (1×1): the logo itself shows whether sharing is on (full colour) or paused (dimmed, with a pause
 *    badge). Tap to switch.
 *  - [QuickWidget] (2×1): Pause / Resume and Send files.
 *  - [TypeWidget] (4×2): a box to type into and send to the computer's clipboard, and four actions: Pause, Send
 *    files, Send clipboard, Play on phone. Widgets can't hold a text field, so the box opens a small typing window
 *    over the home screen.
 *  - [MusicWidget] (4×2 and up): what's playing on the computer, with its controls, volume and Play on phone, or an
 *    empty state when nothing is.
 */
object Widgets {
    /** Redraw every widget (pausing, connecting, the computer's music changing…). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        Link.scope.launch {
            runCatching {
                PauseWidget().updateAll(app)
                QuickWidget().updateAll(app)
                TypeWidget().updateAll(app)
                MusicWidget().updateAll(app)
            }
        }
    }
}

// MARK: actions

class TogglePauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (Pause.active) Pause.resume(context) else Pause.pause(context, null)
        Widgets.refresh(context)
    }
}

/** The computer's music: play, pause, next, prev, vol_up, vol_down, play_here (Play on phone). */
class MusicAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val cmd = parameters[CMD] ?: return
        if (cmd == "play_here") Link.post(JSONObject().put("t", "play_on_phone")) else MacMusic.command(cmd)
    }

    companion object { val CMD = ActionParameters.Key<String>("cmd") }
}

private fun music(cmd: String) = actionRunCallback<MusicAction>(actionParametersOf(MusicAction.CMD to cmd))

private fun openApp(ctx: Context) = actionStartActivity(
    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun pickFiles(ctx: Context) = actionStartActivity(
    Intent(ctx, PickFilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun sendClipboard(ctx: Context) = actionStartActivity(
    Intent(ctx, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private fun typeText(ctx: Context) = actionStartActivity(
    Intent(ctx, TypeToComputerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY))

// MARK: pieces

@Composable
private fun Card(modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A round or pill-shaped button: an icon, and a label when there's room. */
@Composable
private fun Action(
    icon: Int, label: String, onClick: androidx.glance.action.Action, modifier: GlanceModifier = GlanceModifier,
    showLabel: Boolean = true, filled: Boolean = false,
) {
    val bg = if (filled) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer
    val fg = if (filled) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer
    Row(
        modifier.height(44.dp).background(bg).cornerRadius(22.dp).padding(horizontal = 12.dp).clickable(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(ImageProvider(icon), label, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(fg))
        if (showLabel) {
            Spacer(GlanceModifier.width(6.dp))
            Text(label, maxLines = 1, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun Label(text: String, size: Int = 14, bold: Boolean = false, dim: Boolean = false, lines: Int = 1) {
    Text(text, maxLines = lines, style = TextStyle(
        color = if (dim) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
        fontSize = size.sp, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal))
}

internal fun computerName() = Prefs.pairing.value?.macName ?: Computer.your

// MARK: 1×1: the logo shows on / paused

class PauseWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(57.dp, 57.dp), DpSize(110.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        GlanceTheme {
            val paused = Pause.active
            val big = LocalSize.current.height >= 100.dp
            Box(
                GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(28.dp)
                    .clickable(actionRunCallback<TogglePauseAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(
                            GlanceModifier.size(if (big) 64.dp else 48.dp)
                                .background(if (paused) GlanceTheme.colors.surfaceVariant else GlanceTheme.colors.primaryContainer)
                                .cornerRadius(if (big) 32.dp else 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                ImageProvider(R.drawable.ic_stat_logo),
                                if (paused) "Sharing paused. Tap to resume." else "Sharing is on. Tap to pause.",
                                GlanceModifier.size(if (big) 36.dp else 28.dp),
                                colorFilter = ColorFilter.tint(
                                    if (paused) GlanceTheme.colors.outline else GlanceTheme.colors.onPrimaryContainer),
                            )
                        }
                        if (paused) {
                            Box(
                                GlanceModifier.size(20.dp).background(GlanceTheme.colors.tertiary).cornerRadius(10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(ImageProvider(R.drawable.ic_w_pause), null, GlanceModifier.size(12.dp),
                                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onTertiary))
                            }
                        }
                    }
                    if (big) {
                        Spacer(GlanceModifier.height(8.dp))
                        Label(if (paused) "Paused" else "Sharing on", size = 13, bold = true)
                    }
                }
            }
        }
    }
}

class PauseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PauseWidget() }

// MARK: 2×1: pause and send files

class QuickWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 48.dp), DpSize(200.dp, 48.dp), DpSize(200.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        GlanceTheme {
            val paused = Pause.active
            val size = LocalSize.current
            val labels = size.width >= 190.dp
            Card {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    if (size.height >= 100.dp) {
                        Label(if (paused) "Sharing paused" else "Sharing with ${computerName()}", size = 13, dim = true)
                        Spacer(GlanceModifier.height(8.dp))
                    }
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Action(if (paused) R.drawable.ic_w_play else R.drawable.ic_w_pause, if (paused) "Resume" else "Pause",
                            actionRunCallback<TogglePauseAction>(), GlanceModifier.defaultWeight(), showLabel = labels, filled = paused)
                        Spacer(GlanceModifier.width(8.dp))
                        Action(R.drawable.ic_upload, "Send files", pickFiles(context), GlanceModifier.defaultWeight(), showLabel = labels)
                    }
                }
            }
        }
    }
}

class QuickWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = QuickWidget() }

// MARK: 4×2: type and send, plus four actions

class TypeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 60.dp), DpSize(250.dp, 110.dp), DpSize(320.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        GlanceTheme {
            val paused = Pause.active
            val size = LocalSize.current
            Card {
                Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    // The "text box": opens a small typing window (widgets can't take typing themselves).
                    Row(
                        GlanceModifier.fillMaxWidth().height(48.dp).background(GlanceTheme.colors.surfaceVariant)
                            .cornerRadius(24.dp).padding(start = 16.dp, end = 4.dp).clickable(typeText(context)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(GlanceModifier.defaultWeight()) { Label("Type to send to ${computerName()}", dim = true) }
                        Box(
                            GlanceModifier.size(40.dp).background(GlanceTheme.colors.primary).cornerRadius(20.dp)
                                .clickable(typeText(context)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(ImageProvider(R.drawable.ic_w_send), "Send", GlanceModifier.size(18.dp),
                                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary))
                        }
                    }
                    if (size.height >= 100.dp) {
                        Spacer(GlanceModifier.height(10.dp))
                        val labels = size.width >= 310.dp
                        Row(GlanceModifier.fillMaxWidth()) {
                            Action(if (paused) R.drawable.ic_w_play else R.drawable.ic_w_pause, if (paused) "Resume" else "Pause",
                                actionRunCallback<TogglePauseAction>(), GlanceModifier.defaultWeight(), showLabel = false, filled = paused)
                            Spacer(GlanceModifier.width(6.dp))
                            Action(R.drawable.ic_upload, "Files", pickFiles(context), GlanceModifier.defaultWeight(), showLabel = labels)
                            Spacer(GlanceModifier.width(6.dp))
                            Action(R.drawable.ic_clipboard, "Clipboard", sendClipboard(context), GlanceModifier.defaultWeight(), showLabel = labels)
                            Spacer(GlanceModifier.width(6.dp))
                            Action(R.drawable.ic_phone_handoff, "Play here", music("play_here"), GlanceModifier.defaultWeight(), showLabel = labels)
                        }
                    }
                }
            }
        }
    }
}

class TypeWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = TypeWidget() }

// MARK: 4×2+: the computer's music

class MusicWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(320.dp, 110.dp), DpSize(250.dp, 200.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        GlanceTheme {
            val track = MacMusic.current
            val size = LocalSize.current
            Card(GlanceModifier.clickable(openApp(context))) {
                when {
                    !Prefs.macMusic.value -> Empty("Music on ${Computer.your} is off", "Turn it on in Mac n Droid.")
                    !Link.isConnected -> Empty("Not connected", "Open Mac n Droid on ${Computer.your}.")
                    track == null -> Empty("Nothing playing on ${computerName()}", "Play something there and it shows up here.")
                    else -> Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val art = track.art
                            Box(
                                GlanceModifier.size(if (size.height >= 190.dp) 88.dp else 56.dp)
                                    .background(GlanceTheme.colors.secondaryContainer).cornerRadius(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (art != null) Image(ImageProvider(art), null, GlanceModifier.fillMaxSize().cornerRadius(12.dp),
                                    contentScale = androidx.glance.layout.ContentScale.Crop)
                                else Image(ImageProvider(R.drawable.ic_w_music), null, GlanceModifier.size(28.dp),
                                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
                            }
                            Spacer(GlanceModifier.width(12.dp))
                            Column(GlanceModifier.defaultWeight()) {
                                Label(track.title, size = 15, bold = true, lines = if (size.height >= 190.dp) 2 else 1)
                                Label(listOf(track.artist, "${track.app} on ${computerName()}").filter { it.isNotBlank() }
                                    .joinToString(" · "), size = 12, dim = true)
                            }
                        }
                        Spacer(GlanceModifier.height(10.dp))
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Action(R.drawable.ic_w_prev, "Previous", music("prev"), GlanceModifier.defaultWeight(), showLabel = false)
                            Spacer(GlanceModifier.width(6.dp))
                            Action(if (track.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play, if (track.playing) "Pause" else "Play",
                                music(if (track.playing) "pause" else "play"), GlanceModifier.defaultWeight(), showLabel = false, filled = true)
                            Spacer(GlanceModifier.width(6.dp))
                            Action(R.drawable.ic_w_next, "Next", music("next"), GlanceModifier.defaultWeight(), showLabel = false)
                            if (size.width >= 310.dp && track.volume >= 0) {
                                Spacer(GlanceModifier.width(6.dp))
                                Action(R.drawable.ic_volume_down, "Volume down", music("vol_down"), GlanceModifier.defaultWeight(), showLabel = false)
                                Spacer(GlanceModifier.width(6.dp))
                                Action(R.drawable.ic_volume_up, "Volume up", music("vol_up"), GlanceModifier.defaultWeight(), showLabel = false)
                            }
                            if (Link.PLAY_ON in Link.computerCaps) {
                                Spacer(GlanceModifier.width(6.dp))
                                Action(R.drawable.ic_phone_handoff, "Play on phone", music("play_here"), GlanceModifier.defaultWeight(), showLabel = false)
                            }
                        }
                        if (size.height >= 190.dp && Link.PLAY_ON in Link.computerCaps) {
                            Spacer(GlanceModifier.height(8.dp))
                            Action(R.drawable.ic_phone_handoff, "Play on this phone", music("play_here"), GlanceModifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Empty(title: String, body: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_w_music), null, GlanceModifier.size(28.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
            Spacer(GlanceModifier.height(6.dp))
            Label(title, bold = true)
            Label(body, size = 12, dim = true, lines = 2)
        }
    }
}

class MusicWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = MusicWidget() }

