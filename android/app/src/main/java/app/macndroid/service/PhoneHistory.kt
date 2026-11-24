package app.macndroid.service

import android.content.Context
import app.macndroid.core.Prefs

/**
 * What came from the paired computer and stays on the phone while it's paired: the clipboard history and the
 * Files lists. A different computer must never see the old one's things.
 */
object PhoneHistory {
    /** Unpaired, or the computer let go of this phone: nothing from it stays. */
    fun forget(ctx: Context) {
        ClipHistory.clear()
        Transfers.clearAll()
        FileTransfers.forgetPaused(ctx)
    }

    /** About to pair with `macId`: a different computer than before starts clean. */
    fun pairing(ctx: Context, macId: String) {
        val last = Prefs.lastMacId
        if (last != null && last != macId) forget(ctx)
    }
}
