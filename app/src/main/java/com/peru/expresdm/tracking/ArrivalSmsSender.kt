package com.peru.expresdm.tracking

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.peru.expresdm.R
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.ArrivalSms
import com.peru.expresdm.data.Premium
import com.peru.expresdm.data.SmsContact
import com.peru.expresdm.data.net.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Cómo salió el último SMS de prueba, para enseñarlo en Ajustes. */
enum class SmsTestResult { SENDING, SENT, NO_SIGNAL, FAILED }

/**
 * Manda el SMS de «ya llegué» desde la línea del usuario (Premium).
 *
 * Android solo dice si el SMS salió o no, no por qué. Lo más común es:
 * - Sin señal: se reintenta solo, unas veces, cada pocos minutos.
 * - Sin saldo (el operador lo rechaza): reintentar no sirve. Se le avisa al
 *   usuario con una notificación para que lo reintente cuando recargue, o lo
 *   mande por WhatsApp si tiene datos.
 */
object ArrivalSmsSender {

    private const val TAG = "ArrivalSms"
    private const val CHANNEL_ID = "aviso_sms"
    private const val NOTIFICATION_ID = 1007

    const val ACTION_SENT = "com.peru.expresdm.sms.SENT"
    const val ACTION_RETRY = "com.peru.expresdm.sms.RETRY"
    const val EXTRA_NAME = "name"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_TEXT = "text"
    const val EXTRA_ATTEMPT = "attempt"
    const val EXTRA_TEST = "test"

    /** Sin señal, cuántas veces se prueba en total y cada cuánto. */
    const val MAX_ATTEMPTS = 4
    private const val RETRY_MINUTES = 3L

    private val _testResult = MutableStateFlow<SmsTestResult?>(null)
    val testResult: StateFlow<SmsTestResult?> = _testResult.asStateFlow()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** El teléfono puede mandar SMS (una tablet sin línea, no). */
    fun canSendSms(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** Hay internet de verdad ahora mismo (no solo una red sin salida). */
    fun hasInternet(context: Context): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Se llegó a la parada final: si toca, se manda el SMS. */
    fun onArrived(context: Context, placeName: String, latitude: Double?, longitude: Double?) {
        val settings = AppSettings.get(context)
        val contact = settings.arrivalSmsContact ?: return
        if (!Premium.active || !settings.arrivalSms || !hasPermission(context)) return
        if (settings.arrivalSmsOnlyOffline && hasInternet(context)) return

        val name = AccountRepository.session.value?.displayName
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        send(context, contact, ArrivalSms.message(name, placeName, time, latitude, longitude), attempt = 1, test = false)
    }

    /** Manda un SMS de prueba al contacto, para saber si la línea tiene con qué enviarlo. */
    fun sendTest(context: Context, contact: SmsContact) {
        _testResult.value = SmsTestResult.SENDING
        send(context, contact, context.getString(R.string.sms_test_text), attempt = 1, test = true)
    }

    fun send(context: Context, contact: SmsContact, text: String, attempt: Int, test: Boolean) {
        val app = context.applicationContext
        val result = Intent(app, ArrivalSmsReceiver::class.java)
            .setAction(ACTION_SENT)
            .putExtra(EXTRA_NAME, contact.name)
            .putExtra(EXTRA_NUMBER, contact.number)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_ATTEMPT, attempt)
            .putExtra(EXTRA_TEST, test)
        val sent = PendingIntent.getBroadcast(
            app,
            if (test) 2 else 1,
            result,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            val manager = smsManager(app)
            val parts = manager.divideMessage(text)
            if (parts.size <= 1) {
                manager.sendTextMessage(contact.number, null, text, sent, null)
            } else {
                // Solo el último trozo avisa de cómo salió: así llega un único resultado.
                val intents = ArrayList(parts.indices.map { if (it == parts.lastIndex) sent else null })
                manager.sendMultipartTextMessage(contact.number, null, parts, intents, null)
            }
        } catch (e: RuntimeException) {
            // Sin permiso, sin línea o con un número que Android no acepta.
            Log.w(TAG, "No se pudo mandar el SMS", e)
            handleResult(app, result, Activity.RESULT_CANCELED + 100)
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }

    /** Lo que se hace al saber cómo salió el SMS. */
    fun handleResult(context: Context, intent: Intent, resultCode: Int) {
        val contact = SmsContact(
            intent.getStringExtra(EXTRA_NAME).orEmpty(),
            intent.getStringExtra(EXTRA_NUMBER) ?: return
        )
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 1)
        val test = intent.getBooleanExtra(EXTRA_TEST, false)
        val who = contact.name.ifBlank { ArrivalSms.displayNumber(contact.number) }

        if (resultCode == Activity.RESULT_OK) {
            if (test) _testResult.value = SmsTestResult.SENT
            notify(context, context.getString(R.string.sms_sent_title, who), text, contact, text, failed = false)
            return
        }

        val noSignal = resultCode == SmsManager.RESULT_ERROR_NO_SERVICE ||
            resultCode == SmsManager.RESULT_ERROR_RADIO_OFF
        if (test) {
            _testResult.value = if (noSignal) SmsTestResult.NO_SIGNAL else SmsTestResult.FAILED
            return
        }
        // Sin señal (un túnel, un sótano), más tarde puede salir: se reintenta solo.
        if (noSignal && attempt < MAX_ATTEMPTS) {
            scheduleRetry(context, contact, text, attempt + 1)
            return
        }
        val reason = context.getString(if (noSignal) R.string.sms_failed_no_signal else R.string.sms_failed_balance)
        notify(context, context.getString(R.string.sms_failed_title, who), reason, contact, text, failed = true)
    }

