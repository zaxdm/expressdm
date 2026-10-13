package com.peru.expresdm.data

import android.content.Context
import android.util.Log
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.data.net.ApiFailure
import com.peru.expresdm.data.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Cómo terminó el intento de publicar una alerta. */
sealed interface PublishResult {
    data class Published(val alert: RoadAlert) : PublishResult

    /** No se pudo: [needsSignIn] distingue "inicia sesión" de "no hay conexión". */
    data class Failed(val needsSignIn: Boolean, val message: String? = null) : PublishResult
}

/**
 * Alertas que los usuarios dejan en el mapa.
 *
 * Con servidor configurado, las alertas son de todos: se piden las que hay
 * cerca del usuario y publicar o votar escribe allí. Lo último que se recibió
 * queda guardado en el teléfono, así que sin conexión la app sigue mostrando
 * algo en lugar de una pantalla vacía.
 *
 * Sin servidor (`BACKEND_URL` vacío), funciona como antes: todo local.
 */
object AlertRepository {

    private const val TAG = "AlertRepository"
    private const val FILE_NAME = "alertas.json"

    /** Radio que se pide al servidor: cubre Trujillo y alrededores. */
    private const val RADIUS_METERS = 15_000

    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _alerts = MutableStateFlow<List<RoadAlert>>(emptyList())

    /** Todas las alertas conocidas; la vigencia se filtra con [AlertRules.active]. */
    val alerts: StateFlow<List<RoadAlert>> = _alerts.asStateFlow()

    /** `true` cuando las alertas vienen del servidor y se comparten con los demás. */
    val isShared: Boolean get() = ApiClient.isConfigured

    fun init(context: Context) {
        if (file != null) return
        val alertsFile = File(context.applicationContext.filesDir, FILE_NAME)
        file = alertsFile

        val now = System.currentTimeMillis()
        val stored = runCatching { if (alertsFile.exists()) decode(alertsFile.readText()) else emptyList() }
            .onFailure { Log.w(TAG, "No se pudieron leer las alertas", it) }
            .getOrDefault(emptyList())

        // Las caducadas no vuelven a cargarse: el archivo no crece sin fin.
        _alerts.value = stored.filter { AlertRules.isActive(it, now) }
        persist()
    }

    /**
     * Pide al servidor las alertas cerca del usuario. Si falla, se queda lo que
     * ya había: es mejor mostrar algo viejo que nada.
     */
    suspend fun refresh(latitude: Double, longitude: Double) {
        if (!ApiClient.isConfigured) return

        val path = "/api/alerts?lat=$latitude&lon=$longitude&radius=$RADIUS_METERS"
        when (val result = ApiClient.get(path, AccountRepository.token)) {
            is ApiResult.Failure -> Log.i(TAG, "No se pudieron traer las alertas: ${result.reason}")
            is ApiResult.Success -> {
                val fromServer = parseAlerts(result.data.optJSONArray("alerts"))
                withContext(Dispatchers.Main) {
                    _alerts.value = fromServer
                    persist()
                }
            }
        }
    }

    suspend fun publish(
        type: AlertType,
        latitude: Double,
        longitude: Double,
        comment: String?,
        placeName: String?,
        routeId: String? = null,
        imageUrl: String? = null,
        now: Long = System.currentTimeMillis()
    ): PublishResult {
        if (!ApiClient.isConfigured) {
            // Sin servidor, la alerta se queda en este teléfono.
            val local = RoadAlert(
                id = UUID.randomUUID().toString(),
                type = type,
                latitude = latitude,
                longitude = longitude,
                placeName = placeName,
                comment = comment?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
                createdByMe = true,
                routeId = routeId
            )
            add(local)
            return PublishResult.Published(local)
        }

        val body = JSONObject()
            .put("type", type.name)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("placeName", placeName ?: JSONObject.NULL)
            .put("comment", comment ?: JSONObject.NULL)
            .put("routeId", routeId ?: JSONObject.NULL)
            .put("imageUrl", imageUrl ?: JSONObject.NULL)

        return when (val result = ApiClient.post("/api/alerts", body, AccountRepository.token)) {
            is ApiResult.Success -> {
                val alert = parseAlert(result.data)
                if (alert == null) {
                    PublishResult.Failed(needsSignIn = false)
                } else {
                    withContext(Dispatchers.Main) {
                        add(alert)
                        AccountRepository.noteAlertPublished()
                    }
                    PublishResult.Published(alert)
                }
            }

            is ApiResult.Failure -> PublishResult.Failed(
                needsSignIn = result.reason == ApiFailure.UNAUTHORIZED,
                message = result.message
            )
        }
    }

    /**
     * Vota "sigue ahí" o "ya no está".
     *
     * El cambio se ve al momento y se manda al servidor en segundo plano: quien
     * vota no tiene por qué esperar a la red.
     */
    fun vote(alertId: String, vote: AlertVote, now: Long = System.currentTimeMillis()) {
        update(alertId) { AlertRules.vote(it, vote, now) }
        if (!ApiClient.isConfigured) return

        scope.launch {
            val body = JSONObject().put("vote", vote.name)
            when (val result = ApiClient.post("/api/alerts/$alertId/vote", body, AccountRepository.token)) {
                is ApiResult.Success -> parseAlert(result.data)?.let { fromServer ->
                    withContext(Dispatchers.Main) {
                        // El recuento bueno es el del servidor, que ve los votos de todos.
                        _alerts.value = _alerts.value.map { if (it.id == fromServer.id) fromServer else it }
                        persist()
                    }
                }

                is ApiResult.Failure -> Log.i(TAG, "No se pudo enviar el voto: ${result.reason}")
            }
        }
    }

