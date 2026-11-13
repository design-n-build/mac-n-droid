package app.macndroid.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.macndroid.core.Computer
import app.macndroid.core.Prefs
import app.macndroid.service.Controls
import app.macndroid.service.ScreenshotWatcher
import app.macndroid.service.SmsBridge

// MARK: Local network (Android 17+)

internal object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    val applies get() = Build.VERSION.SDK_INT >= 37

    /** Checks the app-op too: for apps not yet targeting 17, "Don't allow" shows up only there. */
    fun allowed(ctx: Context): Boolean {
        if (!applies) return true
        if (ContextCompat.checkSelfPermission(ctx, PERMISSION) != PackageManager.PERMISSION_GRANTED) return false
        // The permission can read as granted while its app-op says "ignore" (shown in Settings as Don't allow).
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return true
        val op = android.app.AppOpsManager.permissionToOp(PERMISSION) ?: "android:access_local_network"
        val mode = runCatching { ops.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), ctx.packageName) }
            .getOrDefault(android.app.AppOpsManager.MODE_ALLOWED)
        return mode == android.app.AppOpsManager.MODE_ALLOWED || mode == android.app.AppOpsManager.MODE_FOREGROUND
    }

    fun openAppSettings(ctx: Context) {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Asks for local network access; if Android won't ask again, opens App info → Permissions. */
@Composable
internal fun rememberLocalNetworkRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val ok = LocalNetwork.allowed(ctx)
        if (!ok) LocalNetwork.openAppSettings(ctx)
        else app.macndroid.core.Link.kick()
        onResult(ok)
    }
    return { launcher.launch(LocalNetwork.PERMISSION) }
}

/** Shown wherever the app would otherwise just keep "Searching". */
@Composable
internal fun LocalNetworkCard(onAllow: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WifiOff, null)
                Spacer(Modifier.width(12.dp))
                Text("Allow local network access", style = MaterialTheme.typography.titleMedium)
            }
            Text("Android is blocking Mac n Droid from your Wi-Fi network, so it can't reach ${Computer.your}. " +
                "Allow it to connect to devices on your local network. If no prompt appears, open Permissions → Nearby devices (or Local network) → Allow.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

// MARK: What's on

/** Everything optional the app can use, read fresh each time the app comes back to the front. */
internal data class Features(
    val controls: Boolean,
    val listener: Boolean,
    val postNotifications: Boolean,
    val battery: Boolean,
    val photos: Boolean,
    val sms: Boolean,
    /** Android 17+ can block the app from the local network; then it can't reach the computer at all. */
    val localNetwork: Boolean = true,
) {
    val notifications get() = listener && postNotifications
    val media get() = photos && sms

    companion object {
        fun read(ctx: Context): Features {
            val p = PermissionState.read(ctx)
            return Features(
                controls = Controls.isEnabled(ctx),
                listener = p.listener,
                postNotifications = p.postNotifications,
                battery = p.battery,
                photos = app.macndroid.service.Gallery.permissionAccess(ctx) != "none",
                sms = SmsBridge.hasPermission(ctx),
                localNetwork = LocalNetwork.allowed(ctx),
            )
        }
    }
}

@Composable
internal fun rememberFeatures(): State<Features> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Features.read(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.value = Features.read(ctx) }
    return state
}

/** The permission requests both the onboarding and the checklist use. */
internal class FeatureActions(
    val controls: () -> Unit,
    val notifications: () -> Unit,
    val appNotifications: () -> Unit,
    val media: () -> Unit,
    val battery: () -> Unit,
    val localNetwork: () -> Unit,
)

