package app.macndroid

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.macndroid.core.Link
import app.macndroid.core.Pairing
import app.macndroid.core.Prefs
import app.macndroid.service.ClipHistory
import app.macndroid.service.CopyWatcher
import app.macndroid.service.FOLDER_MIME
import app.macndroid.service.NotificationMirror
import app.macndroid.service.TransferItem
import app.macndroid.service.Transfers
import app.macndroid.ui.MacNDroidTheme
import app.macndroid.ui.MainActivity
import app.macndroid.ui.PairedScreen
import app.macndroid.ui.previewColors
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Stills and frame sequences of the real Android screens for the video series, with the series'
 * demo data (video/series/STORY.md, sections 2 and 5B).
 *   tools/video/android-shots.sh        → video/series/captures/android/
 * A Pixel 8's screen (412 × 915 dp) at 3× density: 1236 × 2745 px. The system status bar isn't part of
 * the app, so it isn't in these; live recordings have it. Nothing here ships in the app.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class VideoShots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val out = System.getProperty("videoShots.out") ?: "build/video-shots"
    private val mac = "MacBook Air"
    private val now = System.currentTimeMillis()
    private fun min(m: Int) = now - m * 60_000L

    init { setUp() }

    private fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS,
        )
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            ComponentName(app, NotificationMirror::class.java).flattenToString())
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(app, CopyWatcher::class.java).flattenToString())
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)

        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT"))
        Prefs.init(app)
        // Monochrome, like the website; -Ppalette=green gives the brand green.
        val green = System.getProperty("videoShots.palette") == "green"
        previewColors = { dark -> if (green) (if (dark) greenDark else greenLight) else if (dark) monoDark else monoLight }
        Prefs.setSmsOnMac(true)
        Prefs.savePairing(Pairing("demo", ByteArray(32), listOf("192.168.1.24"), 47800, mac))
        status(Link.Status.Connected)
        clips(withAddress = false)
        transfers(emptyList())

        // Apps for the Notifications tab: invented names, no real brands.
        val pm = shadowOf(app.packageManager)
        listOf(
            Triple("demo.calendar", "Calendar", 0xFFEC4899.toInt() to 0xFFF43F5E.toInt()),
            Triple("demo.mail", "Mail", 0xFFEF4444.toInt() to 0xFFF97316.toInt()),
            Triple("demo.messages", "Messages", 0xFF3B82F6.toInt() to 0xFF06B6D4.toInt()),
            Triple("demo.music", "Music", 0xFFDB2777.toInt() to 0xFF7C3AED.toInt()),
            Triple("demo.bank", "Northwind Bank", 0xFF4F46E5.toInt() to 0xFF9333EA.toInt()),
            Triple("demo.phone", "Phone", 0xFF10B981.toInt() to 0xFF22C55E.toInt()),
            Triple("demo.photos", "Photos", 0xFFF59E0B.toInt() to 0xFFEF4444.toInt()),
        ).forEach { (pkg, label, colors) ->
            val ai = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label; name = label; flags = 0 }
            pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
            pm.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = ai } },
            )
            pm.setApplicationIcon(pkg, BitmapDrawable(app.resources, icon(label.first().toString(), colors.first, colors.second)))
        }
    }

    /** The clipboard history as the Mac sends it. [withAddress]: the Osteria Lucca address was just copied on the Mac. */
    private fun clips(withAddress: Boolean) {
        fun item(id: String, text: String, from: String, time: Long) =
            JSONObject().put("id", id).put("kind", "text").put("text", text).put("from", from).put("time", time)
        ClipHistory.receive(JSONObject().put("items", JSONArray().apply {
            if (withAddress) put(item("c1", "Osteria Lucca, 42 Elm Street", "mac", min(1)))
            put(item("c2", "482913", "phone", min(4)))
            put(item("c3", "Sounds good, see you there!", "mac", min(9)))
            put(item("c4", "Table for four at 8, under Priya", "phone", min(26)))
            put(item("c5", "Q4 deck: numbers are final, slides 6 to 9 changed", "mac", min(52)))
            put(item("c6", "https://osteria-lucca.example/menu", "phone", min(88)))
        }))
    }

    private fun transfers(list: List<TransferItem>) {
        @Suppress("UNCHECKED_CAST")
        (Transfers::class.java.getDeclaredField("_items").apply { isAccessible = true }.get(Transfers) as MutableStateFlow<List<TransferItem>>)
            .value = list
    }

    private val menu = TransferItem("v1", "dinner-menu.jpg", 150_000, outgoing = true, bytes = 150_000, state = TransferItem.State.Done, mime = "image/jpeg", time = min(2))
    private val deck = TransferItem("v2", "Q4-deck.pdf", 3_800_000, outgoing = true, bytes = 3_800_000, state = TransferItem.State.Done, mime = "application/pdf", time = min(14))
    private fun photos(done: Boolean) = TransferItem("v3", "holiday-photos", 186_000_000, outgoing = false,
        bytes = if (done) 186_000_000 else 115_000_000, state = if (done) TransferItem.State.Done else TransferItem.State.Active,
        mime = FOLDER_MIME, time = min(1))

    private fun status(s: Link.Status) {
        @Suppress("UNCHECKED_CAST")
        (Link::class.java.getDeclaredField("_status").apply { isAccessible = true }.get(Link) as MutableStateFlow<Link.Status>).value = s
    }

    private fun icon(letter: String, a: Int, b: Int): Bitmap {
        val s = 192
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(s / 2f, s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), a, b, Shader.TileMode.CLAMP)
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 92f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText(letter, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    private fun tab(label: String) =
        rule.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab), useUnmergedTree = false)

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage("$out/$name.png")
    }

    /** Real animation frames: the clock is stepped by hand, one 16 ms frame at a time (62.5 frames a second). */
    private fun frames(folder: String, from: Int, count: Int) {
        File("$out/$folder").mkdirs()
        repeat(count) { i ->
            rule.mainClock.advanceTimeBy(16)
            rule.onRoot().captureRoboImage("$out/$folder/%04d.png".format(from + i))
        }
    }

    private fun all(sfx: String) {
        shot("01-home$sfx")
        rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + 10f, durationMillis = 600) }
        shot("02-home-more$sfx")

        // Clipboard: before, and after the address copied on the Mac arrives.
        tab("Clipboard").performClick()
        shot("03-clipboard-before$sfx")
        clips(withAddress = true)
        shot("04-clipboard-address-from-mac$sfx")

        // Files: the folder dropped on the Mac's notch arriving, then what the phone sent.
        tab("Files").performClick()
        shot("05-files-empty$sfx")
        transfers(listOf(photos(done = false)))
        shot("06-files-receiving-folder$sfx")
        transfers(listOf(photos(done = true)))
        shot("07-files-received-folder$sfx")
        transfers(listOf(menu, photos(done = true), deck))
        rule.onNode(hasText("Sent (2)")).performClick()
        shot("08-files-sent$sfx")

        tab("Notifications").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Northwind Bank").fetchSemanticsNodes().isNotEmpty() }
        shot("09-notifications-apps$sfx")

        // Pairing: the screen with "Scan QR code".
        Prefs.setOnboarded(true)
        Prefs.clearPairing()
        status(Link.Status.Unpaired)
        shot("10-scan-qr$sfx")

        // Right after scanning: connecting (electricity), then connected (✓ and confetti), frame by frame.
        Prefs.savePairing(Pairing("demo", ByteArray(32), listOf("192.168.1.24"), 47800, mac))
        status(Link.Status.Connecting)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { rule.activity.setContent { MacNDroidTheme { PairedScreen(mac) {} } } }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().captureRoboImage("$out/11-connecting$sfx.png")
        if (sfx.isEmpty() || System.getProperty("videoShots.allFrames") == "true") {
            frames("12-pairing-frames$sfx", 0, 45)
            status(Link.Status.Connected)
            frames("12-pairing-frames$sfx", 45, 150)
        } else {
            status(Link.Status.Connected)
            rule.mainClock.advanceTimeBy(2400)
        }
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().captureRoboImage("$out/13-connected$sfx.png")
        rule.mainClock.autoAdvance = true
    }

    @Test fun light() = all("")
    @Test @Config(qualifiers = "+night") fun dark() = all("-dark")
}