    /**
     * Busca el nombre de la calle de una alerta ya publicada. Va aparte para no
     * hacer esperar al usuario, y termina aunque cierre la hoja de reporte.
     */
    fun resolvePlaceName(alertId: String) {
        val alert = _alerts.value.firstOrNull { it.id == alertId } ?: return
        if (alert.placeName != null) return
        scope.launch {
            val name = PlaceSearch.streetName(alert.latitude, alert.longitude) ?: return@launch
            withContext(Dispatchers.Main) {
                update(alertId) { it.copy(placeName = name) }
            }
        }
    }

    private fun add(alert: RoadAlert) {
        _alerts.value = listOf(alert) + _alerts.value.filterNot { it.id == alert.id }
        persist()
    }

    private fun update(alertId: String, change: (RoadAlert) -> RoadAlert) {
        _alerts.value = _alerts.value.map { if (it.id == alertId) change(it) else it }
        persist()
    }

    private fun persist() {
        val target = file ?: return
        runCatching { target.writeText(encode(_alerts.value)) }
            .onFailure { Log.e(TAG, "No se pudieron guardar las alertas", it) }
    }

    // --- Traducción de lo que manda el servidor ---

    private fun parseAlerts(array: JSONArray?): List<RoadAlert> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { parseAlert(array.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun parseAlert(json: JSONObject): RoadAlert? = try {
        val type = AlertType.entries.firstOrNull { it.name == json.optString("type") }
        if (type == null) {
            null
        } else {
            RoadAlert(
                id = json.getString("id"),
                type = type,
                latitude = json.getDouble("latitude"),
                longitude = json.getDouble("longitude"),
                placeName = json.optStringOrNull("placeName"),
                comment = json.optStringOrNull("comment"),
                createdAt = json.getLong("createdAt"),
                confirmations = json.optInt("confirmations"),
                dismissals = json.optInt("dismissals"),
                lastConfirmedAt = json.optLong("lastConfirmedAt", json.getLong("createdAt")),
                myVote = json.optStringOrNull("myVote")?.let { name ->
                    AlertVote.entries.firstOrNull { it.name == name }
                },
                createdByMe = json.optBoolean("createdByMe"),
                routeId = json.optStringOrNull("routeId"),
                imageUrl = json.optStringOrNull("imageUrl"),
                authorName = json.optStringOrNull("authorName"),
                authorAvatarUrl = json.optStringOrNull("authorAvatarUrl"),
                authorReputation = Reputation.fromName(json.optStringOrNull("authorReputation"))
            )
        }
    } catch (e: JSONException) {
        Log.w(TAG, "Alerta ilegible del servidor", e)
        null
    }

    // --- Copia local ---

    fun encode(alerts: List<RoadAlert>): String {
        val array = JSONArray()
        alerts.forEach { alert ->
            array.put(
                JSONObject()
                    .put("id", alert.id)
                    .put("type", alert.type.name)
                    .put("lat", alert.latitude)
                    .put("lon", alert.longitude)
                    .put("place", alert.placeName ?: JSONObject.NULL)
                    .put("comment", alert.comment ?: JSONObject.NULL)
                    .put("createdAt", alert.createdAt)
                    .put("confirmations", alert.confirmations)
                    .put("dismissals", alert.dismissals)
                    .put("lastConfirmedAt", alert.lastConfirmedAt)
                    .put("myVote", alert.myVote?.name ?: JSONObject.NULL)
                    .put("createdByMe", alert.createdByMe)
                    .put("routeId", alert.routeId ?: JSONObject.NULL)
                    .put("imageUrl", alert.imageUrl ?: JSONObject.NULL)
                    .put("authorName", alert.authorName ?: JSONObject.NULL)
                    .put("authorAvatarUrl", alert.authorAvatarUrl ?: JSONObject.NULL)
                    .put("authorReputation", alert.authorReputation.name)
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<RoadAlert> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            try {
                val obj = array.getJSONObject(i)
                RoadAlert(
                    id = obj.getString("id"),
                    type = AlertType.valueOf(obj.getString("type")),
                    latitude = obj.getDouble("lat"),
                    longitude = obj.getDouble("lon"),
                    placeName = obj.optStringOrNull("place"),
                    comment = obj.optStringOrNull("comment"),
                    createdAt = obj.getLong("createdAt"),
                    confirmations = obj.optInt("confirmations"),
                    dismissals = obj.optInt("dismissals"),
                    lastConfirmedAt = obj.optLong("lastConfirmedAt", obj.getLong("createdAt")),
                    myVote = obj.optStringOrNull("myVote")?.let { AlertVote.valueOf(it) },
                    createdByMe = obj.optBoolean("createdByMe"),
                    routeId = obj.optStringOrNull("routeId"),
                    imageUrl = obj.optStringOrNull("imageUrl"),
                    authorName = obj.optStringOrNull("authorName"),
                    authorAvatarUrl = obj.optStringOrNull("authorAvatarUrl"),
                    authorReputation = Reputation.fromName(obj.optStringOrNull("authorReputation"))
                )
            } catch (e: JSONException) {
                Log.w(TAG, "Alerta dañada en el archivo, se omite", e)
                null
            } catch (e: IllegalArgumentException) {
                // Un tipo que ya no existe en esta versión de la app.
                null
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
