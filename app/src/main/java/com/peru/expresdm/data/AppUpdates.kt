package com.peru.expresdm.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.peru.expresdm.BuildConfig
import com.peru.expresdm.data.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Una versión de la app más nueva que la instalada. */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    /** Qué trae, para contarlo al ofrecerla; puede venir vacío. */
    val notes: String,
    /** De dónde se baja el instalador. */
    val url: String
)

/**
 * Actualizaciones de la app.
 *
 * Subo no se reparte por una tienda: el instalador está en la web, junto a un
 * archivo (`descargas/version.json`) que dice cuál es la última versión. La app
 * lo mira al abrirse, cuando el servidor avisa y de vez en cuando con ella
 * cerrada; si hay una más nueva
 * la ofrece, la baja y se la pasa al instalador de Android, que comprueba que
 * venga firmada como la que ya está puesta.
 */
object AppUpdates {

    private const val TAG = "AppUpdates"
    private val TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20).toInt()

    private const val PREFS_NAME = "app_updates"
    private const val KEY_OFFERED_CODE = "offered_code"
    private const val KEY_OFFERED_AT = "offered_at"
    private const val KEY_NOTIFIED_CODE = "notified_code"

    /** Al abrir la app, la misma versión no se vuelve a ofrecer hasta pasado esto. */
    private val OFFER_EVERY_MS = TimeUnit.HOURS.toMillis(24)

    /** Tiene que coincidir con el proveedor del manifiesto y con `file_paths.xml`. */
    const val FILES_AUTHORITY_SUFFIX = ".archivos"
    private const val DOWNLOAD_FOLDER = "actualizacion"

    private val folderUrl = "${BuildConfig.WEB_URL}/descargas"

    /** Solo en la compilación de pruebas: hace como si hubiera puesta una versión vieja. */
    @Volatile
    var debugPretendOld = false

    private val installedCode: Int get() = if (debugPretendOld) 0 else BuildConfig.VERSION_CODE

    /** La versión que hay en la web, si es más nueva que esta. `null` también si no se pudo mirar. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            // La hora en la dirección evita que conteste una copia guardada por el camino.
            val text = open("$folderUrl/version.json?t=${System.currentTimeMillis()}").run {
                try {
                    if (responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
            parse(text, installedCode, folderUrl, Locale.getDefault().language)?.also { announce(it) }
        } catch (e: IOException) {
            Log.i(TAG, "No se pudo mirar si hay versión nueva", e)
            null
        }
    }

    /**
     * Le cuenta al servidor que hay versión nueva, para que avise al momento a
     * los demás teléfonos. Él comprueba que sea verdad y avisa una sola vez.
     */
    private suspend fun announce(update: AppUpdate) {
        if (ApiClient.isConfigured) ApiClient.post("/api/app/update", JSONObject().put("versionCode", update.versionCode))
    }

    /**
     * Lo que dice `version.json`, si anuncia una versión posterior a [installed].
     * La dirección del instalador puede venir entera o relativa a [folder].
     */
    fun parse(text: String, installed: Int, folder: String, language: String): AppUpdate? = try {
        val json = JSONObject(text)
        val code = json.getInt("versionCode")
        val file = json.optString("url").ifBlank { "subo.apk" }
        val notes = json.optString("notes_$language").ifBlank { json.optString("notes") }
        if (code <= installed) {
            null
        } else {
            AppUpdate(
                versionCode = code,
                versionName = json.optString("versionName").ifBlank { code.toString() },
                notes = notes.trim(),
                url = if (file.startsWith("https://")) file else "$folder/${file.trimStart('/')}"
            )
        }
    } catch (e: JSONException) {
        Log.w(TAG, "version.json ilegible", e)
        null
    }

    /** Si toca ofrecerla al abrir la app: una vez al día, para no insistir a quien dijo "más tarde". */
    fun shouldOffer(context: Context, update: AppUpdate, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        val recent = prefs.getInt(KEY_OFFERED_CODE, 0) == update.versionCode &&
            now - prefs.getLong(KEY_OFFERED_AT, 0L) < OFFER_EVERY_MS
        if (recent) return false
        prefs.edit().putInt(KEY_OFFERED_CODE, update.versionCode).putLong(KEY_OFFERED_AT, now).apply()
        return true
    }

    /** Si toca avisar con una notificación: una sola vez por versión. */
    fun shouldNotify(context: Context, update: AppUpdate): Boolean {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_NOTIFIED_CODE, 0) >= update.versionCode) return false
        prefs.edit().putInt(KEY_NOTIFIED_CODE, update.versionCode).apply()
        return true
    }

    /**
     * Baja el instalador. [onProgress] va diciendo el porcentaje, o `null` si el
     * servidor no dice cuánto pesa. Devuelve el archivo, o `null` si falló.
     */
    suspend fun download(context: Context, update: AppUpdate, onProgress: (Int?) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, DOWNLOAD_FOLDER).apply { mkdirs() }
            // Los de versiones anteriores ya no sirven.
            folder.listFiles()?.forEach { it.delete() }
            val target = File(folder, "subo-${update.versionCode}.apk")
            try {
                val connection = open(update.url)
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    val total = connection.contentLengthLong
                    var done = 0L
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                onProgress(if (total > 0) (done * 100 / total).toInt() else null)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                target
            } catch (e: IOException) {
                Log.i(TAG, "No se pudo bajar la versión nueva", e)
                target.delete()
                null
            }
        }

    /** Le pasa el instalador a Android, que pregunta al usuario antes de ponerlo. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILES_AUTHORITY_SUFFIX", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
