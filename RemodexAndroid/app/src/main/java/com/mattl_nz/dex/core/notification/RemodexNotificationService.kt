package com.mattl_nz.dex.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mattl_nz.dex.R
import com.mattl_nz.dex.app.MainActivity

class RemodexNotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "remodex_turns"
        const val CHANNEL_NAME = "Turn Updates"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_SOURCE = "notification_source"
        const val SOURCE_RUN_COMPLETION = "codex.runCompletion"
        const val SOURCE_STRUCTURED_INPUT = "codex.structuredUserInput"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications for completed turns and required input"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun notifyRunCompletion(
        threadId: String,
        threadTitle: String,
        isSuccess: Boolean,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra(EXTRA_SOURCE, SOURCE_RUN_COMPLETION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, threadId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(threadTitle)
            .setContentText(if (isSuccess) "Response ready" else "Run failed")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup("remodex_turns")
            .build()

        val notificationId = "$SOURCE_RUN_COMPLETION.$threadId".hashCode()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    fun notifyStructuredInput(
        threadId: String,
        threadTitle: String,
        questionCount: Int,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra(EXTRA_SOURCE, SOURCE_STRUCTURED_INPUT)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, threadId.hashCode() + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = if (questionCount == 1) {
            "Codex needs 1 answer to continue."
        } else {
            "Codex needs $questionCount answers to continue."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(threadTitle)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup("remodex_turns")
            .build()

        val notificationId = "$SOURCE_STRUCTURED_INPUT.$threadId".hashCode()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
