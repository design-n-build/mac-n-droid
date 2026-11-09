package app.macndroid.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.macndroid.R
import app.macndroid.core.Prefs

/**
 * Puts the paired Mac in the share sheet's top row (Direct Share), as two clear actions:
 * "Open on <Mac>" (links open in its browser) and "Send to <Mac>" (files, or text to its clipboard).
 * Their icon is a laptop, so it is clear the link or file is going to the Mac.
 */
object ShareShortcuts {
    const val SEND_ID = "send_to_mac"
    const val OPEN_ID = "open_on_mac"
    private const val SEND_CATEGORY = "app.macndroid.category.SEND_TO_MAC"
    private const val OPEN_CATEGORY = "app.macndroid.category.OPEN_ON_MAC"

    fun publish(ctx: Context) {
        val mac = Prefs.pairing.value?.macName ?: return
        // Shown only when sharing text or a link, first.
        push(ctx, OPEN_ID, "Open on $mac", OPEN_CATEGORY, rank = 0)
        push(ctx, SEND_ID, "Send to $mac", SEND_CATEGORY, rank = 1)
    }

    private fun push(ctx: Context, id: String, label: String, category: String, rank: Int) {
        val shortcut = ShortcutInfoCompat.Builder(ctx, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_share_mac))
            .setIntent(Intent(Intent.ACTION_DEFAULT, null, ctx, MainActivity::class.java))
            .setCategories(setOf(category))
            .setLongLived(true)
            .setRank(rank)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(ctx, shortcut) }
    }

    /** Helps Android rank the Mac first in the share sheet. */
    fun reportUsed(ctx: Context, id: String) = runCatching { ShortcutManagerCompat.reportShortcutUsed(ctx, id) }

    fun remove(ctx: Context) = runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(ctx) }
}
