package app.macndroid.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.macndroid.R
import app.macndroid.core.Prefs

/** Publishes the paired Mac as a Direct Share target so it shows in the share sheet's first row. */
object ShareShortcuts {
    private const val ID = "send_to_mac"
    const val CATEGORY = "app.macndroid.category.SEND_TO_MAC"

    fun publish(ctx: Context) {
        val mac = Prefs.pairing.value?.macName ?: return
        val shortcut = ShortcutInfoCompat.Builder(ctx, ID)
            .setShortLabel(mac)
            .setLongLabel("Send to $mac")
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_share_mac))
            .setIntent(Intent(Intent.ACTION_DEFAULT, null, ctx, MainActivity::class.java))
            .setCategories(setOf(CATEGORY))
            .setLongLived(true)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(ctx, shortcut) }
    }

    /** Helps Android rank the Mac first in the share sheet. */
    fun reportUsed(ctx: Context) = runCatching { ShortcutManagerCompat.reportShortcutUsed(ctx, ID) }

    fun remove(ctx: Context) = runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(ctx) }
}