    private fun scheduleRetry(context: Context, contact: SmsContact, text: String, attempt: Int) {
        val work = OneTimeWorkRequestBuilder<ArrivalSmsRetryWorker>()
            .setInitialDelay(RETRY_MINUTES, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    EXTRA_NAME to contact.name,
                    EXTRA_NUMBER to contact.number,
                    EXTRA_TEXT to text,
                    EXTRA_ATTEMPT to attempt
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    /** El enlace de WhatsApp al contacto con el mismo texto; un celular peruano lleva el 51 delante. */
    fun whatsappUri(contact: SmsContact, text: String): Uri {
        val digits = contact.number.filter { it.isDigit() }
        val phone = if (digits.length == 9 && digits.startsWith("9")) "51$digits" else digits
        return Uri.parse("https://wa.me/$phone?text=" + Uri.encode(text))
    }

    private fun notify(
        context: Context,
        title: String,
        body: String,
        contact: SmsContact,
        text: String,
        failed: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrival_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)

        if (failed) {
            val retry = PendingIntent.getBroadcast(
                context,
                3,
                Intent(context, ArrivalSmsReceiver::class.java)
                    .setAction(ACTION_RETRY)
                    .putExtra(EXTRA_NAME, contact.name)
                    .putExtra(EXTRA_NUMBER, contact.number)
                    .putExtra(EXTRA_TEXT, text),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val whatsapp = PendingIntent.getActivity(
                context,
                4,
                Intent(Intent.ACTION_VIEW, whatsappUri(contact, text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, context.getString(R.string.sms_action_retry), retry)
                .addAction(0, context.getString(R.string.sms_action_whatsapp), whatsapp)
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Se quitó el permiso justo ahora: no hay dónde avisar.
        }
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sms_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.sms_channel_description) }
        )
    }
}

/** Recibe de Android cómo salió el SMS, y el «Reintentar» de la notificación. */
class ArrivalSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ArrivalSmsSender.ACTION_SENT -> ArrivalSmsSender.handleResult(context, intent, resultCode)
            ArrivalSmsSender.ACTION_RETRY -> {
                val number = intent.getStringExtra(ArrivalSmsSender.EXTRA_NUMBER) ?: return
                val text = intent.getStringExtra(ArrivalSmsSender.EXTRA_TEXT) ?: return
                ArrivalSmsSender.cancelNotification(context)
                ArrivalSmsSender.send(
                    context,
                    SmsContact(intent.getStringExtra(ArrivalSmsSender.EXTRA_NAME).orEmpty(), number),
                    text,
                    // Lo pidió el usuario: si vuelve a fallar sin señal, se reintenta otra tanda.
                    attempt = 1,
                    test = false
                )
            }
        }
    }
}

/** Reintenta el SMS que no salió por falta de señal. */
class ArrivalSmsRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val number = inputData.getString(ArrivalSmsSender.EXTRA_NUMBER) ?: return Result.success()
        val text = inputData.getString(ArrivalSmsSender.EXTRA_TEXT) ?: return Result.success()
        ArrivalSmsSender.send(
            applicationContext,
            SmsContact(inputData.getString(ArrivalSmsSender.EXTRA_NAME).orEmpty(), number),
            text,
            attempt = inputData.getInt(ArrivalSmsSender.EXTRA_ATTEMPT, ArrivalSmsSender.MAX_ATTEMPTS),
            test = false
        )
        return Result.success()
    }
}
