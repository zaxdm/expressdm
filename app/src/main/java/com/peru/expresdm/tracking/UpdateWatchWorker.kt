package com.peru.expresdm.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.peru.expresdm.MainActivity
import com.peru.expresdm.R
import com.peru.expresdm.data.AppUpdate
import com.peru.expresdm.data.AppUpdates
import java.util.concurrent.TimeUnit

/**
 * Avisa de que hay una versión nueva de la app.
 *
 * El servidor lo dice al momento a los teléfonos apuntados a los avisos
 * ([PushService]). Para los demás, unas cuantas veces al día y con la app
 * cerrada se mira la versión que hay en la web. En los dos casos, si es más
 * nueva sale una notificación, una sola por versión; al tocarla se abre la app
 * ofreciendo descargarla.
 */
class UpdateWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        checkNow(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update_watch"
        private const val CHANNEL_ID = "actualizaciones"
        private const val CHECK_EVERY_HOURS = 6L

        /** Con este dato en el intent, [MainActivity] ofrece la versión nueva aunque ya la hubiera ofrecido hoy. */
        const val EXTRA_OFFER_UPDATE = "offer_update"

        fun sync(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWatchWorker>(CHECK_EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Mira si hay versión nueva y, si no se había avisado de ella, avisa. */
        suspend fun checkNow(context: Context) {
            val update = AppUpdates.check() ?: return
            if (AlertNotifier.canNotify(context) && AppUpdates.shouldNotify(context, update)) notify(context, update)
        }

        private fun notify(context: Context, update: AppUpdate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService<NotificationManager>()?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.update_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = context.getString(R.string.update_channel_description) }
                )
            }

            val open = PendingIntent.getActivity(
                context,
                CHANNEL_ID.hashCode(),
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OFFER_UPDATE, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = context.getString(R.string.update_notification_text, update.versionName)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_nav_routes)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(CHANNEL_ID.hashCode(), notification)
            } catch (_: SecurityException) {
                // El permiso se quitó entre la comprobación y el aviso: no hay nada que hacer.
            }
        }
    }
}
