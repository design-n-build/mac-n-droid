package app.macndroid.ui

import app.macndroid.core.Computer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.macndroid.core.Link
import app.macndroid.core.Pairing
import app.macndroid.core.Prefs
import app.macndroid.service.ClipboardSync
import app.macndroid.service.SyncService
import app.macndroid.service.Transfers
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {
    private val pendingLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) animateSplashExit()
        pendingLink.value = intent?.dataString
        SyncService.start(this)
        setContent {
            MacNDroidTheme {
                val pairing by Prefs.pairing.collectAsState()
                val onboarded by Prefs.onboarded.collectAsState()
                var link by pendingLink
                var celebrate by rememberSaveable { mutableStateOf(false) }
                if (pairing == null && !onboarded && link == null) {
                    OnboardingScreen(onDone = { Prefs.setOnboarded(true) })
                } else if (pairing == null) {
                    PairScreen(initialLink = link, onPaired = { link = null; celebrate = true }, onBack = { Prefs.setOnboarded(false) })
                } else if (celebrate) {
                    PairedScreen(pairing?.macName ?: Computer.word, onDone = { celebrate = false })
                } else {
                    AppShell()
                }
            }
        }
    }

    @androidx.annotation.RequiresApi(31)
    private fun animateSplashExit() {
        splashScreen.setOnExitAnimationListener { splash ->
            val icon = splash.iconView
            val reduced = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            if (icon == null || reduced) { splash.remove(); return@setOnExitAnimationListener }
            icon.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    icon.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(260)
                        .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
                    splash.animate().alpha(0f).setStartDelay(60).setDuration(260).withEndAction { splash.remove() }.start()
                }.start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { pendingLink.value = it }
        if (Prefs.pairing.value != null && intent.dataString != null) {
            Pairing.parse(intent.dataString!!)?.let { Prefs.savePairing(it); Link.kick(); SyncService.start(this) }
        }
    }

    /** While our UI is focused we can read the clipboard directly. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ClipboardSync.isAutoEnabled(this)) ClipboardSync.sendCurrent(this, auto = true)
    }
}

// MARK: Pairing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairScreen(initialLink: String?, onPaired: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }

    fun accept(raw: String) {
        val p = Pairing.parse(raw)
        if (p == null) {
            error = "That isn't a Mac n Droid pairing code."
        } else {
            Prefs.savePairing(p)
            SyncService.start(ctx)
            onPaired()
        }
    }

    LaunchedEffect(initialLink) { initialLink?.let(::accept) }
    // Asked for in onboarding step 2; if it was skipped, Android's own prompt comes up here instead.
    val allowLan = rememberLocalNetworkRequest()
    LaunchedEffect(Unit) { if (!LocalNetwork.allowed(ctx)) allowLan() }

    var backProgress by remember { mutableFloatStateOf(0f) }
    androidx.activity.compose.PredictiveBackHandler { events ->
        try { events.collect { backProgress = it.progress }; onBack() } finally { backProgress = 0f }
    }
    Scaffold(modifier = Modifier.predictiveBack(backProgress), topBar = {
        Column {
            TopAppBar(
                title = { Text("Mac n Droid") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    Text("Step 3 of 3", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
                },
            )
            Box(Modifier.padding(horizontal = 24.dp)) { StepProgress(3) }
        }
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Devices, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("Connect to ${Computer.your}", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Open Mac n Droid on ${Computer.your} and choose Pair a Phone. Then scan the QR code it shows.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    error = null
                    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(ctx, opts).startScan()
                        .addOnSuccessListener { code -> code.rawValue?.let(::accept) }
                        .addOnFailureListener { error = "The scanner couldn't start. Enter the pairing link instead." }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showManual = true }) { Text("Enter pairing link instead") }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Your phone and ${Computer.word} must be on the same Wi-Fi network. Nothing goes through the cloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showManual) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("Enter pairing link") },
            text = {
                Column {
                    Text("On ${Computer.your}, click Copy Pairing Link and send it to this phone, then paste it here.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("macndroid://pair?…") })
                }
            },
            confirmButton = { TextButton(onClick = { showManual = false; accept(text) }, enabled = text.isNotBlank()) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("Cancel") } },
        )
    }
}

// MARK: Shell with bottom navigation

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Files("Files", Icons.Outlined.Folder, Icons.Filled.Folder),
    Clipboard("Clipboard", Icons.Outlined.ContentPaste, Icons.Filled.ContentPaste),
    Notifications("Notifications", Icons.Outlined.Notifications, Icons.Filled.Notifications),
}

@Composable
private fun AppShell() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val pausedUntil by app.macndroid.core.Pause.until.collectAsState()
    var askResume by remember { mutableStateOf(false) }
    val transfers by Transfers.items.collectAsState()
    val active = transfers.count { it.state == app.macndroid.service.TransferItem.State.Active }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.Files && active > 0) Badge { Text("$active") } }) {
                                Icon(if (tab == t) t.selectedIcon else t.icon, null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { outer ->
        Box(Modifier.padding(bottom = outer.calculateBottomPadding())) {
            if (tab == Tab.Home) HomeScreen()
            else PausedGate(pausedUntil != 0L, onBlocked = { askResume = true }) {
                when (tab) {
                    Tab.Notifications -> NotificationsScreen()
                    Tab.Clipboard -> ClipboardScreen()
                    else -> FilesScreen()
                }
            }
        }
    }
    if (askResume) ResumeDialog { askResume = false }
}

// MARK: Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val status by Link.status.collectAsState()
    val pairing by Prefs.pairing.collectAsState()
    val clip by Prefs.receiveClipboard.collectAsState()
    val shots by Prefs.screenshotsToMac.collectAsState()
    val haptics by Prefs.haptics.collectAsState()
    val sounds by Prefs.sounds.collectAsState()
    val leftBehind by Prefs.leftBehind.collectAsState()
    val macMusic by Prefs.macMusic.collectAsState()
    val smsOn by Prefs.smsOnMac.collectAsState()
    var smsAllowed by remember { mutableStateOf(app.macndroid.service.SmsBridge.hasPermission(ctx)) }

    var shotsAllowed by remember { mutableStateOf(app.macndroid.service.ScreenshotWatcher.hasPermission(ctx)) }
    val features = remember { mutableStateOf(Features.read(ctx)) }
    val controls = features.value.controls
    var explainControls by remember { mutableStateOf(false) }
    val featureActions = rememberFeatureActions(features) { explainControls = true }
    val allowLocalNetwork = rememberLocalNetworkRequest { features.value = Features.read(ctx) }
    var menu by remember { mutableStateOf(false) }
    val pausedUntil by app.macndroid.core.Pause.until.collectAsState()
    val paused = pausedUntil != 0L
    var pauseSheet by remember { mutableStateOf(false) }
    var askResume by remember { mutableStateOf(false) }
    val gate = { askResume = true }
    var confirmUnpair by remember { mutableStateOf(false) }
    var openTip by remember { mutableStateOf<Tip?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        features.value = Features.read(ctx)
        shotsAllowed = app.macndroid.service.ScreenshotWatcher.hasPermission(ctx)
        smsAllowed = app.macndroid.service.SmsBridge.hasPermission(ctx)
    }
    fun refreshService() = runCatching {
        ctx.startService(Intent(ctx, SyncService::class.java).setAction(SyncService.ACTION_REFRESH))
    }
    val photosPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        shotsAllowed = ok
        if (ok) Prefs.setScreenshotsToMac(true)
        refreshService()
    }

    val galleryOn by Prefs.galleryOnMac.collectAsState()
    var galleryAllowed by remember { mutableStateOf(app.macndroid.service.Gallery.permissionAccess(ctx) != "none") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        galleryAllowed = app.macndroid.service.Gallery.permissionAccess(ctx) != "none"
    }
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        galleryAllowed = app.macndroid.service.Gallery.permissionAccess(ctx) != "none"
        if (galleryAllowed) Prefs.setGalleryOnMac(true)
        app.macndroid.service.Gallery.reportAccess(ctx)
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        smsAllowed = app.macndroid.service.SmsBridge.hasPermission(ctx)
        if (smsAllowed) Prefs.setSmsOnMac(true)
        refreshService()
    }

    var flown by remember { mutableIntStateOf(0) }
    var chip by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val macLabel = pairing?.macName ?: Computer.word
    fun sendClipboard() {
        // The app is in front, so it can read the clipboard itself.
        val msg = when (ClipboardSync.sendCurrent(ctx, auto = false)) {
            ClipboardSync.Result.SentText -> "Sent to $macLabel"
            ClipboardSync.Result.SentImage -> "Image sent to $macLabel"
            ClipboardSync.Result.Empty -> "Clipboard is empty"
            ClipboardSync.Result.NotConnected -> "Not connected to ${Computer.your}"
            ClipboardSync.Result.Duplicate -> "Already on $macLabel"
            ClipboardSync.Result.Sensitive -> "Not sent: marked as sensitive"
            ClipboardSync.Result.Paused -> "Sharing is paused"
        }
        chip = (chip?.first ?: 0) + 1 to msg
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { SyncService.sendUris(ctx, uris); flown++ }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) { SyncService.sendFolder(ctx, tree); flown++ }
    }
    var sendMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Mac n Droid") },
                actions = {
                    if (!paused) IconButton(onClick = { pauseSheet = true }) { Icon(Icons.Outlined.PauseCircle, "Pause sharing") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; Link.kick() },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                        DropdownMenuItem(text = { Text("Unpair ${Computer.word}") }, onClick = { menu = false; confirmUnpair = true },
                            leadingIcon = { Icon(Icons.Outlined.LinkOff, null) })
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.animation.AnimatedContent(paused, label = "pause") { p ->
                        if (p) PausedCard(pausedUntil, macLabel) else StatusCard(status, macLabel)
                    }
                    PausedGate(paused, gate) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) {
                            FilledTonalButton(
                                onClick = { sendMenu = true },
                                enabled = status == Link.Status.Connected,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Send files", maxLines = 1)
                            }
                            SendMenu(sendMenu, onDismiss = { sendMenu = false },
                                onFiles = { pickFiles.launch(arrayOf("*/*")) }, onFolder = { pickFolder.launch(null) })
                            FlyAway(flown, Icons.Outlined.InsertDriveFile, Modifier.align(Alignment.Center))
                        }
                        Box(Modifier.weight(1f)) {
                            FilledTonalButton(
                                onClick = { sendClipboard() },
                                enabled = status == Link.Status.Connected,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(Icons.Outlined.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Send clipboard", maxLines = 1)
                            }
                            SentChip(chip, Modifier.align(Alignment.TopCenter).offset(y = (-8).dp))
                        }
                    } }
                }
            }

            if (!features.value.localNetwork) item { LocalNetworkCard(allowLocalNetwork) }
            item { PausedGate(paused, gate) { SetupChecklist(features.value, featureActions) } }

            item { PausedGate(paused, gate) {
                SectionCard("Clipboard") {
                    SwitchRow(Icons.Outlined.ContentCopy, "From ${Computer.word}", "Copy on ${Computer.your}, paste here", clip) { Prefs.setReceiveClipboard(it) }
                    SwitchRow(Icons.Outlined.AutoAwesome, "To ${Computer.word} automatically",
                        if (controls) "Copy here, paste on ${Computer.your}" else "Copy here, paste on ${Computer.your}. Turns on clipboard and controls.",
                        controls) { explainControls = true }
                    SwitchRow(Icons.Outlined.Screenshot, "Screenshots",
                        if (shotsAllowed) "New screenshots are ready to paste on ${Computer.your}" else "Needs access to photos",
                        shots && shotsAllowed) { on ->
                        if (on && !shotsAllowed) photosPermission.launch(app.macndroid.service.ScreenshotWatcher.permission)
                        else { Prefs.setScreenshotsToMac(on); refreshService() }
                    }
                }
            } }

            item { PausedGate(paused, gate) {
                SectionCard("Photos, messages and music") {
                    SwitchRow(Icons.Outlined.PhotoLibrary, "Photos on ${Computer.word}",
                        when {
                            !galleryAllowed -> "See this phone's photos and videos on ${Computer.your}. Needs access to photos."
                            galleryOn -> "${pairing?.macName ?: Computer.your} can browse your photos and videos"
                            else -> "${Computer.your} can't see your photos and videos"
                        },
                        galleryOn && galleryAllowed) { on ->
                        if (on && !galleryAllowed) galleryPermission.launch(app.macndroid.service.Gallery.permissions)
                        else { Prefs.setGalleryOnMac(on); app.macndroid.service.Gallery.reportAccess(ctx) }
                    }
                    SwitchRow(Icons.Outlined.Sms, "Messages on ${Computer.word}",
                        if (smsAllowed) "Read and reply to texts from ${pairing?.macName ?: Computer.your}" else "Read and reply to texts. Needs access to SMS.",
                        smsOn && smsAllowed) { on ->
                        if (on && !smsAllowed) smsPermission.launch(app.macndroid.service.SmsBridge.permissions)
                        else { Prefs.setSmsOnMac(on); refreshService() }
                    }
                    SwitchRow(Icons.Outlined.MusicNote, "Music on ${Computer.your}",
                        "Play, pause and skip from your phone",
                        macMusic) { on ->
                        Prefs.setMacMusic(on)
                        if (!on) app.macndroid.service.MacMusic.end()
                        app.macndroid.core.Link.post(org.json.JSONObject().put("t", "mac_media_want").put("on", on))
                    }
                }
            } }

            item { PausedGate(paused, gate) {
                SectionCard("Phone") {
                    SwitchRow(Icons.Outlined.TouchApp, "Control from ${Computer.word}",
                        if (controls) "Tap and type here from the Phone Screen window" else "Mirroring is watch-only without it. Turns on clipboard and controls.",
                        controls) { explainControls = true }
                    SwitchRow(Icons.Outlined.NotificationsActive, "Left-behind alerts",
                        "A chime if you leave the Wi-Fi network ${pairing?.macName ?: Computer.your} is on while it's awake",
                        leftBehind) { Prefs.setLeftBehind(it) }
                    SwitchRow(Icons.Outlined.VolumeUp, "Sounds",
                        "Sounds when files arrive or send, and for left-behind alerts",
                        sounds) { Prefs.setSounds(it) }
                    SwitchRow(Icons.Outlined.Vibration, "Haptic feedback",
                        "A light tap when things sync",
                        haptics) { Prefs.setHaptics(it) }
                }
            } }

            item {
                SectionCard("Tips") {
                    Tip.entries.forEach { tip ->
                        ListItem(
                            modifier = Modifier.tileRow().clickableRow { openTip = tip },
                            headlineContent = { Text(tip.title) },
                            supportingContent = { Text(tip.summary) },
                            leadingContent = { Icon(tip.icon, null) },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                            colors = ListItemDefaults.colors(containerColor = tileColor),
                        )
                    }
                }
            }
        }
    }

    if (pauseSheet) PauseSheet(macLabel) { pauseSheet = false }
    if (askResume) ResumeDialog { askResume = false }
    openTip?.let { TipSheet(it, macName = pairing?.macName ?: Computer.word, onDismiss = { openTip = null }) }

    if (explainControls) ControlsConsentDialog(on = controls) { explainControls = false }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = { Icon(Icons.Outlined.LinkOff, null) },
            title = { Text("Unpair ${pairing?.macName ?: Computer.word}?") },
            text = { Text("This phone will stop syncing with ${Computer.your}. You can pair again at any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    Prefs.clearPairing()
                    ShareShortcuts.remove(ctx)
                    Link.stop()
                    SyncService.stop(ctx)
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

/** The paired Mac: its name as the heading, connection at the top right, then a line of detail. */
@Composable
private fun StatusCard(status: Link.Status, macName: String) {
    val scheme = MaterialTheme.colorScheme
    val (label, detail) = when (status) {
        Link.Status.Connected -> "Connected" to "Notifications, clipboard and files are syncing."
        Link.Status.Connecting -> "Connecting" to "Securing the connection."
        Link.Status.Searching -> "Searching" to "Make sure Mac n Droid is open on ${Computer.your} and you're on the same Wi-Fi."
        Link.Status.AuthFailed -> "Expired" to "Your ${Computer.word} reset its pairing code. Unpair, then scan the new QR code."
        Link.Status.Unpaired -> "Not paired" to ""
    }
    val dot = when (status) {
        Link.Status.Connected -> scheme.primary
        Link.Status.AuthFailed -> scheme.error
        else -> scheme.outline
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh, contentColor = scheme.onSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(macName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                if (status == Link.Status.Searching || status == Link.Status.Connecting) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = scheme.onSurfaceVariant)
                } else {
                    Surface(shape = CircleShape, color = dot, modifier = Modifier.size(8.dp)) {}
                }
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (status == Link.Status.AuthFailed) scheme.error else scheme.onSurfaceVariant)
            }
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Shrinks and rounds the screen as a predictive back swipe progresses, like Android's own screens. */
internal fun Modifier.predictiveBack(progress: Float): Modifier = this.then(
    Modifier.graphicsLayer {
        val s = 1f - 0.1f * progress
        scaleX = s; scaleY = s
        shape = RoundedCornerShape((32 * progress).dp); clip = progress > 0f
    },
)

@Composable
internal fun SwitchRow(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val change = { on: Boolean ->
        if (Prefs.haptics.value) view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) (if (on) android.view.HapticFeedbackConstants.TOGGLE_ON else android.view.HapticFeedbackConstants.TOGGLE_OFF)
            else android.view.HapticFeedbackConstants.CLOCK_TICK)
        onChange(on)
    }
    ListItem(
        modifier = Modifier.tileRow().clickableRow { change(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked, change) },
        colors = ListItemDefaults.colors(containerColor = tileColor),
    )
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        // Gboard-style group: rounded outside, rows separated by a thin gap.
        Column(Modifier.clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

fun Modifier.clickableRow(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

// MARK: Permissions

internal data class PermissionState(val postNotifications: Boolean, val listener: Boolean, val battery: Boolean) {
    val allGranted get() = postNotifications && listener && battery

    companion object {
        fun read(ctx: Context) = PermissionState(
            postNotifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
            battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
        )
    }
}

internal fun openListenerSettings(ctx: Context) {
    val component = ComponentName(ctx, app.macndroid.service.NotificationMirror::class.java)
    val detail = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else null
    runCatching { ctx.startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

@android.annotation.SuppressLint("BatteryLife")
internal fun requestBatteryExemption(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
