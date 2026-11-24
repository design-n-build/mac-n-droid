package app.macndroid.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * What differs between phone makers: what Accessibility settings call the list of apps' services, and whether
 * the maker's own battery manager stops background apps even after Android's "unrestricted" battery setting.
 * OnePlus, OPPO, realme, vivo/iQOO and Xiaomi freeze apps in the background unless you allow background
 * activity on their own settings page, so copies and screenshots wait until you open the app.
 */
object PhoneMaker {
    private val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()

    private val oppoFamily get() = listOf("oneplus", "oppo", "realme").any { it in brand }
    private val vivoFamily get() = listOf("vivo", "iqoo").any { it in brand }
    private val xiaomiFamily get() = listOf("xiaomi", "redmi", "poco").any { it in brand }

    /** The heading in Accessibility settings that lists services from installed apps. */
    val accessibilityList: String get() = if ("samsung" in brand) "Installed apps" else "Downloaded apps"

    /** This maker has its own background limits on top of Android's. */
    val freezesBackgroundApps: Boolean get() = oppoFamily || vivoFamily || xiaomiFamily

    /** The maker's name as people know it, for "On your OnePlus phone…". */
    val name: String get() = when {
        "oneplus" in brand -> "OnePlus"
        "realme" in brand -> "realme"
        "oppo" in brand -> "OPPO"
        "iqoo" in brand -> "iQOO"
        "vivo" in brand -> "vivo"
        xiaomiFamily -> "Xiaomi"
        else -> Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    }

    /** Where to allow background activity, on the App info page that [openAppInfo] opens. */
    val backgroundSteps: String get() = when {
        oppoFamily -> "On App info, open Battery usage and turn on Allow background activity (and Allow auto launch, if you see it)."
        vivoFamily -> "On App info, open Battery and choose Allow background power consumption (or Allow high background power usage). Turn on Autostart too, if you see it."
        xiaomiFamily -> "On App info, turn on Autostart, then open Battery saver and choose No restrictions."
        else -> "On App info, open Battery and choose Unrestricted."
    }

    fun openAppInfo(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private const val PREFS = "phone_maker"

    /** The person opened the maker's background setting from the app. Android can't tell us whether they turned it on. */
    fun backgroundChecked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("backgroundOpened", false)

    fun markBackgroundChecked(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("backgroundOpened", true).apply()
}
