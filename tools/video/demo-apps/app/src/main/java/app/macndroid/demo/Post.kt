package app.macndroid.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Posts this app's one notification from the video series' story (video/series/STORY.md, section 2).
 * Invented senders only. Reply and Mark as read work, and take the notification away like a real chat app.
 */
class Post : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (intent.action == DONE || intent.getBooleanExtra("clear", false)) { nm.cancelAll(); return }
        nm.createNotificationChannel(NotificationChannel("story", ctx.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(ctx, "story")
            .setSmallIcon(R.drawable.ic_small)
            .setColor(ctx.getColor(R.color.brand))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setAutoCancel(true)
        when (BuildConfig.KIND) {
            "text" -> {
                val priya = Person.Builder().setName("Priya").build()
                val done = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, Post::class.java).setAction(DONE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                b.setCategory(Notification.CATEGORY_MESSAGE)
                    .setStyle(Notification.MessagingStyle(Person.Builder().setName("You").build())
                        .addMessage("Dinner at 8? 🍝", System.currentTimeMillis(), priya))
                    .addAction(Notification.Action.Builder(null, "Reply", done)
                        .addRemoteInput(RemoteInput.Builder("reply").setLabel("Reply").build()).build())
                    .addAction(Notification.Action.Builder(null, "Mark as read", done).build())
            }
            "code" -> b.setCategory(Notification.CATEGORY_MESSAGE)
                .setContentTitle("Northwind Bank")
                .setContentText("482913 is your sign-in code. Never share it with anyone.")
                .setStyle(Notification.BigTextStyle().bigText("482913 is your sign-in code. Never share it with anyone."))
            else -> b.setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("Download complete")
                .setContentText("dinner-menu.jpg")
        }
        nm.notify(1, b.build())
    }

    private companion object { const val DONE = "app.macndroid.demo.DONE" }
}
