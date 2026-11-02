package com.peru.expresdm.data.net

import android.util.Log
import com.peru.expresdm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Motivo por el que una llamada al servidor no salió bien. */
enum class ApiFailure {
    /** No hay servidor configurado: la app funciona solo con lo del teléfono. */
    NOT_CONFIGURED,

    /** Sin conexión, servidor apagado o no respondió a tiempo. */
    NETWORK,

    /** Hace falta iniciar sesión, o la sesión caducó. */
    UNAUTHORIZED,

    /** El servidor rechazó los datos (400) o ya existían (409). */
    REJECTED,

    /** Falló el servidor (5xx) o devolvió algo que no supimos leer. */
    SERVER
}

/** Resultado de una llamada: o los datos, o el motivo del fallo. */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    /**
     * [message] es el texto que manda el servidor, listo para mostrar, y [code]
     * su nombre del error (`limite_asistente`, `solo_premium`…), para cuando la
     * app tiene que hacer algo distinto según cuál sea.
     */
    data class Failure(
        val reason: ApiFailure,
        val message: String? = null,
        val code: String? = null
    ) : ApiResult<Nothing>
}

/**
 * Cliente del servidor de Subo.
 *
 * Usa HttpURLConnection, igual que el cliente de Gemini: son llamadas sencillas
 * y así el APK no carga una librería de red entera.
 *
 * Si no hay servidor configurado (`BACKEND_URL` vacío en local.properties), todo
 * devuelve [ApiFailure.NOT_CONFIGURED] y la app sigue funcionando con los datos
 * del teléfono, como antes de que existiera el servidor.
 */
object ApiClient {

    private const val TAG = "ApiClient"
    private val TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20).toInt()

    /** Dirección del servidor, sin barra final. */
    val baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    suspend fun get(path: String, token: String? = null): ApiResult<JSONObject> =
        request("GET", path, body = null, token = token)

    suspend fun post(path: String, body: JSONObject, token: String? = null): ApiResult<JSONObject> =
        request("POST", path, body, token)

    /** [body] hace falta pocas veces, como al borrar la cuenta, que pide la contraseña. */
    suspend fun delete(path: String, token: String? = null, body: JSONObject? = null): ApiResult<JSONObject> =
        request("DELETE", path, body = body, token = token)

    /**
     * Se llama cuando el servidor rechaza con 401 un token que se le mandó: la
     * sesión ya no vale (cambió la contraseña en otro teléfono, se borró la
     * cuenta). Lo pone [AccountRepository] para cerrar la sesión en el acto.
     */
    @Volatile
    var onUnauthorized: ((token: String) -> Unit)? = null

    /** Sube un archivo tal cual (una foto), no un JSON. */
    suspend fun postBytes(
        path: String,
        bytes: ByteArray,
        contentType: String,
        token: String? = null
    ): ApiResult<JSONObject> = request("POST", path, body = null, token = token, bytes = bytes, contentType = contentType)

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
        bytes: ByteArray? = null,
        contentType: String? = null
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext ApiResult.Failure(ApiFailure.NOT_CONFIGURED)

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlFor(baseUrl, path)).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                } else if (bytes != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                    setFixedLengthStreamingMode(bytes.size)
                }
            }
            val payload = body?.toString()?.toByteArray(Charsets.UTF_8) ?: bytes
            payload?.let { data -> connection.outputStream.use { it.write(data) } }

            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                if (code == 401 && token != null) onUnauthorized?.invoke(token)
                return@withContext ApiResult.Failure(reasonFor(code), fieldOf(error, "message"), fieldOf(error, "error"))
            }

            // 204 y otras respuestas sin cuerpo: no hay nada que leer.
            val text = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            ApiResult.Success(if (text.isBlank()) JSONObject() else JSONObject(text))
        } catch (io: IOException) {
            Log.w(TAG, "Sin conexión con el servidor en $method $path", io)
            ApiResult.Failure(ApiFailure.NETWORK, io.message)
        } catch (json: JSONException) {
            Log.w(TAG, "El servidor devolvió algo ilegible en $method $path", json)
            ApiResult.Failure(ApiFailure.SERVER, json.message)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * La base de datos está en Canadá; si el servidor corre en otra región,
     * cada consulta cruza el continente y tarda segundos de más. Por eso toda
     * llamada a nuestro servidor le pide que corra allí mismo.
     */
    const val REGION_PARAM = "forceFunctionRegion=ca-central-1"

    /** La dirección completa de [path] en el servidor [base], con la región pedida. */
    fun urlFor(base: String, path: String): String {
        val url = base + path
        // Un ancla al final (#...) no se manda al servidor: el parámetro va antes.
        val hash = url.indexOf('#')
        val (main, anchor) = if (hash >= 0) url.substring(0, hash) to url.substring(hash) else url to ""
        if (main.contains(REGION_PARAM)) return url
        val separator = if (main.contains('?')) "&" else "?"
        return main + separator + REGION_PARAM + anchor
    }

    private fun reasonFor(code: Int): ApiFailure = when (code) {
        401 -> ApiFailure.UNAUTHORIZED
        // 403: hay sesión, pero no puede hacer eso (cuenta bloqueada, sin permiso).
        // 422: una foto que no pasó la revisión. En ambos el mensaje dice por qué.
        400, 403, 404, 409, 422, 429 -> ApiFailure.REJECTED
        else -> ApiFailure.SERVER
    }

    /** El servidor manda `{"error":..., "message":...}`: el nombre del error y el texto para mostrar. */
    private fun fieldOf(errorBody: String?, field: String): String? = errorBody
        ?.let { runCatching { JSONObject(it).optString(field).takeIf(String::isNotBlank) }.getOrNull() }
}
