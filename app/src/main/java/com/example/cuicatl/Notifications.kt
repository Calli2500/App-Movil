package com.example.cuicatl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object AppNotifications {

    const val CHANNEL_EXTRACT = "channel_extract"
    const val CHANNEL_PLAYER = "channel_player"
    private const val EXTRACT_NOTIFICATION_ID = 2001

    // Los canales solo existen desde Android 8 (API 26)
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXTRACT, "Extracción de audio", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Avisa cuando termina de extraerse el audio de un video" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYER, "Reproductor", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de la música en reproducción"
                setShowBadge(false)
            }
        )
    }

    fun notifyExtractionDone(context: Context, title: String) {
        ensureChannels(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_EXTRACT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Audio extraído")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        // Sin permiso (Android 13+) simplemente no se muestra
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        NotificationManagerCompat.from(context).notify(EXTRACT_NOTIFICATION_ID, notification)
    }
}