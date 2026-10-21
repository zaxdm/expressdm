package com.peru.expresdm.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.peru.expresdm.MainActivity
import com.peru.expresdm.R
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.ArrivalRules
import com.peru.expresdm.data.NearbyRoutes
import com.peru.expresdm.data.Premium
import com.peru.expresdm.ui.formatDistance
import com.peru.expresdm.voice.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Parada donde el usuario quiere que le avisen al llegar. */
data class ArrivalTarget(
    val routeId: String,
    val routeName: String,
    val stopName: String,
    val latitude: Double,
    val longitude: Double,
    /**
     * Con transbordo, la bajada del micro siguiente: al llegar a esta se pasa
     * a vigilar aquella, sin que el usuario tenga que tocar nada.
     */
    val next: ArrivalTarget? = null,
    /** Lo que la guía hablada dice al empezar: hasta dónde caminar y qué micro tomar. */
    val intro: String? = null
)

data class ArrivalState(
    /** Aviso activo; `null` si no hay ninguno. */
    val target: ArrivalTarget? = null,
    /** Distancia a la parada en la última lectura útil del GPS. */
    val distanceMeters: Double? = null,
    /** Se llegó y se avisó: la interfaz lo muestra una vez y lo limpia. */
    val arrivedAt: ArrivalTarget? = null,
    /** No se pudo activar (sin permiso o con la ubicación apagada). */
    val failed: Boolean = false
)

/**
 * Aviso de llegada: sigue la ubicación del usuario mientras va en el micro y le
 * avisa cuando está llegando a su parada: una notificación y, según Ajustes,
 * vibración y sonido.
 *
 * Es un servicio en primer plano para que funcione con la pantalla apagada y la
 * app cerrada, que es justo cuando hace falta. Se detiene solo al avisar, al
 * cancelarlo o, por si el usuario se olvida, al cabo de unas horas.
 */
class ArrivalAlarmService : Service(), LocationListener {

    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotifiedDistance: Double? = null

    /** La última lectura del GPS: el SMS de «ya llegué» lleva ese punto. */
    private var lastLocation: Location? = null

    /** Se le vio lejos de la parada: a partir de ahí tiene sentido el preaviso. */
    private var cameFromFar = false
    private var earlyWarned = false

    /**
     * Mantiene el teléfono atento con la pantalla apagada: sin él, Android
     * puede dormirse entre dos lecturas del GPS y dar el aviso tarde.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val giveUp = Runnable {
        Log.i(TAG, "Aviso de llegada caducado sin llegar a la parada")
        _state.value = ArrivalState()
        stopEverything()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.toTarget()?.let { startWatching(it) } ?: stopEverything()
            ACTION_CANCEL -> {
                _state.value = ArrivalState()
                stopEverything()
            }
            else -> stopEverything()
        }
        // Si Android cierra el proceso, el sistema vuelve a entregar el pedido y
        // el aviso sigue activo: el usuario cuenta con él.
        return START_REDELIVER_INTENT
    }

    /** La guía hablada del viaje es de Premium y se apaga en Ajustes. */
    private fun guideOn(): Boolean = Premium.active && AppSettings.get(this).voiceGuide

    private fun startWatching(target: ArrivalTarget, announce: Boolean = true) {
        // Android exige mostrar la notificación nada más arrancar el servicio.
        createChannels()
        try {
            startForegroundCompat(buildTrackingNotification(target, distanceMeters = null))
        } catch (e: RuntimeException) {
            // Si Android cerró la app y la vuelve a levantar sin ella a la vista,
            // puede no dejar seguir la ubicación: el aviso se da por perdido en
            // vez de tumbar la app.
            Log.e(TAG, "Android no dejó retomar el aviso de llegada", e)
            _state.value = ArrivalState(failed = true)
            stopEverything()
            return
        }

        val manager = getSystemService<LocationManager>()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager?.isProviderEnabled(it) == true }
        if (!hasLocationPermission() || manager == null || providers.isEmpty()) {
            _state.value = ArrivalState(failed = true)
            stopEverything()
            return
        }

        stopLocationUpdates()
        _state.value = ArrivalState(target = target)
        lastNotifiedDistance = null
        cameFromFar = false
        earlyWarned = false

