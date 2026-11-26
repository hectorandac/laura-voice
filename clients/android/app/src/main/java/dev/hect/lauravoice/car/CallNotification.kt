package dev.hect.lauravoice.car

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import dev.hect.lauravoice.App
import dev.hect.lauravoice.MainActivity
import dev.hect.lauravoice.R
import dev.hect.lauravoice.VoiceService
import dev.hect.lauravoice.core.CarCall
import dev.hect.lauravoice.core.VoiceServiceBase

/**
 * The ongoing-call notification for a car call (Core-Telecom asks for a CallStyle notification on the foreground
 * service within 5 s of adding the call). Hang up = turn her off.
 */
object CallNotification {
    fun build(context: Context): Notification {
        val ui = App.engine.ui.value
        val laura = Person.Builder()
            .setName("Laura")
            .setIcon(IconCompat.createWithBitmap(BitmapFactory.decodeResource(context.resources, R.drawable.car_icon)))
            .setImportant(true)
            .build()
        val hangUp = PendingIntent.getService(
            context, 10,
            Intent(context, VoiceService::class.java).setAction(VoiceServiceBase.ACTION_STOP).putExtra("why", "hung up (notification)"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, 11, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        val since = LauraCall.since.takeIf { it > 0 }
            ?.let { System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it) } ?: System.currentTimeMillis()
        return NotificationCompat.Builder(context, VoiceServiceBase.CHANNEL)
            .setSmallIcon(dev.hect.lauravoice.core.R.drawable.ic_laura)
            .setContentTitle("Laura")
            .setContentText("Car call · " + CarCall.status(ui, true))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setWhen(since)
            .setUsesChronometer(true)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(laura, hangUp))
            .build()
    }
}
