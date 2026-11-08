package app.macndroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import app.macndroid.core.Prefs

class MacNDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        app.macndroid.core.Haptics.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("files")   // v0.1–0.4 channel used the system sound
        val soft = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun raw(id: Int) = Uri.parse("android.resource://$packageName/$id")
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shows while Mac n Droid keeps your phone linked to your Mac"
                    setShowBadge(false)
                },
                NotificationChannel(CH_FILES, "Received files", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(raw(R.raw.mnd_received), soft)
                    enableVibration(false)
                },
                NotificationChannel(CH_SENT, "Sent files", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Confirms a file reached your Mac"
                    setSound(raw(R.raw.mnd_sent), soft)
                    enableVibration(false)
                },
                NotificationChannel(CH_MIRROR, "Screen mirroring", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Asks before your screen is shown on your Mac"
                    setSound(null, null)
                },
                NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                },
            )
        )
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_FILES = "files_received"
        const val CH_SENT = "files_sent"
        const val CH_RING = "ring"
        const val CH_MIRROR = "mirror"
    }
}