        try {
            // Aquí sirven también las lecturas de la red: basta con saber que se
            // acerca, no hace falta dibujar nada con precisión.
            providers.forEach { provider ->
                manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper())
            }
            locationManager = manager
        } catch (security: SecurityException) {
            Log.e(TAG, "Permiso de ubicación revocado al activar el aviso", security)
            _state.value = ArrivalState(failed = true)
            stopEverything()
            return
        }

        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, MAX_WATCH_MS)
        holdAwake()

        if (announce && guideOn()) {
            Speaker.speak(this, target.intro ?: getString(R.string.voice_guide_start, target.stopName))
        }
    }

    override fun onLocationChanged(location: Location) {
        val target = _state.value.target ?: return
        lastLocation = location
        val distance = NearbyRoutes.distanceMeters(location.latitude, location.longitude, target.latitude, target.longitude)
        val accuracy = if (location.hasAccuracy()) location.accuracy else null

        val radius = AppSettings.get(this).arrivalDistanceMeters.toDouble()

        when (ArrivalRules.evaluate(distance, accuracy, radius)) {
            ArrivalRules.Reading.ARRIVED -> arrive(target)
            ArrivalRules.Reading.IMPRECISE -> Unit
            ArrivalRules.Reading.APPROACHING -> {
                _state.value = _state.value.copy(distanceMeters = distance)
                if (distance > ArrivalRules.EARLY_WARNING_METERS) {
                    cameFromFar = true
                } else if (!earlyWarned && ArrivalRules.isEarlyWarning(distance, cameFromFar, radius)) {
                    earlyWarned = true
                    warnEarly(target, distance)
                }
                val last = lastNotifiedDistance
                // La notificación fija se actualiza cada tanto, no con cada lectura.
                if (last == null || abs(last - distance) >= NOTIFICATION_STEP_METERS) {
                    lastNotifiedDistance = distance
                    getSystemService<NotificationManager>()
                        ?.notify(TRACKING_NOTIFICATION_ID, buildTrackingNotification(target, distance))
                }
            }
        }
    }

    @Deprecated("Obligatorio antes de API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    /**
     * El preaviso de Premium: unas cuadras antes, un toque corto para ir
     * guardando el teléfono. El aviso de verdad llega después, como siempre.
     */
    private fun warnEarly(target: ArrivalTarget, distanceMeters: Double) {
        val settings = AppSettings.get(this)
        if (!settings.arrivalEarlyWarning || !Premium.active) return

        val distance = formatDistance(distanceMeters)
        if (canPostNotifications()) {
            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle(getString(R.string.arrival_early_title, target.stopName))
                .setContentText(getString(R.string.arrival_early_text, distance))
                .setSmallIcon(R.drawable.ic_arrival_alarm)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                // Sin setSilent: Android no asoma encima de otra app una
                // notificación marcada como silenciosa. El canal ya va mudo.
                .setVibrate(NO_VIBRATION)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .build()
            notifyArrival(notification)
        }
        if (settings.arrivalVibration) ArrivalSignal.nudge(this)
        if (settings.arrivalVoice || guideOn()) {
            Speaker.speak(this, getString(R.string.arrival_early_voice, distance, target.stopName))
        }
    }

    private fun arrive(target: ArrivalTarget) {
        stopLocationUpdates()
        postArrivalNotification(target)
        val settings = AppSettings.get(this)
        ArrivalSignal.play(this, vibrate = settings.arrivalVibration, sound = settings.arrivalSound)
        // Con transbordo y guía, lo que toca ahora es el micro siguiente.
        val next = target.next?.takeIf { guideOn() }
        if (settings.arrivalVoice || guideOn()) {
            val text = if (next != null) {
                getString(R.string.voice_guide_transfer, target.stopName, next.routeName, next.stopName)
            } else {
                getString(R.string.arrival_arrived, target.stopName)
            }
            // Después del tono, para que se entienda lo que dice.
            Speaker.speak(this, text, delayMs = if (settings.arrivalSound) VOICE_AFTER_SOUND_MS else 0)
        }
        if (next != null) {
            startWatching(next, announce = false)
            // Si no se pudo seguir, startWatching ya lo dejó todo parado.
            if (_state.value.target == next) _state.value = _state.value.copy(arrivedAt = target)
            return
        }
        _state.value = ArrivalState(arrivedAt = target)
        // Premium: avisa por SMS a quien eligió, aunque no tenga internet.
        val here = lastLocation
        ArrivalSmsSender.onArrived(this, target.stopName, here?.latitude ?: target.latitude, here?.longitude ?: target.longitude)
        stopEverything()
    }

    private fun holdAwake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subo:aviso_llegada")
            // Caduca solo, por si el servicio muere sin soltarlo.
            ?.apply { acquire(MAX_WATCH_MS) }
    }

    private fun releaseAwake() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stopEverything() {
        handler.removeCallbacks(giveUp)
        releaseAwake()
        stopLocationUpdates()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun stopLocationUpdates() {
        try {
            locationManager?.removeUpdates(this)
        } catch (security: SecurityException) {
            Log.w(TAG, "No se pudieron soltar las actualizaciones de ubicación", security)
        }
        locationManager = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        releaseAwake()
        stopLocationUpdates()
        super.onDestroy()
    }

    // --- Notificaciones ---

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                TRACKING_CHANNEL_ID,
                getString(R.string.arrival_channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.arrival_channel_tracking_description)
                setShowBadge(false)
            }
        )

        // Importancia alta para que se vea encima de lo que esté haciendo. El
        // canal va mudo: la vibración y el sonido los pone ArrivalSignal, según
        // lo que el usuario eligió en Ajustes.
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.arrival_channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.arrival_channel_alert_description)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildTrackingNotification(target: ArrivalTarget, distanceMeters: Double?): Notification {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, ArrivalAlarmService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (distanceMeters == null) {
            getString(R.string.arrival_notification_tracking_waiting, target.routeName)
        } else {
            getString(R.string.arrival_notification_tracking_text, target.routeName, formatDistance(distanceMeters))
        }
        return NotificationCompat.Builder(this, TRACKING_CHANNEL_ID)
            .setContentTitle(getString(R.string.arrival_notification_tracking_title, target.stopName))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_arrival_alarm)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.arrival_action_cancel), cancel)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun postArrivalNotification(target: ArrivalTarget) {
        // Sin permiso solo queda el aviso dentro de la app, si está abierta.
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.arrival_notification_title, target.stopName))
            .setContentText(getString(R.string.arrival_notification_text, target.routeName))
            .setSmallIcon(R.drawable.ic_arrival_alarm)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            // Sin setSilent: Android no asoma encima de otra app una
            // notificación marcada como silenciosa. El canal ya va mudo.
            .setVibrate(NO_VIBRATION)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        notifyArrival(notification)
    }

    private fun notifyArrival(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(ARRIVAL_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // El permiso se quitó entre la comprobación y el aviso: queda el de dentro de la app.
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(TRACKING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(TRACKING_NOTIFICATION_ID, notification)
        }
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun Intent.toTarget(): ArrivalTarget? {
        val routeId = getStringExtra(EXTRA_ROUTE_ID) ?: return null
        return ArrivalTarget(
            routeId = routeId,
            routeName = getStringExtra(EXTRA_ROUTE_NAME).orEmpty(),
            stopName = getStringExtra(EXTRA_STOP_NAME).orEmpty(),
            latitude = getDoubleExtra(EXTRA_LAT, Double.NaN).takeUnless { it.isNaN() } ?: return null,
            longitude = getDoubleExtra(EXTRA_LON, Double.NaN).takeUnless { it.isNaN() } ?: return null,
            next = nextTargets(),
            intro = getStringExtra(EXTRA_INTRO)
        )
    }

    /** Las bajadas que vienen después de la primera, encadenadas. */
    private fun Intent.nextTargets(): ArrivalTarget? {
        val ids = getStringArrayExtra(EXTRA_NEXT_ROUTE_IDS) ?: return null
        val names = getStringArrayExtra(EXTRA_NEXT_ROUTE_NAMES) ?: return null
        val stops = getStringArrayExtra(EXTRA_NEXT_STOP_NAMES) ?: return null
        val lats = getDoubleArrayExtra(EXTRA_NEXT_LATS) ?: return null
        val lons = getDoubleArrayExtra(EXTRA_NEXT_LONS) ?: return null
        if (listOf(names.size, stops.size, lats.size, lons.size).any { it != ids.size }) return null
        return ids.indices.reversed().fold(null as ArrivalTarget?) { following, index ->
            ArrivalTarget(ids[index], names[index], stops[index], lats[index], lons[index], next = following)
        }
    }

    companion object {
        private const val TAG = "ArrivalAlarm"
        private const val TRACKING_CHANNEL_ID = "aviso_llegada_seguimiento"
        private const val ALERT_CHANNEL_ID = "aviso_llegada"
        private const val TRACKING_NOTIFICATION_ID = 1003
        private const val ARRIVAL_NOTIFICATION_ID = 1004

        private const val ACTION_START = "com.peru.expresdm.arrival.START"
        private const val ACTION_CANCEL = "com.peru.expresdm.arrival.CANCEL"
        private const val EXTRA_ROUTE_ID = "routeId"
        private const val EXTRA_ROUTE_NAME = "routeName"
        private const val EXTRA_STOP_NAME = "stopName"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_INTRO = "intro"
        private const val EXTRA_NEXT_ROUTE_IDS = "nextRouteIds"
        private const val EXTRA_NEXT_ROUTE_NAMES = "nextRouteNames"
        private const val EXTRA_NEXT_STOP_NAMES = "nextStopNames"
        private const val EXTRA_NEXT_LATS = "nextLats"
        private const val EXTRA_NEXT_LONS = "nextLons"

        private const val UPDATE_INTERVAL_MS = 5_000L

        /** Antes de Android 8, una notificación solo asoma si dice que vibra, aunque sea nada. */
        private val NO_VIBRATION = longArrayOf(0L)

        /** Lo que dura el tono del aviso, más un respiro: la voz entra después. */
        const val VOICE_AFTER_SOUND_MS = 3_600L
        private const val NOTIFICATION_STEP_METERS = 100.0

        /** Ningún trayecto en micro por Trujillo dura tanto: si no llegó, se olvidó del aviso. */
        private const val MAX_WATCH_MS = 3 * 60 * 60 * 1000L

        private val _state = MutableStateFlow(ArrivalState())

        /** Aviso activo y su distancia, observable desde la interfaz. */
        val state: StateFlow<ArrivalState> = _state.asStateFlow()

        fun start(context: Context, target: ArrivalTarget) {
            val intent = Intent(context, ArrivalAlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROUTE_ID, target.routeId)
                .putExtra(EXTRA_ROUTE_NAME, target.routeName)
                .putExtra(EXTRA_STOP_NAME, target.stopName)
                .putExtra(EXTRA_LAT, target.latitude)
                .putExtra(EXTRA_LON, target.longitude)
                .putExtra(EXTRA_INTRO, target.intro)
            val following = generateSequence(target.next) { it.next }.toList()
            if (following.isNotEmpty()) {
                intent.putExtra(EXTRA_NEXT_ROUTE_IDS, following.map { it.routeId }.toTypedArray())
                    .putExtra(EXTRA_NEXT_ROUTE_NAMES, following.map { it.routeName }.toTypedArray())
                    .putExtra(EXTRA_NEXT_STOP_NAMES, following.map { it.stopName }.toTypedArray())
                    .putExtra(EXTRA_NEXT_LATS, following.map { it.latitude }.toDoubleArray())
                    .putExtra(EXTRA_NEXT_LONS, following.map { it.longitude }.toDoubleArray())
            }
            // Se marca ya, para que la interfaz no espere a que arranque el servicio.
            _state.value = ArrivalState(target = target)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            if (_state.value.target != null) {
                context.startService(Intent(context, ArrivalAlarmService::class.java).setAction(ACTION_CANCEL))
            }
            _state.value = ArrivalState()
        }

        /** El "llegaste" o el fallo ya se mostró en la app. */
        fun consumeMessages() {
            val current = _state.value
            if (current.arrivedAt != null || current.failed) {
                _state.value = current.copy(arrivedAt = null, failed = false)
            }
        }
    }
}
