package app.macndroid.ui

import app.macndroid.core.Computer

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.macndroid.core.Prefs
import app.macndroid.service.NotificationMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PhoneApp(val pkg: String, val name: String, val category: String, val icon: ImageBitmap?)

private val CATEGORY_TITLES = linkedMapOf(
    "messages" to "Messages", "calls" to "Calls", "email" to "Email", "social" to "Social",
    "shopping" to "Shopping & food", "finance" to "Banking & payments", "media" to "Music & video",
    "travel" to "Travel & rides", "work" to "Work & productivity", "news" to "News", "games" to "Games",
    "system" to "System", "other" to "Other",
)

/**
 * Notifications tab: turn mirroring on/off, check access, and choose which apps' notifications
 * go to the Mac. Apps that are off still notify you on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen() {
    val ctx = LocalContext.current
    val mirror by Prefs.mirrorNotifications.collectAsState()
    var hasAccess by remember { mutableStateOf(listenerEnabled(ctx)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { hasAccess = listenerEnabled(ctx) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val view = LocalView.current
    val blocked by Prefs.blockedApps.collectAsState()
    var apps by remember { mutableStateOf<List<PhoneApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }


    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != ctx.packageName }
                .map { info ->
                    PhoneApp(
                        pkg = info.packageName,
                        name = pm.getApplicationLabel(info).toString(),
                        category = NotificationMirror.Categories.categorize(pm, info.packageName, null, info),
                        icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
    }

    fun update(set: Set<String>) {
        Prefs.setBlockedApps(set)
        NotificationMirror.instance?.applyFilter()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Notifications") },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Allow all apps") }, onClick = { menu = false; update(emptySet()) })
                        DropdownMenuItem(text = { Text("Allow no apps") }, onClick = {
                            menu = false
                            update(apps.orEmpty().map { it.pkg }.toSet())
                        })
                    }
                },
            )
        },
    ) { pad ->
        val list = apps.orEmpty()
        val shown = list.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard("On ${Computer.your}") {
                        SwitchRow(Icons.Outlined.Notifications, "Show notifications on ${Computer.your}",
                            if (mirror) "New notifications appear on ${Computer.your} as they arrive" else "Notifications stay on this phone only",
                            mirror) { on ->
                            Prefs.setMirrorNotifications(on)
                            if (on) NotificationMirror.instance?.syncAll()
                        }
                        if (!hasAccess) {
                            ListItem(
                                modifier = Modifier.tileRow().clickableRow { openListenerSettings(ctx) },
                                headlineContent = { Text("Allow notification access") },
                                supportingContent = { Text("Needed to read notifications and music. Tap to open settings.") },
                                leadingContent = { Icon(Icons.Outlined.NotificationsOff, null, tint = MaterialTheme.colorScheme.error) },
                                colors = ListItemDefaults.colors(containerColor = tileColor),
                            )
                        }
                    }
                }
            }
            if (!mirror) return@LazyColumn
            if (apps == null) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                return@LazyColumn
            }
            item {
                Text("Apps", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp))
                Text(
                    "${list.count { it.pkg !in blocked }} of ${list.size} apps send notifications to ${Computer.your}. " +
                        "Turned-off apps still notify you here on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            CATEGORY_TITLES.forEach { (cat, title) ->
                val group = shown.filter { it.category == cat }
                if (group.isNotEmpty()) {
                    item(key = "h-$cat") {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
                    }
                    items(group, key = { it.pkg }) { app ->
                        val on = app.pkg !in blocked
                        val toggle = { value: Boolean ->
                            if (Prefs.haptics.value) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            update(if (value) blocked - app.pkg else blocked + app.pkg)
                        }
                        ListItem(
                            modifier = Modifier.clickableRow { toggle(!on) },
                            headlineContent = { Text(app.name) },
                            leadingContent = {
                                app.icon?.let { Image(it, null, Modifier.size(40.dp)) } ?: Spacer(Modifier.size(40.dp))
                            },
                            trailingContent = { Switch(on, toggle) },
                        )
                    }
                }
            }
        }
    }
}

private fun listenerEnabled(ctx: android.content.Context) =
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