@Composable
internal fun rememberFeatureActions(features: MutableState<Features>, onControls: () -> Unit): FeatureActions {
    val ctx = LocalContext.current
    val refresh = {
        features.value = Features.read(ctx)
        runCatching { ctx.startService(Intent(ctx, app.macndroid.service.SyncService::class.java)
            .setAction(app.macndroid.service.SyncService.ACTION_REFRESH)) }
    }
    val notifThenListener = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        if (!features.value.listener) openListenerSettings(ctx)
    }
    val postOnly = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ScreenshotWatcher.hasPermission(ctx)) Prefs.setScreenshotsToMac(true)
        if (SmsBridge.hasPermission(ctx)) Prefs.setSmsOnMac(true)
        app.macndroid.service.Gallery.reportAccess(ctx)
        refresh()
    }
    val lan = rememberLocalNetworkRequest { refresh() }
    val needsPost = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    return FeatureActions(
        controls = onControls,
        notifications = {
            if (needsPost) notifThenListener.launch(Manifest.permission.POST_NOTIFICATIONS) else openListenerSettings(ctx)
        },
        appNotifications = { if (Build.VERSION.SDK_INT >= 33) postOnly.launch(Manifest.permission.POST_NOTIFICATIONS) },
        media = { media.launch(app.macndroid.service.Gallery.permissions + SmsBridge.permissions) },
        battery = { requestBatteryExemption(ctx) },
        localNetwork = lan,
    )
}

// MARK: The disclosure shown before Android's Accessibility settings open

/**
 * Says plainly what the accessibility service does, what it looks at and where data goes, and asks for
 * agreement before opening Android's settings. Also shown (as "how to turn it off") when it's already on.
 */
@Composable
internal fun ControlsConsentDialog(on: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun openSettings() { onDismiss(); ctx.startActivity(Controls.settingsIntent()) }
    if (on) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.TouchApp, null) },
            title = { Text("Turn off clipboard and controls?") },
            text = {
                Text("In the next screen, open ${Controls.NAME} and turn it off.\n\n" +
                    "Sending files, copying by hand with Send clipboard and watch-only mirroring keep working. " +
                    "Links from ${Computer.your} will arrive as notifications instead of opening by themselves.")
            },
            confirmButton = { TextButton(onClick = ::openSettings) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.TouchApp, null) },
        title = { Text("Clipboard and controls") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Mac n Droid uses Android's Accessibility service for these optional features:")
                Bullet("Copy here, paste on ${Computer.your}: when you tap Copy, what you copied is sent to ${Computer.your}.")
                Bullet("Use this phone from ${Computer.your}: while you mirror this screen, your clicks and typing there become taps and typing here.")
                Bullet("Links from ${Computer.your} open right away, music you start from ${Computer.your} plays, and a timer or map stays on ${Computer.your} while that app is open here.")
                Text("What it looks at", fontWeight = FontWeight.SemiBold)
                Text("Only the label of what you tap (to tell whether it's Copy), which app is open, and the text field you type into from ${Computer.your}. " +
                    "It taps and types only when you do so from ${Computer.your}, never on its own.")
                Text("Where your data goes", fontWeight = FontWeight.SemiBold)
                Text("Only to your own paired ${Computer.word}, over your Wi-Fi, encrypted. Nothing is stored, and nothing is sent to us or anyone else. " +
                    "Passwords copied from password managers are never sent.")
                Text("In the next screen, open Installed apps → ${Controls.NAME} and turn it on. You can turn it off at any time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = ::openSettings) { Text("Agree and continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•  ")
        Text(text)
    }
}

// MARK: Onboarding (before pairing)

/** Step 1: what the app does. Step 2: choose optional features. Step 3 is the pairing screen. */
@Composable
internal fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    val features = remember { mutableStateOf(Features(false, false, false, false, false, false)) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { features.value = Features.read(ctx) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { features.value = Features.read(ctx) }
    var consent by remember { mutableStateOf(false) }
    val actions = rememberFeatureActions(features) { consent = true }
    var backProgress by remember { mutableFloatStateOf(0f) }
    androidx.activity.compose.PredictiveBackHandler(enabled = step > 1) { events ->
        try { events.collect { backProgress = it.progress }; step-- } finally { backProgress = 0f }
    }

    Scaffold(
        topBar = { StepBar(step) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = { if (step == 1) step = 2 else onDone() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (step == 1) "Get started" else "Continue to pairing")
                }
                if (step > 1) TextButton(onClick = { step-- }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Back")
                }
            }
        },
    ) { pad ->
        // Steps slide like pages: forward goes left, back comes in from the left.
        androidx.compose.animation.AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.slideInHorizontally(spring(0.9f, Spring.StiffnessMediumLow)) { it * dir / 3 } +
                    androidx.compose.animation.fadeIn(tween(220, 60))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(spring(0.9f, Spring.StiffnessMediumLow)) { -it * dir / 3 } +
                        androidx.compose.animation.fadeOut(tween(120)))
            },
            modifier = Modifier.padding(pad).predictiveBack(backProgress),
            label = "step",
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (s == 1) Welcome() else MakeItYours(features.value, actions)
            }
        }
    }
    if (consent) ControlsConsentDialog(on = features.value.controls) { consent = false }
}

