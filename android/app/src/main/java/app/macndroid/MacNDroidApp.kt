package app.macndroid

import app.macndroid.core.Computer

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
        app.macndroid.core.Pause.init(this)
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
                    description = "Shows while Mac n Droid keeps your phone linked to ${Computer.your}"
                    setShowBadge(false)
                },
                NotificationChannel(CH_FILES, "Received files", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(raw(R.raw.mnd_received), soft)
                    enableVibration(false)
                },
                NotificationChannel(CH_SENT, "Sent files", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Confirms a file reached ${Computer.your}"
                    setSound(raw(R.raw.mnd_sent), soft)
                    enableVibration(false)
                },
                NotificationChannel(CH_MIRROR, "Screen mirroring", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Asks before your screen is shown on ${Computer.your}"
                    setSound(null, null)
                },
                NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                },
                NotificationChannel(CH_LEFT_BEHIND, "Left-behind alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tells you when you leave the Wi-Fi network ${Computer.your} is on while it's awake"
                    setSound(raw(R.raw.mnd_left_behind), soft)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                },
                NotificationChannel(CH_LINKS, "Links from ${Computer.word}", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Links sent from ${Computer.your}, when they can't open by themselves"
                    setSound(null, null)
                },
                NotificationChannel(CH_PROGRESS, "Files in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows how far along a file is while it moves to or from ${Computer.your}"
                    setShowBadge(false)
                },
                NotificationChannel(CH_MAC_MUSIC, "Music on ${Computer.your}", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Play, pause and skip what's playing on ${Computer.your}"
                    setShowBadge(false)
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
        const val CH_LEFT_BEHIND = "left_behind"
        const val CH_LINKS = "links"
        const val CH_MAC_MUSIC = "mac_music"
        const val CH_PROGRESS = "transfer_progress"
    }
}
