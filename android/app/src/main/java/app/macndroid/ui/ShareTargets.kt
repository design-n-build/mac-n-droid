package app.macndroid.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.macndroid.core.Prefs

/**
 * The share sheet's "Send to …" and "Open on …" are named after the paired computer: Mac, PC (Windows and Linux),
 * or "computer" before pairing. Android takes share-target names from the manifest, so each has three aliases
 * (AndroidManifest.xml) and only the matching one is switched on.
 */
object ShareTargets {
    private val SEND = mapOf("mac" to "SendToMac", "pc" to "SendToPc", "computer" to "SendToComputer")
    private val OPEN = mapOf("mac" to "OpenOnMac", "pc" to "OpenOnPc", "computer" to "OpenOnComputer")

    fun update(ctx: Context) {
        val kind = when (Prefs.pairing.value?.os) {
            null -> "computer"
            "mac" -> "mac"
            else -> "pc"
        }
        val pm = ctx.packageManager
        for (names in listOf(SEND, OPEN)) {
            for ((k, name) in names) {
                val want = if (k == kind) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                           else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val c = ComponentName(ctx, "app.macndroid.ui.$name")
                if (runCatching { pm.getComponentEnabledSetting(c) }.getOrNull() == want) continue
                runCatching { pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP) }
            }
        }
    }
}
