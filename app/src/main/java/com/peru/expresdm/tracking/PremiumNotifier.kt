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
import com.peru.expresdm.MainActivity
import com.peru.expresdm.R
import com.peru.expresdm.data.net.SessionStore

/**
 * Los avisos de Subo Premium: que se confirmó o se rechazó el pago, o que
 * Premium está por vencer. El servidor manda el título y el texto ya escritos;
 * al tocar el aviso se abre la hoja de Premium en el perfil.
 */
object PremiumNotifier {

    private const val CHANNEL_ID = "subo_premium"

    /** Con este extra, MainActivity abre el perfil con la hoja de Premium. */
    const val EXTRA_OPEN_PREMIUM = "open_premium"

    const val KIND_CONFIRMED = "premium_confirmed"
    const val KIND_REJECTED = "premium_rejected"
    const val KIND_EXPIRING = "premium_expiring"

    val KINDS = setOf(KIND_CONFIRMED, KIND_REJECTED, KIND_EXPIRING)

    fun handle(context: Context, kind: String, data: Map<String, String>) {
        // Pago confirmado: Premium se abre ya, sin esperar a la próxima vez que
        // la app pregunte por la sesión.
        if (kind == KIND_CONFIRMED) {
            data["premiumUntil"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
                SessionStore.get(context).updatePremiumUntil(it)
            }
        }
        show(
            context,
            kind,
            title = data["title"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.premium_title),
            body = data["body"].orEmpty()
        )
    }

    private fun show(context: Context, kind: String, title: String, body: String) {
        if (!AlertNotifier.canNotify(context)) return
        createChannel(context)

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_PREMIUM, true)
        val open = PendingIntent.getActivity(
            context,
            CHANNEL_ID.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_star)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            // Uno por tipo: un segundo aviso del mismo tipo sustituye al primero.
            NotificationManagerCompat.from(context).notify(CHANNEL_ID.hashCode() xor kind.hashCode(), notification)
        } catch (_: SecurityException) {
            // El permiso se quitó entre la comprobación y el aviso: no hay nada que hacer.
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.premium_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.premium_channel_description) }
        )
    }
}
