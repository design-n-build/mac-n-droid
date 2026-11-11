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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import app.macndroid.service.NotificationMirror
import app.macndroid.service.TransferItem
import app.macndroid.service.Transfers
import app.macndroid.ui.MainActivity
import app.macndroid.ui.previewColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onAllNodesWithText
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

/**
 * Renders the real app screens with sample data, for the Play Store listing.
 *   ./gradlew storeShots   → app/build/store-shots/
 * Nothing here ships in the app.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class StoreShots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val out = System.getProperty("storeShots.out") ?: "build/store-shots"

    init { setUp() }

    private fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOf(app)
        shadow.grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS,
        )
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            ComponentName(app, NotificationMirror::class.java).flattenToString())
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)

        Prefs.init(app)
        previewColors = { dark -> if (dark) greenDark else greenLight }
        Prefs.setSmsOnMac(true)
        Prefs.savePairing(Pairing("demo", ByteArray(32), listOf("192.168.1.24"), 47800, "MacBook Air"))
        @Suppress("UNCHECKED_CAST")
        (Link::class.java.getDeclaredField("_status").apply { isAccessible = true }.get(Link) as MutableStateFlow<Link.Status>)
            .value = Link.Status.Connected

        val now = System.currentTimeMillis()
        fun min(m: Int) = now - m * 60_000L
        ClipHistory.receive(JSONObject().put("items", JSONArray().apply {
            put(JSONObject().put("id", "1").put("kind", "text").put("text", "https://maps.example.com/terminal-2-pickup").put("from", "mac").put("time", min(1)))
            put(JSONObject().put("id", "2").put("kind", "text").put("text", "482913").put("from", "phone").put("time", min(6)))
            put(JSONObject().put("id", "3").put("kind", "text").put("text", "Booking ref: KX7-29Q · Cabin by the lake, Sat–Sun").put("from", "mac").put("time", min(22)))
            put(JSONObject().put("id", "4").put("kind", "text").put("text", "Let's keep the header simple: logo, one line, one button.").put("from", "mac").put("time", min(48)))
            put(JSONObject().put("id", "5").put("kind", "text").put("text", "221B Lake View Road, Apartment 4").put("from", "phone").put("time", min(95)))
        }))
        @Suppress("UNCHECKED_CAST")
        (Transfers::class.java.getDeclaredField("_items").apply { isAccessible = true }.get(Transfers) as MutableStateFlow<List<TransferItem>>)
            .value = emptyList()
        listOf(
            TransferItem("t5", "Invoice November.pdf", 210_000, outgoing = false, bytes = 210_000, state = TransferItem.State.Done, mime = "application/pdf", time = min(130)),
            TransferItem("t4", "IMG_2041.jpg", 4_200_000, outgoing = true, bytes = 4_200_000, state = TransferItem.State.Done, mime = "image/jpeg", time = min(64)),
            TransferItem("t3", "Boarding pass.pdf", 320_000, outgoing = false, bytes = 320_000, state = TransferItem.State.Done, mime = "application/pdf", time = min(30)),
            TransferItem("t1", "Screenshot_20261110_0941.png", 1_840_000, outgoing = true, bytes = 1_840_000, state = TransferItem.State.Done, mime = "image/png", time = min(1)),
            TransferItem("t2", "Trip photos.zip", 248_000_000, outgoing = false, bytes = 151_000_000, state = TransferItem.State.Active, mime = "application/zip", time = min(0)),
        ).forEach(Transfers::add)

        // Launcher apps for the Notifications tab: generic names and icons, no real brands.
        val pm = shadowOf(app.packageManager)
        listOf(
            Triple("demo.bank", "Bank", 0xFF4F46E5.toInt() to 0xFF9333EA.toInt()),
            Triple("demo.calendar", "Calendar", 0xFFEC4899.toInt() to 0xFFF43F5E.toInt()),
            Triple("demo.chat", "Chat", 0xFF10B981.toInt() to 0xFF22C55E.toInt()),
            Triple("demo.food", "Food delivery", 0xFFF97316.toInt() to 0xFFFACC15.toInt()),
            Triple("demo.mail", "Mail", 0xFFEF4444.toInt() to 0xFFF97316.toInt()),
            Triple("demo.messages", "Messages", 0xFF3B82F6.toInt() to 0xFF06B6D4.toInt()),
            Triple("demo.music", "Music", 0xFFDB2777.toInt() to 0xFF7C3AED.toInt()),
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

    private fun all(suffix: String) {
        shot("android-01-home$suffix")
        rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + 10f, durationMillis = 600) }
        shot("android-02-home-more$suffix")
        tab("Files").performClick(); shot("android-03-files-received$suffix")
        rule.onNode(hasText("Sent (2)")).performClick(); shot("android-04-files-sent$suffix")
        tab("Clipboard").performClick(); shot("android-05-clipboard$suffix")
        tab("Notifications").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Food delivery").fetchSemanticsNodes().isNotEmpty() }
        shot("android-06-notifications$suffix")
        Prefs.clearPairing()
        shot("android-07-pair$suffix")
    }

    @Test fun light() = all("")
    @Test @Config(qualifiers = "+night") fun dark() = all("-dark")

    // Material You from a green wallpaper (seed #0DA457, the brand green), tonal-spot roles.
    private val greenLight = lightColorScheme(
        primary = Color(0xFF006D3D), onPrimary = Color.White, primaryContainer = Color(0xFF98F7B5), onPrimaryContainer = Color(0xFF00210F),
        secondary = Color(0xFF4F6354), onSecondary = Color.White, secondaryContainer = Color(0xFFD1E8D5), onSecondaryContainer = Color(0xFF0C1F13),
        tertiary = Color(0xFF3B6470), tertiaryContainer = Color(0xFFBEEAF7), onTertiaryContainer = Color(0xFF001F27),
        background = Color(0xFFF6FBF4), onBackground = Color(0xFF171D19), surface = Color(0xFFF6FBF4), onSurface = Color(0xFF171D19),
        surfaceVariant = Color(0xFFDCE5DC), onSurfaceVariant = Color(0xFF414942), outline = Color(0xFF717971), outlineVariant = Color(0xFFC0C9C0),
        surfaceBright = Color(0xFFF6FBF4), surfaceDim = Color(0xFFD6DBD5), surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F5EE), surfaceContainer = Color(0xFFEAEFE8), surfaceContainerHigh = Color(0xFFE5EAE3),
        surfaceContainerHighest = Color(0xFFDFE4DD), inverseSurface = Color(0xFF2C322D), inverseOnSurface = Color(0xFFEDF2EB),
    )
    private val greenDark = darkColorScheme(
        primary = Color(0xFF7CDA9B), onPrimary = Color(0xFF00391D), primaryContainer = Color(0xFF00522C), onPrimaryContainer = Color(0xFF98F7B5),
        secondary = Color(0xFFB5CCBA), onSecondary = Color(0xFF213527), secondaryContainer = Color(0xFF374B3D), onSecondaryContainer = Color(0xFFD1E8D5),
        tertiary = Color(0xFFA3CDDB), tertiaryContainer = Color(0xFF214C57), onTertiaryContainer = Color(0xFFBEEAF7),
        background = Color(0xFF0F1511), onBackground = Color(0xFFDFE4DD), surface = Color(0xFF0F1511), onSurface = Color(0xFFDFE4DD),
        surfaceVariant = Color(0xFF414942), onSurfaceVariant = Color(0xFFC0C9C0), outline = Color(0xFF8A938B), outlineVariant = Color(0xFF414942),
        surfaceBright = Color(0xFF353B36), surfaceDim = Color(0xFF0F1511), surfaceContainerLowest = Color(0xFF0A0F0C),
        surfaceContainerLow = Color(0xFF171D19), surfaceContainer = Color(0xFF1B211D), surfaceContainerHigh = Color(0xFF252B27),
        surfaceContainerHighest = Color(0xFF303632), inverseSurface = Color(0xFFDFE4DD), inverseOnSurface = Color(0xFF2C322D),
    )
}