@Composable
private fun StepBar(step: Int) {
    Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mac n Droid", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("Step $step of 3", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        StepProgress(step)
    }
}

@Composable
internal fun StepProgress(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..3).forEach { i ->
            // Each segment fills from the left with a soft spring when its step is reached.
            val fill by animateFloatAsState(if (i <= step) 1f else 0f, spring(0.8f, Spring.StiffnessLow), label = "fill")
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun Welcome() {
    Spacer(Modifier.height(8.dp))
    HeaderIcon(Icons.Outlined.Devices)
    Text("Your phone and computer, together", style = MaterialTheme.typography.headlineMedium)
    Text("See notifications and calls, copy and paste, send files, text, and use this phone from your Mac or PC.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Point(Icons.Outlined.Wifi, "Stays on your Wi-Fi", "Your phone talks straight to your computer. Nothing goes through the cloud.")
    Point(Icons.Outlined.Lock, "Encrypted", "Everything is encrypted with a key made when you pair. Only your computer can read it.")
    Point(Icons.Outlined.Tune, "You choose", "Every permission is optional. Turn on only what you want to use.")
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MakeItYours(f: Features, a: FeatureActions) {
    Spacer(Modifier.height(8.dp))
    HeaderIcon(Icons.Outlined.Tune)
    Text("Make it yours", style = MaterialTheme.typography.headlineMedium)
    Text("Turn on only the features you want. These permissions are optional and aren't needed to pair. You can change them later on the Home tab.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (LocalNetwork.applies) FeatureCard(Icons.Outlined.Wifi, "Connect on your Wi-Fi",
        "Local network access lets this phone find and connect to your computer on the same Wi-Fi. This one is needed to connect; nothing leaves your network.",
        f.localNetwork, a.localNetwork)
    FeatureCard(Icons.Outlined.TouchApp, "Clipboard and controls",
        "Accessibility lets you copy here and paste on your computer, use this phone from your computer, and open links from it right away.",
        f.controls, a.controls)
    FeatureCard(Icons.Outlined.Notifications, "Phone notifications",
        "Notification access shows this phone's notifications and calls on your computer. You choose which apps to share.",
        f.notifications, a.notifications)
    FeatureCard(Icons.Outlined.PhotoLibrary, "Photos, videos and messages",
        "See this phone's photos and videos on your computer, paste new screenshots there, and read and reply to texts. Android asks for photos and videos, SMS and contacts separately; you can decline any.",
        f.media, a.media)
    FeatureCard(Icons.Outlined.BatteryChargingFull, "Stay connected",
        "Lets Mac n Droid run in the background so notifications reach your computer straight away.",
        f.battery, a.battery)
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, body: String, on: Boolean, enable: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // When a permission is allowed: the icon morphs into a ✓, the button becomes "Enabled", the card tints, and a
    // success tap. Only for a real change while the screen is open, not for things that were already on.
    var wasOn by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on && !wasOn) app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Success)
        wasOn = on
    }
    val container by androidx.compose.animation.animateColorAsState(
        if (on) scheme.secondaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh, tween(400), label = "card")
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp).animateContentSize(spring(0.8f, Spring.StiffnessMediumLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.animation.AnimatedContent(
                    targetState = on,
                    transitionSpec = {
                        (androidx.compose.animation.scaleIn(spring(0.45f, Spring.StiffnessMedium), initialScale = 0.3f) +
                            androidx.compose.animation.fadeIn(tween(150))) togetherWith
                            (androidx.compose.animation.scaleOut(tween(150), targetScale = 0.3f) + androidx.compose.animation.fadeOut(tween(150)))
                    },
                    label = "icon",
                ) { isOn ->
                    if (isOn) Icon(Icons.Outlined.CheckCircle, "On", tint = scheme.primary) else Icon(icon, null)
                }
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            androidx.compose.animation.AnimatedContent(
                targetState = on,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(tween(200, 100)) + androidx.compose.animation.scaleIn(spring(0.6f), initialScale = 0.85f)) togetherWith
                        androidx.compose.animation.fadeOut(tween(100))
                },
                label = "action",
            ) { isOn ->
                if (isOn) EnabledChip() else OutlinedButton(onClick = enable, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Enable") }
            }
        }
    }
}

@Composable
private fun EnabledChip() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(6.dp))
            Text("Enabled", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

// MARK: Setup checklist (Home)

/** What's on and what isn't, on the Home tab. Open while something you'd likely want is off. */
@Composable
internal fun SetupChecklist(f: Features, a: FeatureActions) {
    data class Item(val icon: ImageVector, val title: String, val on: Boolean, val onText: String, val offText: String, val action: () -> Unit)
    val rows = buildList {
        if (LocalNetwork.applies) add(Item(Icons.Outlined.Wifi, "Local network", f.localNetwork,
            "On: can reach ${Computer.your} on your Wi-Fi", "Off: can't connect to ${Computer.your} at all", a.localNetwork))
        add(Item(Icons.Outlined.TouchApp, "Clipboard and controls", f.controls,
            "On: copying, phone control, links and music from ${Computer.your}",
            "Off: copy by hand, mirroring is watch-only, links arrive as notifications", a.controls))
        add(Item(Icons.Outlined.NotificationsActive, "Notification access", f.listener,
            "On: notifications and calls show on ${Computer.your}",
            "Off: ${Computer.your} can't show this phone's notifications. If the switch is greyed out, open App info → ⋮ → Allow restricted settings first.",
            a.notifications))
        if (Build.VERSION.SDK_INT >= 33) add(Item(Icons.Outlined.NotificationAdd, "App notifications", f.postNotifications,
            "On: connection status and received files", "Off: no status or file notifications here", a.appNotifications))
        add(Item(Icons.Outlined.BatteryChargingFull, "Run in background", f.battery,
            "On: stays connected", "Off: Android may cut the connection to save battery", a.battery))
        add(Item(Icons.Outlined.PhotoLibrary, "Photos, videos and messages", f.media,
            "On: photos, videos, screenshots and texts on ${Computer.your}", "Off: optional, for photos, videos and texts on ${Computer.your}", a.media))
    }
    val off = rows.filter { !it.on }
    var open by rememberSaveable { mutableStateOf(!(f.listener && f.battery && f.controls && f.localNetwork)) }
    val scheme = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            ListItem(
                modifier = Modifier.clickableRow { open = !open },
                leadingContent = { Icon(if (off.isEmpty()) Icons.Outlined.CheckCircle else Icons.Outlined.Checklist, null) },
                headlineContent = { Text("Setup checklist", style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Text(if (off.isEmpty()) "Everything is on" else "Not on: " + off.joinToString(" · ") { it.title })
                },
                trailingContent = {
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Hide" else "Show")
                },
                colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainerHigh),
            )
            AnimatedVisibility(open) {
                Column {
                    rows.forEach { r ->
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
                        ListItem(
                            leadingContent = { Icon(r.icon, null, tint = if (r.on) scheme.primary else scheme.onSurfaceVariant) },
                            headlineContent = { Text(r.title) },
                            supportingContent = { Text(if (r.on) r.onText else r.offText) },
                            trailingContent = {
                                if (r.on) Icon(Icons.Outlined.CheckCircle, "On", tint = scheme.primary)
                                else FilledTonalButton(onClick = r.action) { Text("Enable") }
                            },
                            colors = ListItemDefaults.colors(containerColor = scheme.surfaceContainerHigh),
                        )
                    }
                }
            }
        }
    }
}
